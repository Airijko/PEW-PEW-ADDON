package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.enums.PortalGateColor;
import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class NaturalPortalGateManager {

    private static final List<String> PORTAL_BLOCK_IDS = List.of(
            "EL_MajorDungeonPortal_D01",
            "EL_MajorDungeonPortal_D02",
            "EL_MajorDungeonPortal_D03",
            "EL_EndgamePortal_Swamp_Dungeon",
            "EL_EndgamePortal_Frozen_Dungeon",
            "EL_EndgamePortal_Golem_Void"
    );

    private static final long DEFAULT_SPAWN_INTERVAL_MINUTES = 30L;
    private static final long DEFAULT_GATE_LIFETIME_MINUTES = 30L;
    private static final int PLACEMENT_ATTEMPTS = 16;
    private static final String PREFIX = "[EndlessLeveling] ";
    private static final int DYNAMIC_MIN_LEVEL = 1;
    private static final int DYNAMIC_MAX_LEVEL = 500;
    private static final int DEFAULT_DYNAMIC_LEVEL_OFFSET_MIN = 0;
    private static final int DEFAULT_DYNAMIC_LEVEL_OFFSET_MAX = 30;
    private static final int DEFAULT_NORMAL_MOB_LEVEL_RANGE = 20;
    private static final int DEFAULT_BOSS_LEVEL_BONUS = 10;
    private static final int DEFAULT_WEIGHT_S = 1;
    private static final int DEFAULT_WEIGHT_A = 6;
    private static final int DEFAULT_WEIGHT_B = 13;
    private static final int DEFAULT_WEIGHT_C = 30;
    private static final int DEFAULT_WEIGHT_D = 25;
    private static final int DEFAULT_WEIGHT_E = 25;
    private static final int AIR_BLOCK_ID = 0;
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 319;
    private static final long REMOVAL_RETRY_INTERVAL_SECONDS = 10L;
    private static final int REMOVAL_RETRY_BATCH_PER_WORLD = 32;

    private static JavaPlugin plugin;
    private static AddonFilesManager filesManager;
    private static ScheduledFuture<?> periodicTask;
    private static ScheduledFuture<?> pendingRemovalTask;
    private static final Map<UUID, Set<GateRemovalRequest>> PENDING_GATE_REMOVALS = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<Long>> PENDING_REMOVAL_CHUNK_LOADS = new ConcurrentHashMap<>();
    private static final Set<ActiveGate> ACTIVE_GATES = ConcurrentHashMap.newKeySet();
    private static volatile int HIGHEST_SEEN_PLAYER_LEVEL = DYNAMIC_MIN_LEVEL;

    private NaturalPortalGateManager() {
    }

        public static void initialize(@Nonnull JavaPlugin owner, @Nullable AddonFilesManager manager) {
        plugin = owner;
        filesManager = manager;
            refreshConfigSnapshot();
            long spawnIntervalMinutesMin = Math.max(1L, resolveSpawnIntervalMinutesMin());
            long spawnIntervalMinutesMax = Math.max(spawnIntervalMinutesMin, resolveSpawnIntervalMinutesMax());
        long gateLifetimeMinutes = resolveGateLifetimeMinutes();
            scheduleNextNaturalSpawnTick();
        pendingRemovalTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
            NaturalPortalGateManager::processPendingRemovals,
            REMOVAL_RETRY_INTERVAL_SECONDS,
            REMOVAL_RETRY_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        log(Level.INFO,
                "[ELPortal] Natural gate spawner enabled: every %d-%d minute(s), lifetime %d minute(s)",
                spawnIntervalMinutesMin,
                spawnIntervalMinutesMax,
            gateLifetimeMinutes);
    }

    public static void shutdown() {
        // Kick out all players from gate instances before shutdown
        kickOutPlayersFromGateInstances();
        
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
        if (pendingRemovalTask != null) {
            pendingRemovalTask.cancel(false);
            pendingRemovalTask = null;
        }
        PENDING_GATE_REMOVALS.clear();
        PENDING_REMOVAL_CHUNK_LOADS.clear();
        ACTIVE_GATES.clear();
    }

    /**
     * Kicks out all players from active gate instances to their spawn/home worlds.
     * Called during shutdown to ensure safe player transitions before instance cleanup.
     */
    private static void kickOutPlayersFromGateInstances() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            int totalPlayersKicked = 0;
            for (ActiveGate gate : new ArrayList<>(ACTIVE_GATES)) {
                World instanceWorld = universe.getWorld(gate.worldUuid());
                if (instanceWorld == null) {
                    continue;
                }

                // Get all players in this instance and kick them to spawn world
                List<Player> playersInInstance = instanceWorld.getPlayers();
                for (Player player : playersInInstance) {
                    try {
                        Ref<EntityStore> entityRef = player.getReference();
                        if (entityRef == null || !entityRef.isValid()) {
                            continue;
                        }

                        Store<EntityStore> store = entityRef.getStore();
                        PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
                        if (playerRef != null) {
                            // Teleport to player's home/spawn world
                            World spawnWorld = universe.getDefaultWorld();
                            if (spawnWorld != null) {
                                PortalLeveledInstanceRouter.teleportPlayerToReturnWorld(playerRef, spawnWorld);
                                totalPlayersKicked++;
                            }
                        }
                    } catch (Exception ex) {
                        log(Level.WARNING,
                                "[ELPortal] Failed to kick player from instance %s: %s",
                                gate.gateId(), ex.getMessage());
                    }
                }
            }

            if (totalPlayersKicked > 0) {
                log(Level.INFO, "[ELPortal] Shut down: kicked %d players from gate instances", totalPlayersKicked);
            }
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Failed to kick out players during shutdown: %s", ex.getMessage());
        }
    }

    @Nonnull
    public static CompletableFuture<Boolean> spawnRandomGateNearPlayer(@Nonnull Player player, boolean isTestSpawn) {
        refreshConfigSnapshot();
        World world = player.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }
        if (!isPortalWorldAllowed(world)) {
            log(Level.INFO,
                    "[ELPortal] Spawn blocked: world %s is not in portal_world_whitelist",
                    world.getName());
            return CompletableFuture.completedFuture(false);
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return CompletableFuture.completedFuture(false);
        }

        // store.getComponent must run on the world thread; defer via world.execute()
        String blockId = pickRandomPortalBlock();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        world.execute(() -> {
            if (!ref.isValid()) {
                future.complete(false);
                return;
            }

            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                future.complete(false);
                return;
            }

            spawnInWorldNearPlayerRefOnThread(world, playerRef, blockId, isTestSpawn, future);
        });
        return future;
    }

    private static void spawnNaturalGateTick() {
        try {
            if (plugin == null) {
                return;
            }
            refreshConfigSnapshot();
            if (filesManager != null && !filesManager.isDungeonGateEnabled()) {
                return;
            }

            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            List<PlayerRef> players = resolveEligiblePlayers(universe);
            if (players.isEmpty()) {
                log(Level.INFO, "[ELPortal] Spawn skipped: no online players in whitelisted worlds");
                return;
            }

            PlayerRef target = players.get((int) (Math.random() * players.size()));
            UUID worldUuid = target.getWorldUuid();
            if (worldUuid == null) {
                return;
            }

            World world = universe.getWorld(worldUuid);
            if (world == null) {
                return;
            }
            if (!isPortalWorldAllowed(world)) {
                return;
            }

            int maxConcurrentSpawns = resolveMaxConcurrentSpawns();
            if (maxConcurrentSpawns >= 0 && ACTIVE_GATES.size() >= maxConcurrentSpawns) {
                log(Level.INFO,
                        "[ELPortal] Spawn skipped: active gates=%d reached max_concurrent_spawns=%d",
                        ACTIVE_GATES.size(),
                        maxConcurrentSpawns);
                return;
            }

            String blockId = pickRandomPortalBlock();
            spawnGateViaWorldDispatch(world, target, blockId, false);
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin, Level.WARNING, ex, "[ELPortal] Natural gate tick failed");
        }
    }

    private static void scheduleNextNaturalSpawnTick() {
        long delayMinutes = resolveRandomSpawnIntervalMinutes();
        periodicTask = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                spawnNaturalGateTick();
            } finally {
                if (plugin != null) {
                    scheduleNextNaturalSpawnTick();
                }
            }
        }, delayMinutes, TimeUnit.MINUTES);
    }

    @Nonnull
    private static CompletableFuture<Boolean> spawnGateViaWorldDispatch(@Nonnull World world,
                                                                          @Nonnull PlayerRef playerRef,
                                                                          @Nonnull String blockId,
                                                                          boolean isTestSpawn) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        world.execute(() -> spawnInWorldNearPlayerRefOnThread(world, playerRef, blockId, isTestSpawn, future));
        return future;
    }

    @Nonnull
    private static List<PlayerRef> resolveEligiblePlayers(@Nonnull Universe universe) {
        List<PlayerRef> eligiblePlayers = new ArrayList<>();
        for (PlayerRef player : universe.getPlayers()) {
            if (player == null) {
                continue;
            }

            UUID worldUuid = player.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }

            World world = universe.getWorld(worldUuid);
            if (world != null && isPortalWorldAllowed(world)) {
                eligiblePlayers.add(player);
            }
        }
        return eligiblePlayers;
    }

    private static boolean isPortalWorldAllowed(@Nonnull World world) {
        if (filesManager == null) {
            return true;
        }

        String worldName = world.getName();
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String allowedName : filesManager.getDungeonPortalWorldWhitelist()) {
            if (worldName.equalsIgnoreCase(allowedName)) {
                return true;
            }
        }
        return false;
    }

    private static void spawnInWorldNearPlayerRefOnThread(@Nonnull World world,
                                                          @Nonnull PlayerRef playerRef,
                                                          @Nonnull String blockId,
                                                          boolean isTestSpawn,
                                                          @Nonnull CompletableFuture<Boolean> future) {
            List<Long> loadedChunkIndexes = resolveLoadedChunkIndexes(world);
            if (loadedChunkIndexes.isEmpty()) {
                log(Level.INFO,
                        "[ELPortal] Spawn skipped: no active loaded chunks in world %s",
                        world.getName());
                future.complete(false);
                return;
            }

            int attempts = Math.max(PLACEMENT_ATTEMPTS, loadedChunkIndexes.size());
            for (int attempt = 0; attempt < attempts; attempt++) {
                long chunkIndex = loadedChunkIndexes.get(randomInt(0, loadedChunkIndexes.size() - 1));
                WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
                if (chunk == null) {
                    continue;
                }

                int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
                int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
                int x = (chunkX << 5) + randomInt(0, 31);
                int z = (chunkZ << 5) + randomInt(0, 31);
                int y = resolveGroundPlacementY(chunk, x, z);
                if (y < 0) {
                    continue;
                }

                GateRank gateRank = resolveGateRank();
                LevelRange levelRange = resolveLevelRangeForWorld(world, playerRef, gateRank.tier);
                int normalLevelMin = levelRange.normalMin;
                int normalLevelMax = levelRange.normalMax;
                int bossLevel = levelRange.bossLevel;
                String rankedBlockId = blockId + gateRank.tier.blockIdSuffix();
                chunk.setBlock(x, y, z, rankedBlockId);
                trackActiveGate(world, rankedBlockId, x, y, z);
                String gateId = resolveGateIdAt(world, x, y, z);
                if (gateId == null || gateId.isBlank()) {
                    UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
                    if (worldUuid != null) {
                        gateId = buildStableGateId(worldUuid, x, y, z);
                    }
                }
                PortalLeveledInstanceRouter.setPendingLevelRange(rankedBlockId, gateId, normalLevelMin, normalLevelMax, bossLevel);
                if (isAnnounceOnSpawnEnabled()) {
                    announceGate(x, y, z, gateRank, normalLevelMin, normalLevelMax, bossLevel);
                }
                String expectedGroupId = gateId == null || gateId.isBlank() ? "<unknown>" : gateId;
                log(Level.INFO,
                        "[ELPortal] Gate spawned world=%s block=%s gateId=%s expectedGroupId=%s test=%s at %d %d %d rank=%s roll=%d normalRange=%d-%d bossLevel=%d",
                        world.getName(),
                        rankedBlockId,
                        expectedGroupId,
                        expectedGroupId,
                        isTestSpawn,
                        x,
                        y,
                        z,
                        gateRank.tier.letter(),
                        gateRank.roll,
                        normalLevelMin,
                        normalLevelMax,
                        bossLevel);
                // Keep a warning-level twin so range parity evidence is visible even when INFO is suppressed.
                log(Level.WARNING,
                        "[ELPortal] Gate spawned world=%s block=%s gateId=%s expectedGroupId=%s test=%s at %d %d %d rank=%s roll=%d normalRange=%d-%d bossLevel=%d",
                        world.getName(),
                        rankedBlockId,
                        expectedGroupId,
                        expectedGroupId,
                        isTestSpawn,
                        x,
                        y,
                        z,
                        gateRank.tier.letter(),
                        gateRank.roll,
                        normalLevelMin,
                        normalLevelMax,
                        bossLevel);
                scheduleRemoval(world, rankedBlockId, x, y, z);
                future.complete(true);
                return;
            }

            log(Level.INFO,
                    "[ELPortal] No valid ground placement found for player=%s in world %s (loadedChunks=%d)",
                    playerRef.getUsername(),
                    world.getName(),
                    loadedChunkIndexes.size());
            future.complete(false);
    }

    @Nonnull
    private static List<Long> resolveLoadedChunkIndexes(@Nonnull World world) {
        List<Long> loaded = new ArrayList<>();
        for (Long chunkIndexObj : world.getChunkStore().getChunkIndexes()) {
            if (chunkIndexObj == null) {
                continue;
            }
            long chunkIndex = chunkIndexObj;
            if (world.getChunkIfLoaded(chunkIndex) != null) {
                loaded.add(chunkIndex);
            }
        }
        return loaded;
    }

    private static int resolveGroundPlacementY(@Nonnull WorldChunk chunk, int x, int z) {
        for (int y = WORLD_MAX_Y - 2; y >= WORLD_MIN_Y + 1; y--) {
            int supportBlock = chunk.getBlock(x, y, z);
            if (supportBlock == AIR_BLOCK_ID || isLikelyFoliageOrWood(supportBlock)) {
                continue;
            }

            int placeY = y + 1;
            if (placeY >= WORLD_MAX_Y) {
                continue;
            }

            // Keep gate close to terrain: place directly above the surface with two clear blocks.
            if (chunk.getBlock(x, placeY, z) != AIR_BLOCK_ID) {
                continue;
            }
            if (chunk.getBlock(x, placeY + 1, z) != AIR_BLOCK_ID) {
                continue;
            }
            return placeY;
        }
        return -1;
    }

    private static boolean isLikelyFoliageOrWood(int blockIntId) {
        BlockType blockType = BlockType.getAssetMap().getAsset(blockIntId);
        if (blockType == null || blockType.getId() == null) {
            return false;
        }

        String id = blockType.getId().toLowerCase(Locale.ROOT);
        return id.contains("leaf")
                || id.contains("log")
                || id.contains("wood")
                || id.contains("branch")
                || id.contains("vine")
                || id.contains("canopy")
                || id.contains("bamboo");
    }

    private static void refreshConfigSnapshot() {
        if (filesManager != null) {
            filesManager.refreshDungeonGateOptions();
        }
    }

    private static void scheduleRemoval(@Nonnull World world,
                                        @Nonnull String blockId,
                                        int x,
                                        int y,
                                        int z) {
        long gateLifetimeMinutes = resolveGateLifetimeMinutes();
        if (gateLifetimeMinutes < 0) {
            return;
        }
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            world.execute(() -> {
                attemptGateRemoval(world, blockId, x, y, z, true, "timer-expiry");
            });
        }, gateLifetimeMinutes, TimeUnit.MINUTES);
    }

    private static void attemptGateRemoval(@Nonnull World world,
                                           @Nonnull String blockId,
                                           int x,
                                           int y,
                                           int z,
                                           boolean enqueueIfUnavailable,
                                           @Nonnull String reason) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) {
            if (enqueueIfUnavailable) {
                boolean queued = queuePendingRemoval(world, blockId, x, y, z);
                triggerPendingRemovalChunkLoad(world, chunkIndex);
                if (queued) {
                    log(Level.INFO,
                            "[ELPortal] Gate expiry queued (chunk unavailable) world=%s block=%s at %d %d %d reason=%s",
                            world.getName(),
                            blockId,
                            x,
                            y,
                            z,
                            reason);
                }
            }
            return;
        }

        try {
            chunk.setBlock(x, y, z, AIR_BLOCK_ID);
            chunk.markNeedsSaving();
            String gateId = resolveGateIdAt(world, x, y, z);
            if (isAnnounceOnDespawnEnabled()) {
                announceGateDespawn(x, y, z, blockId);
            }
            // Cleanup the paired instance when gate expires
            if (gateId != null && !gateId.isBlank()) {
                PortalLeveledInstanceRouter.cleanupGateInstanceByIdentity(gateId, blockId);
            } else {
                PortalLeveledInstanceRouter.cleanupGateInstance(world, x, y, z, blockId);
            }
            untrackActiveGate(world, x, y, z);
            log(Level.INFO,
                    "[ELPortal] Gate expired and removed world=%s block=%s at %d %d %d reason=%s",
                    world.getName(),
                    blockId,
                    x,
                    y,
                    z,
                    reason);
        } catch (Exception ex) {
            if (enqueueIfUnavailable) {
                queuePendingRemoval(world, blockId, x, y, z);
            }
            log(Level.WARNING,
                    "[ELPortal] Gate expiry removal failed world=%s block=%s at %d %d %d reason=%s error=%s",
                    world.getName(),
                    blockId,
                    x,
                    y,
                    z,
                    reason,
                    ex.getMessage());
        }
    }

    private static boolean queuePendingRemoval(@Nonnull World world,
                                               @Nonnull String blockId,
                                               int x,
                                               int y,
                                               int z) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return false;
        }

        Set<GateRemovalRequest> pending = PENDING_GATE_REMOVALS.computeIfAbsent(
                worldUuid,
                ignored -> ConcurrentHashMap.newKeySet());
        return pending.add(new GateRemovalRequest(blockId, x, y, z));
    }

    private static void trackActiveGate(@Nonnull World world,
                                        @Nonnull String blockId,
                                        int x,
                                        int y,
                                        int z) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return;
        }
        String gateId = buildStableGateId(worldUuid, x, y, z);
        ACTIVE_GATES.add(new ActiveGate(gateId, worldUuid, blockId, x, y, z));
        PortalLeveledInstanceRouter.registerGateExpectedInstance(gateId, blockId);
    }

    @Nonnull
    private static String buildStableGateId(@Nonnull UUID worldUuid, int x, int y, int z) {
        return "el_gate:" + worldUuid + ":" + x + ":" + y + ":" + z;
    }

    @Nonnull
    public static GateAnchor resolveTrackedGateAnchor(@Nonnull World world,
                                                      @Nonnull String blockId,
                                                      int x,
                                                      int y,
                                                      int z) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null || ACTIVE_GATES.isEmpty()) {
            return new GateAnchor(x, y, z, null);
        }

        String blockBase = stripRankSuffix(blockId);
        ActiveGate best = null;
        int bestDistance = Integer.MAX_VALUE;

        for (ActiveGate gate : ACTIVE_GATES) {
            if (!gate.worldUuid().equals(worldUuid)) {
                continue;
            }

            String gateBase = stripRankSuffix(gate.blockId());
            if (!gate.blockId().equals(blockId) && !gateBase.equals(blockBase)) {
                continue;
            }

            int dx = gate.x() - x;
            int dy = gate.y() - y;
            int dz = gate.z() - z;
            int distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = gate;
            }
        }

        if (best == null) {
            return new GateAnchor(x, y, z, null);
        }
        return new GateAnchor(best.x(), best.y(), best.z(), best.gateId());
    }

    @Nullable
    public static String resolveGateIdAt(@Nonnull World world, int x, int y, int z) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return null;
        }

        for (ActiveGate gate : ACTIVE_GATES) {
            if (!gate.worldUuid().equals(worldUuid)) {
                continue;
            }
            if (gate.x() == x && gate.y() == y && gate.z() == z) {
                return gate.gateId();
            }
        }
        return null;
    }

    @Nonnull
    private static String stripRankSuffix(@Nonnull String blockId) {
        int rankIndex = blockId.indexOf("_Rank");
        if (rankIndex <= 0) {
            return blockId;
        }
        return blockId.substring(0, rankIndex);
    }

    private static void untrackActiveGate(@Nonnull World world, int x, int y, int z) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return;
        }
        ACTIVE_GATES.removeIf(gate -> gate.worldUuid().equals(worldUuid)
                && gate.x() == x
                && gate.y() == y
                && gate.z() == z);
    }

    /**
     * Admin-force-remove: kicks all players from the associated instance, cleans up
     * router maps, clears the disk entry, and untracks the gate.
     * Safe to call from the world thread (e.g. inside a command executor).
     *
     * @param world   the world the gate block lives in
     * @param x       gate anchor X
     * @param y       gate anchor Y
     * @param z       gate anchor Z
     * @param blockId the block type ID of the gate anchor block
     */
    public static void forceRemoveGateAt(@Nonnull World world,
                                         int x, int y, int z,
                                         @Nonnull String blockId) {
        String gateId = resolveGateIdAt(world, x, y, z);
        if (gateId != null && !gateId.isBlank()) {
            // Kick any players inside the instance before tearing it down.
            String instanceName = PortalLeveledInstanceRouter.resolveInstanceNameForGate(gateId);
            if (instanceName != null && !instanceName.isBlank()) {
                PortalLeveledInstanceRouter.kickPlayersFromGateInstance(instanceName);
            }
            // cleanupGateInstanceByIdentity removes router maps, calls safeRemoveInstance,
            // and also removes the disk entry (via GateInstancePersistenceManager).
            PortalLeveledInstanceRouter.cleanupGateInstanceByIdentity(gateId, blockId);
        } else {
            // No tracked gate ID — fall back to coordinate-based cleanup.
            PortalLeveledInstanceRouter.cleanupGateInstance(world, x, y, z, blockId);
        }
        untrackActiveGate(world, x, y, z);
        log(Level.INFO,
                "[ELPortal] Admin force-removed gate world=%s block=%s at %d %d %d gateId=%s",
                world.getName(),
                blockId,
                x, y, z,
                gateId != null ? gateId : "<untracked>");
    }

    /**
     * Restores an active gate from persistence so that {@link #resolveGateIdAt} returns the
     * stable gate ID after a server restart.  Called by
     * {@code PortalLeveledInstanceRouter.restoreSavedGateInstances()}.
     *
     * <p>Gate key formats supported:
     * <ul>
     *   <li>stable: {@code el_gate:<uuid>:<x>:<y>:<z>}
     *   <li>legacy: {@code <uuid>:<x>:<y>:<z>}
     * </ul>
     */
    public static void restoreActiveGate(
            @Nonnull GateInstancePersistenceManager.StoredGateInstance stored,
            @Nonnull Universe universe) {
        String gateKey = stored.gateKey;
        String coordPart;
        if (gateKey.startsWith("el_gate:")) {
            coordPart = gateKey.substring("el_gate:".length());
        } else {
            coordPart = gateKey;
        }

        // Expected: <uuid>:<x>:<y>:<z>
        String[] parts = coordPart.split(":", 4);
        if (parts.length < 4) {
            log(Level.WARNING,
                    "[ELPortal] Cannot parse gate key for restore: %s", gateKey);
            return;
        }

        try {
            UUID worldUuid = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            // Do not gate on universe.getWorld(worldUuid) here: this method is called during plugin
            // startup, before any world has been added to the universe, so that call always returns
            // null and would silently skip every restore.  We trust the saved gate data; if the gate
            // world was deleted the stale ACTIVE_GATES entry is harmless.

            // The canonical stable ID always uses the "el_gate:" prefix
            String stableGateId = gateKey.startsWith("el_gate:") ? gateKey : ("el_gate:" + gateKey);

            // Skip duplicates (position or ID match)
            for (ActiveGate existing : ACTIVE_GATES) {
                if (existing.gateId().equals(stableGateId)) {
                    return;
                }
                if (existing.worldUuid().equals(worldUuid)
                        && existing.x() == x
                        && existing.y() == y
                        && existing.z() == z) {
                    return;
                }
            }

            ACTIVE_GATES.add(new ActiveGate(stableGateId, worldUuid, stored.blockId, x, y, z));
            log(Level.INFO,
                    "[ELPortal] Restored active gate gateId=%s world=%s block=%s at %d %d %d",
                    stableGateId, worldUuid, stored.blockId, x, y, z);
        } catch (Exception ex) {
            log(Level.WARNING,
                    "[ELPortal] Failed to parse gate key for restore: %s error=%s",
                    gateKey, ex.getMessage());
        }
    }

    private static void processPendingRemovals() {
        Universe universe = Universe.get();
        if (universe == null || PENDING_GATE_REMOVALS.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, Set<GateRemovalRequest>> entry : PENDING_GATE_REMOVALS.entrySet()) {
            UUID worldUuid = entry.getKey();
            Set<GateRemovalRequest> pending = entry.getValue();
            if (pending == null || pending.isEmpty()) {
                continue;
            }

            World world = universe.getWorld(worldUuid);
            if (world == null) {
                continue;
            }

            world.execute(() -> {
                int processed = 0;
                List<GateRemovalRequest> done = new ArrayList<>();
                for (GateRemovalRequest request : new ArrayList<>(pending)) {
                    if (processed >= REMOVAL_RETRY_BATCH_PER_WORLD) {
                        break;
                    }

                    long chunkIndex = ChunkUtil.indexChunkFromBlock(request.x(), request.z());
                    WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
                    if (chunk == null) {
                        triggerPendingRemovalChunkLoad(world, chunkIndex);
                        continue;
                    }

                    attemptGateRemoval(world,
                            request.blockId(),
                            request.x(),
                            request.y(),
                            request.z(),
                            false,
                            "pending-retry");
                    done.add(request);
                    processed++;
                }

                if (!done.isEmpty()) {
                    pending.removeAll(done);
                }
                if (pending.isEmpty()) {
                    PENDING_GATE_REMOVALS.remove(worldUuid);
                }
            });
        }
    }

    private static void triggerPendingRemovalChunkLoad(@Nonnull World world, long chunkIndex) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return;
        }

        Set<Long> inFlightLoads = PENDING_REMOVAL_CHUNK_LOADS.computeIfAbsent(
                worldUuid,
                ignored -> ConcurrentHashMap.newKeySet());
        if (!inFlightLoads.add(chunkIndex)) {
            return;
        }

        world.getChunkAsync(chunkIndex).whenComplete((loadedChunk, throwable) -> {
            world.execute(() -> {
                try {
                    if (throwable != null) {
                        log(Level.FINE,
                                "[ELPortal] Pending removal chunk load failed world=%s chunk=%d error=%s",
                                world.getName(),
                                chunkIndex,
                                throwable.getMessage());
                        return;
                    }

                    if (loadedChunk == null) {
                        return;
                    }

                    Set<GateRemovalRequest> pending = PENDING_GATE_REMOVALS.get(worldUuid);
                    if (pending == null || pending.isEmpty()) {
                        return;
                    }

                    int processed = 0;
                    List<GateRemovalRequest> done = new ArrayList<>();
                    for (GateRemovalRequest request : new ArrayList<>(pending)) {
                        if (processed >= REMOVAL_RETRY_BATCH_PER_WORLD) {
                            break;
                        }

                        long requestChunk = ChunkUtil.indexChunkFromBlock(request.x(), request.z());
                        if (requestChunk != chunkIndex) {
                            continue;
                        }

                        attemptGateRemoval(world,
                                request.blockId(),
                                request.x(),
                                request.y(),
                                request.z(),
                                false,
                                "pending-async-load");
                        done.add(request);
                        processed++;
                    }

                    if (!done.isEmpty()) {
                        pending.removeAll(done);
                    }
                    if (pending.isEmpty()) {
                        PENDING_GATE_REMOVALS.remove(worldUuid);
                    }
                } finally {
                    Set<Long> activeLoads = PENDING_REMOVAL_CHUNK_LOADS.get(worldUuid);
                    if (activeLoads != null) {
                        activeLoads.remove(chunkIndex);
                        if (activeLoads.isEmpty()) {
                            PENDING_REMOVAL_CHUNK_LOADS.remove(worldUuid);
                        }
                    }
                }
            });
        });
    }

    @Nonnull
    private static LevelRange resolveLevelRangeForWorld(@Nonnull World world,
                                                         @Nonnull PlayerRef anchorPlayerRef,
                                                         @Nonnull GateRankTier rankTier) {
        LevelBand worldBand = resolveGlobalLevelBand(anchorPlayerRef);
        int normalRange = resolveNormalMobLevelRange();
        int bossBonus = resolveBossLevelBonus();
        int highestLevel = worldBand.maxLevel();

        int normalMin;
        int normalMax;

        if (rankTier == GateRankTier.S) {
            // S rank starts strictly above the highest level any player has ever reached.
            int sOffset = Math.max(1, resolveSOffset());
            normalMin = clampDynamicLevel(highestLevel + sOffset);
            normalMax = clampDynamicLevel(normalMin + normalRange);
        } else {
            int baseLevel = resolveTierAnchorLevel(DYNAMIC_MIN_LEVEL, highestLevel, rankTier);
            String anchorMode = filesManager != null ? filesManager.getDungeonRankAnchorMode() : "HIGHEST_MOB";
            if ("LOWEST_MOB".equals(anchorMode)) {
                // Anchor is the lowest normal mob — range extends upward.
                normalMin = clampDynamicLevel(baseLevel);
                normalMax = clampDynamicLevel(normalMin + normalRange);
            } else if ("BOSS".equals(anchorMode)) {
                // Anchor is the boss level — derive normal range below it.
                int bossAnchor = clampDynamicLevel(baseLevel);
                normalMax = clampDynamicLevel(bossAnchor - bossBonus);
                normalMin = clampDynamicLevel(normalMax - normalRange);
            } else {
                // HIGHEST_MOB (default) — anchor is the highest normal mob, range extends downward.
                normalMax = clampDynamicLevel(baseLevel);
                normalMin = clampDynamicLevel(normalMax - normalRange);
            }
        }

        if (normalMax < normalMin) {
            normalMax = normalMin;
        }

        int bossLevel = clampDynamicLevel(normalMax + bossBonus);

        return new LevelRange(normalMin, normalMax, bossLevel);
    }

    @Nonnull
    private static LevelBand resolveGlobalLevelBand(@Nonnull PlayerRef anchorPlayerRef) {
        boolean overallScope = isOverallLevelPlayerScope();

        if (overallScope) {
            // OVERALL: find the highest level across all known players (online + offline).
            EndlessLeveling plugin = EndlessLeveling.getInstance();
            if (plugin != null) {
                List<PlayerData> allPlayers = plugin.getPlayerDataManager().getAllPlayersSortedByLevel();
                if (!allPlayers.isEmpty()) {
                    int highest = allPlayers.get(0).getLevel();
                    if (highest > HIGHEST_SEEN_PLAYER_LEVEL) {
                        HIGHEST_SEEN_PLAYER_LEVEL = highest;
                    }
                }
            }
        } else {
            // ONLINE: update high-water mark from currently online players only.
            EndlessLevelingAPI api = EndlessLevelingAPI.get();
            Universe universe = Universe.get();
            if (universe != null && api != null) {
                for (PlayerRef player : universe.getPlayers()) {
                    if (player == null) {
                        continue;
                    }
                    UUID playerUuid = player.getUuid();
                    if (playerUuid == null) {
                        continue;
                    }
                    int level = api.getPlayerLevel(playerUuid);
                    if (level > HIGHEST_SEEN_PLAYER_LEVEL) {
                        HIGHEST_SEEN_PLAYER_LEVEL = level;
                    }
                }
            }
        }

        // Ensure the anchor (gate-spawning) player is reflected regardless of scope.
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api != null) {
            UUID anchorUuid = anchorPlayerRef.getUuid();
            if (anchorUuid != null) {
                int anchorLevel = api.getPlayerLevel(anchorUuid);
                if (anchorLevel > HIGHEST_SEEN_PLAYER_LEVEL) {
                    HIGHEST_SEEN_PLAYER_LEVEL = anchorLevel;
                }
            }
        }

        // Band always spans from level 1 to the highest level any player has ever reached.
        int high = HIGHEST_SEEN_PLAYER_LEVEL > 0
                ? clampDynamicLevel(HIGHEST_SEEN_PLAYER_LEVEL)
                : clampDynamicLevel(resolveFallbackLevel(anchorPlayerRef));
        return new LevelBand(DYNAMIC_MIN_LEVEL, high);
    }

    private static boolean isOverallLevelPlayerScope() {
        return filesManager != null && "OVERALL".equals(filesManager.getDungeonLevelPlayerScope());
    }

    private static int resolveTierAnchorLevel(int minLevel, int maxLevel, @Nonnull GateRankTier tier) {
        if (maxLevel <= minLevel) {
            return clampDynamicLevel(maxLevel);
        }

        // Each rank occupies a 20% slice of the [1, highest] band.
        // A random point within that slice is chosen on every spawn for variety.
        double[] range = switch (tier) {
            case E -> new double[]{0.00, 0.20};
            case D -> new double[]{0.20, 0.40};
            case C -> new double[]{0.40, 0.60};
            case B -> new double[]{0.60, 0.80};
            case A -> new double[]{0.80, 1.00};
            default -> new double[]{0.40, 0.60};
        };

        double ratio = range[0] + Math.random() * (range[1] - range[0]);
        int span = maxLevel - minLevel;
        int anchored = minLevel + (int) Math.round(span * ratio);
        return clampDynamicLevel(Math.max(minLevel, Math.min(anchored, maxLevel)));
    }

    private static int resolveFallbackLevel(@Nonnull PlayerRef playerRef) {
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        UUID playerUuid = playerRef.getUuid();
        if (api == null || playerUuid == null) {
            return DYNAMIC_MIN_LEVEL;
        }
        int level = api.getPlayerLevel(playerUuid);
        return level > 0 ? level : DYNAMIC_MIN_LEVEL;
    }

    private static int resolveRankAnchorLevel(int minLevel, int maxLevel, @Nonnull GateRankTier tier) {
        if (maxLevel <= minLevel) {
            return clampDynamicLevel(minLevel);
        }

        RankWeights weights = resolveRankWeights();
        WeightRange weightRange = resolveWeightRangeForTier(weights, tier);
        int total = Math.max(1, weights.total());
        int span = Math.max(1, maxLevel - minLevel);

        int bandMin = minLevel + (int) Math.round(span * (weightRange.startInclusive() / (double) total));
        int bandMax = minLevel + (int) Math.round(span * (weightRange.endExclusive() / (double) total));
        bandMin = clampDynamicLevel(Math.min(bandMin, maxLevel));
        bandMax = clampDynamicLevel(Math.min(Math.max(bandMax, bandMin), maxLevel));

        if (bandMax <= bandMin) {
            return bandMin;
        }
        return randomInt(bandMin, bandMax);
    }

    @Nonnull
    private static WeightRange resolveWeightRangeForTier(@Nonnull RankWeights weights, @Nonnull GateRankTier tier) {
        int start;
        int end;

        switch (tier) {
            case E -> {
                start = 0;
                end = weights.e();
            }
            case D -> {
                start = weights.e();
                end = start + weights.d();
            }
            case C -> {
                start = weights.e() + weights.d();
                end = start + weights.c();
            }
            case B -> {
                start = weights.e() + weights.d() + weights.c();
                end = start + weights.b();
            }
            case A -> {
                start = weights.e() + weights.d() + weights.c() + weights.b();
                end = start + weights.a();
            }
            case S -> {
                start = weights.e() + weights.d() + weights.c() + weights.b() + weights.a();
                end = start + weights.s();
            }
            default -> {
                start = 0;
                end = Math.max(1, weights.total());
            }
        }
        if (end <= start) {
            end = start + 1;
        }
        return new WeightRange(start, end);
    }

    private static int resolveSOffset() {
        int minOffset = resolveLevelOffsetMin();
        int maxOffset = resolveLevelOffsetMax();
        if (maxOffset <= minOffset) {
            return minOffset;
        }
        return randomInt(minOffset, maxOffset);
    }

    private static int resolveLevelOffsetMin() {
        if (filesManager == null) {
            return DEFAULT_DYNAMIC_LEVEL_OFFSET_MIN;
        }
        return Math.max(0, filesManager.getDungeonLevelOffsetMin());
    }

    private static int resolveLevelOffsetMax() {
        if (filesManager == null) {
            return DEFAULT_DYNAMIC_LEVEL_OFFSET_MAX;
        }
        return Math.max(resolveLevelOffsetMin(), filesManager.getDungeonLevelOffsetMax());
    }

    @Nonnull
    private static GateRank resolveGateRank() {
        RankWeights weights = resolveRankWeights();
        int roll = randomInt(1, weights.total());
        int threshold = weights.s();
        if (roll <= threshold) {
            return new GateRank(GateRankTier.S, roll);
        }

        threshold += weights.a();
        if (roll <= threshold) {
            return new GateRank(GateRankTier.A, roll);
        }

        threshold += weights.b();
        if (roll <= threshold) {
            return new GateRank(GateRankTier.B, roll);
        }

        threshold += weights.c();
        if (roll <= threshold) {
            return new GateRank(GateRankTier.C, roll);
        }

        threshold += weights.d();
        if (roll <= threshold) {
            return new GateRank(GateRankTier.D, roll);
        }

        return new GateRank(GateRankTier.E, roll);
    }

    @Nonnull
    private static RankWeights resolveRankWeights() {
        if (filesManager == null) {
            return RankWeights.defaults();
        }

        int s = Math.max(0, filesManager.getDungeonRankWeightS());
        int a = Math.max(0, filesManager.getDungeonRankWeightA());
        int b = Math.max(0, filesManager.getDungeonRankWeightB());
        int c = Math.max(0, filesManager.getDungeonRankWeightC());
        int d = Math.max(0, filesManager.getDungeonRankWeightD());
        int e = Math.max(0, filesManager.getDungeonRankWeightE());

        RankWeights weights = new RankWeights(s, a, b, c, d, e);
        return weights.total() > 0 ? weights : RankWeights.defaults();
    }

    private static int resolveNormalMobLevelRange() {
        if (filesManager == null) {
            return DEFAULT_NORMAL_MOB_LEVEL_RANGE;
        }
        return Math.max(0, filesManager.getDungeonNormalMobLevelRange());
    }

    private static int resolveBossLevelBonus() {
        if (filesManager == null) {
            return DEFAULT_BOSS_LEVEL_BONUS;
        }
        return Math.max(0, filesManager.getDungeonBossLevelBonus());
    }

    private static long resolveSpawnIntervalMinutesMin() {
        if (filesManager == null) {
            return DEFAULT_SPAWN_INTERVAL_MINUTES;
        }
        return Math.max(1L, filesManager.getDungeonSpawnIntervalMinutesMin());
    }

    private static long resolveSpawnIntervalMinutesMax() {
        if (filesManager == null) {
            return DEFAULT_SPAWN_INTERVAL_MINUTES;
        }
        return Math.max(1L, filesManager.getDungeonSpawnIntervalMinutesMax());
    }

    private static long resolveRandomSpawnIntervalMinutes() {
        long min = resolveSpawnIntervalMinutesMin();
        long max = Math.max(min, resolveSpawnIntervalMinutesMax());
        if (min == max) {
            return min;
        }
        return randomInt((int) min, (int) max);
    }

    private static long resolveGateLifetimeMinutes() {
        if (filesManager == null) {
            return DEFAULT_GATE_LIFETIME_MINUTES;
        }
        return filesManager.getDungeonDurationMinutes();
    }

    private static int resolveMaxConcurrentSpawns() {
        if (filesManager == null) {
            return -1;
        }
        int value = filesManager.getDungeonMaxConcurrentSpawns();
        if (value < 0) {
            return -1;
        }
        return Math.max(1, value);
    }

    @Nonnull
    private static GateRankTier resolveGateRankTierFromBlockId(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return GateRankTier.E;
        }

        for (GateRankTier tier : GateRankTier.values()) {
            String suffix = tier.blockIdSuffix();
            if (!suffix.isEmpty() && blockId.endsWith(suffix)) {
                return tier;
            }
        }

        return GateRankTier.E;
    }

    private static boolean isAnnounceOnSpawnEnabled() {
        return filesManager == null || filesManager.isDungeonAnnounceOnSpawn();
    }

    private static boolean isAnnounceOnDespawnEnabled() {
        return filesManager == null || filesManager.isDungeonAnnounceOnDespawn();
    }

    private static int clampDynamicLevel(int value) {
        return Math.max(DYNAMIC_MIN_LEVEL, Math.min(DYNAMIC_MAX_LEVEL, value));
    }

    private static final class LevelRange {
        private final int normalMin;
        private final int normalMax;
        private final int bossLevel;

        private LevelRange(int normalMin, int normalMax, int bossLevel) {
            this.normalMin = normalMin;
            this.normalMax = normalMax;
            this.bossLevel = bossLevel;
        }
    }

    private static final class GateRank {
        private final GateRankTier tier;
        private final int roll;

        private GateRank(@Nonnull GateRankTier tier, int roll) {
            this.tier = tier;
            this.roll = roll;
        }
    }

    private static void announceGate(int x,
                                     int y,
                                     int z,
                                     @Nonnull GateRank gateRank,
                                     int normalLevelMin,
                                     int normalLevelMax,
                                     int bossLevel) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        Message message = Message.join(
            Message.raw(PREFIX).color(PortalGateColor.PREFIX.hex()),
            Message.raw(String.format("%s RANK GATE SPAWNED!", gateRank.tier.letter())).color(gateRank.tier.color().hex()),
            Message.raw("\n"),
            Message.raw(PREFIX).color(PortalGateColor.PREFIX.hex()),
            Message.raw(String.format("Position: (%d, %d, %d)", x, y, z)).color(PortalGateColor.POSITION.hex()),
            Message.raw("\n"),
            Message.raw(PREFIX).color(PortalGateColor.PREFIX.hex()),
            Message.raw(String.format("Normal Level Range: %d-%d", normalLevelMin, normalLevelMax)).color(PortalGateColor.LEVEL.hex()),
            Message.raw("\n"),
            Message.raw(PREFIX).color(PortalGateColor.PREFIX.hex()),
            Message.raw(String.format("Boss Level: %d", bossLevel)).color(PortalGateColor.LEVEL.hex())
        );
        universe.sendMessage(message);
    }

    private static void announceGateDespawn(int x, int y, int z, @Nonnull String blockId) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        GateRankTier gateRankTier = resolveGateRankTierFromBlockId(blockId);

        Message message = Message.join(
                Message.raw(PREFIX).color(PortalGateColor.PREFIX.hex()),
                Message.raw(String.format("%s RANK CLOSED", gateRankTier.letter())).color(gateRankTier.color().hex()),
                Message.raw("\n"),
                Message.raw(PREFIX).color(PortalGateColor.PREFIX.hex()),
                Message.raw(String.format("Position: (%d, %d, %d)", x, y, z)).color(PortalGateColor.POSITION.hex())
        );
        universe.sendMessage(message);
    }

    @Nonnull
    private static String pickRandomPortalBlock() {
        return PORTAL_BLOCK_IDS.get((int) (Math.random() * PORTAL_BLOCK_IDS.size()));
    }

    private static int randomInt(int minInclusive, int maxInclusive) {
        return minInclusive + (int) (Math.random() * (maxInclusive - minInclusive + 1));
    }

    private static void log(@Nonnull Level level, @Nonnull String format, Object... args) {
        AddonLoggingManager.log(plugin, level, String.format(Locale.ROOT, format, args));
    }

    private record GateRemovalRequest(@Nonnull String blockId, int x, int y, int z) {
    }

    private record ActiveGate(@Nonnull String gateId,
                              @Nonnull UUID worldUuid,
                              @Nonnull String blockId,
                              int x,
                              int y,
                              int z) {
    }

    public record GateAnchor(int x, int y, int z, @Nullable String gateId) {
    }

    private record LevelBand(int minLevel, int maxLevel) {
    }

    private record WeightRange(int startInclusive, int endExclusive) {
    }

    private record RankWeights(int s, int a, int b, int c, int d, int e) {
        private int total() {
            return s + a + b + c + d + e;
        }

        @Nonnull
        private static RankWeights defaults() {
            return new RankWeights(
                    DEFAULT_WEIGHT_S,
                    DEFAULT_WEIGHT_A,
                    DEFAULT_WEIGHT_B,
                    DEFAULT_WEIGHT_C,
                    DEFAULT_WEIGHT_D,
                    DEFAULT_WEIGHT_E);
        }
    }
}