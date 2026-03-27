package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
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
    private static final int MAX_OFFSET_BLOCKS = 96;
    private static final int PLACEMENT_ATTEMPTS = 16;
    private static final String PREFIX = "[EndlessLeveling] ";
    private static final int DYNAMIC_MIN_LEVEL = 1;
    private static final int DYNAMIC_MAX_LEVEL = 500;
    private static final int DEFAULT_DYNAMIC_LEVEL_OFFSET = 30;
    private static final int DEFAULT_NORMAL_MOB_LEVEL_RANGE = 20;
    private static final int DEFAULT_BOSS_LEVEL_BONUS = 10;
    private static final int DEFAULT_UPPER_TOP_PERCENT = 25;
    private static final int AIR_BLOCK_ID = 0;
    private static final long REMOVAL_RETRY_INTERVAL_SECONDS = 10L;
    private static final int REMOVAL_RETRY_BATCH_PER_WORLD = 32;

    private static JavaPlugin plugin;
    private static AddonFilesManager filesManager;
    private static ScheduledFuture<?> periodicTask;
    private static ScheduledFuture<?> pendingRemovalTask;
    private static final Map<UUID, Set<GateRemovalRequest>> PENDING_GATE_REMOVALS = new ConcurrentHashMap<>();
    private static final Set<ActiveGate> ACTIVE_GATES = ConcurrentHashMap.newKeySet();

    private NaturalPortalGateManager() {
    }

        public static void initialize(@Nonnull JavaPlugin owner, @Nullable AddonFilesManager manager) {
        plugin = owner;
        filesManager = manager;
        long spawnIntervalMinutes = Math.max(1L, resolveSpawnIntervalMinutes());
        long gateLifetimeMinutes = resolveGateLifetimeMinutes();
        periodicTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
                NaturalPortalGateManager::spawnNaturalGateTick,
            spawnIntervalMinutes,
            spawnIntervalMinutes,
                TimeUnit.MINUTES
        );
        pendingRemovalTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
            NaturalPortalGateManager::processPendingRemovals,
            REMOVAL_RETRY_INTERVAL_SECONDS,
            REMOVAL_RETRY_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
        plugin.getLogger().at(Level.INFO).log(
                "[ELPortal] Natural gate spawner enabled: every %d minute(s), lifetime %d minute(s)",
            spawnIntervalMinutes,
            gateLifetimeMinutes
        );
    }

    public static void shutdown() {
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
        if (pendingRemovalTask != null) {
            pendingRemovalTask.cancel(false);
            pendingRemovalTask = null;
        }
        PENDING_GATE_REMOVALS.clear();
        ACTIVE_GATES.clear();
    }

    @Nonnull
    public static CompletableFuture<Boolean> spawnRandomGateNearPlayer(@Nonnull Player player, boolean isTestSpawn) {
        refreshConfigSnapshot();
        World world = player.getWorld();
        if (world == null) {
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

            List<PlayerRef> players = universe.getPlayers();
            if (players.isEmpty()) {
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
            if (plugin != null) {
                plugin.getLogger().at(Level.WARNING).withCause(ex).log("[ELPortal] Natural gate tick failed");
            }
        }
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

    private static void spawnInWorldNearPlayerRefOnThread(@Nonnull World world,
                                                          @Nonnull PlayerRef playerRef,
                                                          @Nonnull String blockId,
                                                          boolean isTestSpawn,
                                                          @Nonnull CompletableFuture<Boolean> future) {
            Vector3d base = playerRef.getTransform().getPosition();
            int baseX = MathUtil.floor(base.x);
            int baseY = MathUtil.floor(base.y);
            int baseZ = MathUtil.floor(base.z);

            for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
                int offsetX = randomInt(-MAX_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
                int offsetZ = randomInt(-MAX_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
                int x = baseX + offsetX;
                int y = Math.max(baseY, 1);
                int z = baseZ + offsetZ;

                WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    // chunk is not currently loaded by any player — skip
                    continue;
                }

                // Do not overwrite world blocks when spawning portal gates.
                if (chunk.getBlock(x, y, z) != 0) {
                    continue;
                }

                LevelRange levelRange = resolveLevelRangeForWorld(world, playerRef);
                int normalLevelMin = levelRange.normalMin;
                int normalLevelMax = levelRange.normalMax;
                int bossLevel = levelRange.bossLevel;
                int highestPlayerLevel = resolveHighestPlayerLevelForWorld(playerRef);
                GateRank gateRank = resolveGateRank(normalLevelMin, normalLevelMax, highestPlayerLevel);
                String rankedBlockId = blockId + gateRank.tier.blockIdSuffix();
                chunk.setBlock(x, y, z, rankedBlockId);
                trackActiveGate(world, rankedBlockId, x, y, z);
                PortalLeveledInstanceRouter.setPendingLevelRange(rankedBlockId, normalLevelMin, normalLevelMax, bossLevel);
                if (isAnnounceOnSpawnEnabled()) {
                        announceGate(x, y, z, gateRank, normalLevelMin, normalLevelMax, bossLevel);
                }
                if (plugin != null) {
                    plugin.getLogger().at(Level.INFO).log(
                            "[ELPortal] Gate spawned world=%s block=%s test=%s at %d %d %d rank=%s ratio=%.2f normalRange=%d-%d bossLevel=%d",
                            world.getName(),
                            rankedBlockId,
                            isTestSpawn,
                            x,
                            y,
                            z,
                            gateRank.tier.letter(),
                            gateRank.ratio,
                            normalLevelMin,
                            normalLevelMax,
                            bossLevel
                    );
                }
                scheduleRemoval(world, rankedBlockId, x, y, z);
                future.complete(true);
                return;
            }

            if (plugin != null) {
                plugin.getLogger().at(Level.INFO).log(
                        "[ELPortal] No loaded chunk found near %s in world %s for gate placement",
                        playerRef.getUsername(),
                        world.getName()
                );
            }
            future.complete(false);
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
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            if (enqueueIfUnavailable && queuePendingRemoval(world, blockId, x, y, z)) {
                log(Level.INFO,
                        "[ELPortal] Gate expiry queued (chunk unavailable) world=%s block=%s at %d %d %d reason=%s",
                        world.getName(),
                        blockId,
                        x,
                        y,
                        z,
                        reason);
            }
            return;
        }

        try {
            chunk.setBlock(x, y, z, AIR_BLOCK_ID);
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
        ACTIVE_GATES.add(new ActiveGate(worldUuid, blockId, x, y, z));
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

                    WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(request.x(), request.z()));
                    if (chunk == null) {
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

    @Nonnull
    private static LevelRange resolveLevelRangeForWorld(@Nonnull World world, @Nonnull PlayerRef anchorPlayerRef) {
        int baseLevel = resolveReferenceLevelForWorld(world, anchorPlayerRef);
        int offset = resolveLevelOffset();
        int normalRange = resolveNormalMobLevelRange();
        int bossBonus = resolveBossLevelBonus();

        boolean anchorAtMin = "LOWER".equals(resolveLevelReferenceScope());
        int normalMin;
        int normalMax;

        if (anchorAtMin) {
            normalMin = clampDynamicLevel(baseLevel - offset);
            normalMax = clampDynamicLevel(normalMin + normalRange);
        } else {
            normalMax = clampDynamicLevel(baseLevel + offset);
            normalMin = clampDynamicLevel(normalMax - normalRange);
        }

        if (normalMax < normalMin) {
            normalMax = normalMin;
        }

        int bossLevel = clampDynamicLevel(normalMax + bossBonus);

        return new LevelRange(normalMin, normalMax, bossLevel);
    }

    private static int resolveReferenceLevelForWorld(@Nonnull World world, @Nonnull PlayerRef anchorPlayerRef) {
        UUID worldUuid = anchorPlayerRef.getWorldUuid();
        Universe universe = Universe.get();
        if (universe == null || worldUuid == null) {
            return resolveFallbackLevel(anchorPlayerRef);
        }

        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        List<Integer> worldLevels = new ArrayList<>();
        for (PlayerRef player : universe.getPlayers()) {
            if (player == null || !worldUuid.equals(player.getWorldUuid())) {
                continue;
            }

            UUID playerUuid = player.getUuid();
            if (playerUuid == null) {
                continue;
            }

            int level = api != null ? api.getPlayerLevel(playerUuid) : 0;
            if (level > 0) {
                worldLevels.add(level);
            }
        }

        if (worldLevels.isEmpty()) {
            return resolveFallbackLevel(anchorPlayerRef);
        }

        List<Integer> scopedLevels = applyLevelReferenceScope(worldLevels);
        String mode = resolveLevelReferenceMode();
        return switch (mode) {
            case "HIGHEST" -> scopedLevels.stream().max(Integer::compareTo).orElse(DYNAMIC_MIN_LEVEL);
            case "AVERAGE" -> {
                long sum = 0L;
                for (int level : scopedLevels) {
                    sum += level;
                }
                yield (int) Math.round(sum / (double) scopedLevels.size());
            }
            case "MEDIAN" -> {
                Collections.sort(scopedLevels);
                int size = scopedLevels.size();
                int mid = size / 2;
                if ((size % 2) == 1) {
                    yield scopedLevels.get(mid);
                }
                int lower = scopedLevels.get(mid - 1);
                int upper = scopedLevels.get(mid);
                yield (int) Math.round((lower + upper) / 2.0D);
            }
            default -> resolveFallbackLevel(anchorPlayerRef);
        };
    }

    @Nonnull
    private static List<Integer> applyLevelReferenceScope(@Nonnull List<Integer> sourceLevels) {
        String scope = resolveLevelReferenceScope();
        if ("ALL".equals(scope)) {
            return new ArrayList<>(sourceLevels);
        }

        List<Integer> sorted = new ArrayList<>(sourceLevels);
        Collections.sort(sorted);
        int scopePercent = resolveScopePercent();
        int bucketCount = Math.max(1, (int) Math.ceil(sorted.size() * (scopePercent / 100.0D)));

        if ("LOWER".equals(scope)) {
            int endIndex = Math.min(sorted.size(), bucketCount);
            return new ArrayList<>(sorted.subList(0, endIndex));
        }

        int startIndex = Math.max(0, sorted.size() - bucketCount);
        return new ArrayList<>(sorted.subList(startIndex, sorted.size()));
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

    private static int resolveHighestPlayerLevelForWorld(@Nonnull PlayerRef anchorPlayerRef) {
        UUID worldUuid = anchorPlayerRef.getWorldUuid();
        Universe universe = Universe.get();
        if (universe == null || worldUuid == null) {
            return resolveFallbackLevel(anchorPlayerRef);
        }

        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        int highest = 0;
        for (PlayerRef player : universe.getPlayers()) {
            if (player == null || !worldUuid.equals(player.getWorldUuid())) {
                continue;
            }

            UUID playerUuid = player.getUuid();
            if (playerUuid == null) {
                continue;
            }

            int level = api != null ? api.getPlayerLevel(playerUuid) : 0;
            if (level > highest) {
                highest = level;
            }
        }

        if (highest <= 0) {
            return resolveFallbackLevel(anchorPlayerRef);
        }
        return highest;
    }

    @Nonnull
    private static GateRank resolveGateRank(int levelMin, int levelMax, int highestPlayerLevel) {
        int dungeonLevel = Math.max(levelMin, levelMax);
        int safeHighestLevel = Math.max(1, highestPlayerLevel);
        double ratio = dungeonLevel / (double) safeHighestLevel;
        return new GateRank(GateRankTier.fromRatio(ratio), ratio);
    }

    @Nonnull
    private static String resolveLevelReferenceMode() {
        if (filesManager == null) {
            return "AVERAGE";
        }
        String mode = filesManager.getDungeonLevelReferenceMode();
        if (mode == null || mode.isBlank()) {
            return "AVERAGE";
        }
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if ("HIGHEST".equals(normalized)
                || "MEDIAN".equals(normalized)
                || "AVERAGE".equals(normalized)) {
            return normalized;
        }
        return "AVERAGE";
    }

    @Nonnull
    private static String resolveLevelReferenceScope() {
        if (filesManager == null) {
            return "UPPER";
        }
        String scope = filesManager.getDungeonLevelReferenceScope();
        if (scope == null || scope.isBlank()) {
            return "UPPER";
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(normalized) || "UPPER".equals(normalized) || "LOWER".equals(normalized)) {
            return normalized;
        }
        return "UPPER";
    }

    private static int resolveLevelOffset() {
        if (filesManager == null) {
            return DEFAULT_DYNAMIC_LEVEL_OFFSET;
        }
        return Math.max(0, filesManager.getDungeonLevelOffset());
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

    private static int resolveScopePercent() {
        if (filesManager == null) {
            return DEFAULT_UPPER_TOP_PERCENT;
        }
        int value = filesManager.getDungeonLevelReferenceScopePercent();
        return Math.max(1, Math.min(100, value));
    }

    private static long resolveSpawnIntervalMinutes() {
        if (filesManager == null) {
            return DEFAULT_SPAWN_INTERVAL_MINUTES;
        }
        return Math.max(1L, filesManager.getDungeonSpawnIntervalMinutes());
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

    private static boolean isAnnounceOnSpawnEnabled() {
        return filesManager == null || filesManager.isDungeonAnnounceOnSpawn();
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
        private final double ratio;

        private GateRank(@Nonnull GateRankTier tier, double ratio) {
            this.tier = tier;
            this.ratio = ratio;
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

    @Nonnull
    private static String pickRandomPortalBlock() {
        return PORTAL_BLOCK_IDS.get((int) (Math.random() * PORTAL_BLOCK_IDS.size()));
    }

    private static int randomInt(int minInclusive, int maxInclusive) {
        return minInclusive + (int) (Math.random() * (maxInclusive - minInclusive + 1));
    }

    private static void log(@Nonnull Level level, @Nonnull String format, Object... args) {
        if (plugin == null) {
            return;
        }
        plugin.getLogger().at(level).log(String.format(Locale.ROOT, format, args));
    }

    private record GateRemovalRequest(@Nonnull String blockId, int x, int y, int z) {
    }

    private record ActiveGate(@Nonnull UUID worldUuid, @Nonnull String blockId, int x, int y, int z) {
    }
}