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
    private static final int DEFAULT_DYNAMIC_LEVEL_OFFSET = 50;
    private static final int DEFAULT_UPPER_TOP_PERCENT = 25;

    private static JavaPlugin plugin;
    private static AddonFilesManager filesManager;
    private static ScheduledFuture<?> periodicTask;
    private static final Map<UUID, GateRank> LAST_SPAWN_RANK_BY_PLAYER = new ConcurrentHashMap<>();

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

                chunk.setBlock(x, y, z, blockId);
                LevelRange levelRange = resolveLevelRangeForWorld(world, playerRef);
                int levelMin = levelRange.min;
                int levelMax = levelRange.max;
                int highestPlayerLevel = resolveHighestPlayerLevelForWorld(playerRef);
                GateRank gateRank = resolveGateRank(levelMin, levelMax, highestPlayerLevel);
                UUID anchorPlayerUuid = playerRef.getUuid();
                if (anchorPlayerUuid != null) {
                    LAST_SPAWN_RANK_BY_PLAYER.put(anchorPlayerUuid, gateRank);
                }
                PortalLeveledInstanceRouter.setPendingLevelRange(blockId, levelMin, levelMax);
                if (isAnnounceOnSpawnEnabled()) {
                    announceGate(world, x, y, z, gateRank, levelMin, levelMax);
                }
                if (plugin != null) {
                    plugin.getLogger().at(Level.INFO).log(
                            "[ELPortal] Gate spawned world=%s block=%s test=%s at %d %d %d rank=%s ratio=%.2f levelRange=%d-%d",
                            world.getName(),
                            blockId,
                            isTestSpawn,
                            x,
                            y,
                            z,
                            gateRank.tier.letter(),
                            gateRank.ratio,
                            levelMin,
                            levelMax
                    );
                }
                scheduleRemoval(world, blockId, x, y, z);
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
                WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    if (plugin != null) {
                        plugin.getLogger().at(Level.INFO).log(
                                "[ELPortal] Gate expiry skipped (chunk unloaded) world=%s block=%s at %d %d %d",
                                world.getName(),
                                blockId,
                                x,
                                y,
                                z
                        );
                    }
                    return;
                }

                chunk.setBlock(x, y, z, "air");
                if (plugin != null) {
                    plugin.getLogger().at(Level.INFO).log(
                            "[ELPortal] Gate expired and removed world=%s block=%s at %d %d %d",
                            world.getName(),
                            blockId,
                            x,
                            y,
                            z
                    );
                }
            });
        }, gateLifetimeMinutes, TimeUnit.MINUTES);
    }

    @Nonnull
    private static LevelRange resolveLevelRangeForWorld(@Nonnull World world, @Nonnull PlayerRef anchorPlayerRef) {
        int baseLevel = resolveReferenceLevelForWorld(world, anchorPlayerRef);
        int offset = resolveLevelOffset();
        int levelMin = clampDynamicLevel(baseLevel - offset);
        int levelMax = clampDynamicLevel(baseLevel + offset);
        if (levelMax < levelMin) {
            levelMax = levelMin;
        }
        return new LevelRange(levelMin, levelMax);
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

    private static boolean isAnnounceOnSpawnEnabled() {
        return filesManager == null || filesManager.isDungeonAnnounceOnSpawn();
    }

    private static int clampDynamicLevel(int value) {
        return Math.max(DYNAMIC_MIN_LEVEL, Math.min(DYNAMIC_MAX_LEVEL, value));
    }

    private static final class LevelRange {
        private final int min;
        private final int max;

        private LevelRange(int min, int max) {
            this.min = min;
            this.max = max;
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

    @Nullable
    public static String consumeLastSpawnRankLine(@Nonnull UUID playerUuid) {
        GateRank rank = LAST_SPAWN_RANK_BY_PLAYER.remove(playerUuid);
        if (rank == null) {
            return null;
        }
        return String.format("Gate Rank: %s (ratio %.2f)", rank.tier.letter(), rank.ratio);
    }

    private static void announceGate(@Nonnull World world,
                                     int x,
                                     int y,
                                     int z,
                                     @Nonnull GateRank gateRank,
                                     int levelMin,
                                     int levelMax) {
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
            Message.raw(String.format("Level Range: %d-%d", levelMin, levelMax)).color(PortalGateColor.LEVEL.hex())
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
}