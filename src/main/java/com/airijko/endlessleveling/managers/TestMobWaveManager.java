package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.enums.GateRankTier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.util.NPCPhysicsMath;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TestMobWaveManager {

    private static final int DEFAULT_WAVES = 3;
    private static final int DEFAULT_MOBS_PER_WAVE = 4;
    private static final int DEFAULT_INTERVAL_SECONDS = 8;
    private static final double DEFAULT_RADIUS = 10.0D;
    private static final int DYNAMIC_MIN_LEVEL = 1;
    private static final int DYNAMIC_MAX_LEVEL = 500;
    private static final int DEFAULT_NORMAL_MOB_LEVEL_RANGE = 20;
    private static final int DEFAULT_S_OFFSET = 5;
    private static final int DEFAULT_PER_WAVE_LEVEL_INCREMENT = 2;
    private static final String WAVE_OVERRIDE_ID_PREFIX = "elwave:";

    private static final Map<UUID, ActiveWaveSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private TestMobWaveManager() {
    }

    @Nonnull
    public static StartResult startForPlayer(@Nonnull PlayerRef playerRef, @Nonnull GateRankTier rankTier) {
        UUID playerUuid = playerRef.getUuid();

        ActiveWaveSession existing = ACTIVE_SESSIONS.get(playerUuid);
        if (existing != null && !existing.cancelled.get()) {
            return StartResult.failed("A wave test is already running. Use /gate wave stop first.");
        }

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return StartResult.failed("You must be in-world to start wave testing.");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        String worldName = world != null ? world.getName() : null;
        if (world == null || worldName == null || worldName.isBlank()) {
            return StartResult.failed("Could not resolve your current world.");
        }

        NPCPlugin npcPlugin = NPCPlugin.get();
        List<String> roleNames = new ArrayList<>(npcPlugin.getRoleTemplateNames(true));
        if (roleNames.isEmpty()) {
            return StartResult.failed("No spawnable NPC role templates were found.");
        }
        roleNames.sort(Comparator.comparing(s -> s.toLowerCase(Locale.ROOT)));
        String selectedRole = Objects.requireNonNull(roleNames.get(0));

        LevelRange baseLevelRange = resolveTierBaseLevelRange(playerRef, rankTier);
        String overrideId = WAVE_OVERRIDE_ID_PREFIX + playerUuid;

        ActiveWaveSession session = new ActiveWaveSession(
                playerUuid,
                playerRef,
                world,
                worldName,
                selectedRole,
                rankTier,
                DEFAULT_WAVES,
                DEFAULT_MOBS_PER_WAVE,
                DEFAULT_INTERVAL_SECONDS,
                DEFAULT_RADIUS,
                overrideId,
                baseLevelRange
        );

        ACTIVE_SESSIONS.put(playerUuid, session);
        spawnWave(session, 1);

        for (int wave = 2; wave <= session.totalWaves; wave++) {
            int currentWave = wave;
            ScheduledFuture<?> future = HytaleServer.SCHEDULED_EXECUTOR.schedule(
                    () -> spawnWave(session, currentWave),
                    (long) session.intervalSeconds * (wave - 1),
                    TimeUnit.SECONDS
            );
            session.scheduledFutures.add(future);
        }

        return StartResult.started(
                session.roleName,
                session.rankTier,
                session.totalWaves,
                session.mobsPerWave,
                session.intervalSeconds,
                session.baseLevelRange.min,
                session.baseLevelRange.max);
    }

    @Nonnull
    public static StopResult stopForPlayer(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();

        ActiveWaveSession session = ACTIVE_SESSIONS.remove(playerUuid);
        if (session == null) {
            return StopResult.notRunning();
        }

        session.cancelled.set(true);
        for (ScheduledFuture<?> future : session.scheduledFutures) {
            if (future != null) {
                future.cancel(false);
            }
        }
        session.scheduledFutures.clear();
        removeWaveLevelOverride(session);
        return StopResult.stopped(session.roleName);
    }

    @Nullable
    public static StatusResult getStatus(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();

        ActiveWaveSession session = ACTIVE_SESSIONS.get(playerUuid);
        if (session == null || session.cancelled.get()) {
            return null;
        }

        return new StatusResult(
                session.roleName,
                session.rankTier,
                session.totalWaves,
                session.mobsPerWave,
                session.intervalSeconds,
                session.baseLevelRange.min,
                session.baseLevelRange.max);
    }

    private static void spawnWave(@Nonnull ActiveWaveSession session, int waveNumber) {
        if (session.cancelled.get()) {
            return;
        }

        session.world.execute(() -> {
            if (session.cancelled.get()) {
                return;
            }

            Ref<EntityStore> ref = session.playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                cleanupSession(session.playerUuid);
                return;
            }

            Store<EntityStore> store = ref.getStore();
            TransformComponent transform = store.getComponent(ref, Objects.requireNonNull(TransformComponent.getComponentType()));
            if (transform == null) {
                cleanupSession(session.playerUuid);
                return;
            }

            LevelRange waveLevelRange = resolveWaveLevelRange(session, waveNumber);
            applyWaveLevelOverride(session, waveLevelRange);

            Message titlePrimary = Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                    "%s WAVE %d / %d",
                    session.rankTier.letter(),
                    waveNumber,
                    session.totalWaves))).color("#f8d66d");
            Message titleSecondary = Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                    "%d mobs | Lv %d-%d",
                    session.mobsPerWave,
                    waveLevelRange.min,
                    waveLevelRange.max))).color("#8fd3ff");
            EventTitleUtil.showEventTitleToPlayer(session.playerRef, titlePrimary, titleSecondary, true);

            Vector3d playerPos = transform.getPosition();
            NPCPlugin npcPlugin = NPCPlugin.get();

            int spawned = 0;
            for (int i = 0; i < session.mobsPerWave; i++) {
                double angle = Math.random() * Math.PI * 2.0D;
                double distance = (0.5D + Math.random() * 0.5D) * session.radius;
                double x = playerPos.x + Math.cos(angle) * distance;
                double z = playerPos.z + Math.sin(angle) * distance;
                double y = NPCPhysicsMath.heightOverGround(Objects.requireNonNull(session.world), x, z);
                if (y < 0.0D) {
                    y = playerPos.y;
                }

                Vector3d spawnPosition = new Vector3d(x, y, z);
                Vector3f spawnRotation = new Vector3f(0.0F, (float) (Math.random() * Math.PI * 2.0D), 0.0F);

                if (npcPlugin.spawnNPC(store, Objects.requireNonNull(session.roleName), null, spawnPosition, spawnRotation) != null) {
                    spawned++;
                }
            }

            session.playerRef.sendMessage(Message.raw("[WaveTest] --------------------------------").color("#4f5f78"));
            session.playerRef.sendMessage(
                    Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                            "[WaveTest] %s Rank | Wave %d/%d | Spawned %d/%d",
                            session.rankTier.letter(),
                            waveNumber,
                            session.totalWaves,
                            spawned,
                            session.mobsPerWave))).color("#8fd3ff")
            );
            session.playerRef.sendMessage(
                    Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                            "[WaveTest] Level Range: %d-%d | Role: %s | Radius: %.1f",
                            waveLevelRange.min,
                            waveLevelRange.max,
                            session.roleName,
                            session.radius))).color("#b4becf")
            );

            if (waveNumber >= session.totalWaves) {
                EventTitleUtil.showEventTitleToPlayer(
                        session.playerRef,
                        Message.raw("WAVE TEST COMPLETE").color("#6cff78"),
                        Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                                "%s rank | %d waves finished",
                                session.rankTier.letter(),
                                session.totalWaves))).color("#d5f7db"),
                        false
                );
                cleanupSession(session.playerUuid);
            }
        });
    }

    @Nonnull
    private static LevelRange resolveTierBaseLevelRange(@Nonnull PlayerRef playerRef, @Nonnull GateRankTier rankTier) {
        int playerLevel = DYNAMIC_MIN_LEVEL;
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid != null && api != null) {
            playerLevel = clampDynamicLevel(Math.max(DYNAMIC_MIN_LEVEL, api.getPlayerLevel(playerUuid)));
        }

        int normalMin;
        int normalMax;
        if (rankTier == GateRankTier.S) {
            normalMin = clampDynamicLevel(playerLevel + DEFAULT_S_OFFSET);
            normalMax = clampDynamicLevel(normalMin + DEFAULT_NORMAL_MOB_LEVEL_RANGE);
            return new LevelRange(normalMin, Math.max(normalMin, normalMax));
        }

        int baseLevel = resolveTierAnchorLevel(DYNAMIC_MIN_LEVEL, playerLevel, rankTier);
        normalMax = clampDynamicLevel(baseLevel);
        normalMin = clampDynamicLevel(normalMax - DEFAULT_NORMAL_MOB_LEVEL_RANGE);
        return new LevelRange(normalMin, Math.max(normalMin, normalMax));
    }

    private static int resolveTierAnchorLevel(int minLevel, int maxLevel, @Nonnull GateRankTier tier) {
        if (maxLevel <= minLevel) {
            return clampDynamicLevel(maxLevel);
        }

        double ratio = switch (tier) {
            case E -> 0.00D;
            case D -> 0.25D;
            case C -> 0.50D;
            case B -> 0.75D;
            case A -> 1.00D;
            default -> 0.50D;
        };

        int span = maxLevel - minLevel;
        int anchored = minLevel + (int) Math.round(span * ratio);
        return clampDynamicLevel(Math.max(minLevel, Math.min(anchored, maxLevel)));
    }

    @Nonnull
    private static LevelRange resolveWaveLevelRange(@Nonnull ActiveWaveSession session, int waveNumber) {
        int increment = Math.max(0, waveNumber - 1) * DEFAULT_PER_WAVE_LEVEL_INCREMENT;
        int min = clampDynamicLevel(session.baseLevelRange.min + increment);
        int max = clampDynamicLevel(session.baseLevelRange.max + increment);
        return new LevelRange(min, Math.max(min, max));
    }

    private static void applyWaveLevelOverride(@Nonnull ActiveWaveSession session, @Nonnull LevelRange range) {
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null) {
            return;
        }

        api.registerMobWorldGateLevelOverride(session.overrideId, session.worldName, range.min, range.max, 0);
    }

    private static void removeWaveLevelOverride(@Nonnull ActiveWaveSession session) {
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null) {
            return;
        }

        api.removeMobGateLevelOverride(session.overrideId);
        api.removeMobWorldFixedLevelOverride(session.overrideId);
    }

    private static int clampDynamicLevel(int level) {
        return Math.max(DYNAMIC_MIN_LEVEL, Math.min(DYNAMIC_MAX_LEVEL, level));
    }

    private static void cleanupSession(UUID playerUuid) {
        ActiveWaveSession removed = ACTIVE_SESSIONS.remove(playerUuid);
        if (removed == null) {
            return;
        }

        removed.cancelled.set(true);
        for (ScheduledFuture<?> future : removed.scheduledFutures) {
            if (future != null) {
                future.cancel(false);
            }
        }
        removed.scheduledFutures.clear();
        removeWaveLevelOverride(removed);
    }

    private static final class LevelRange {
        private final int min;
        private final int max;

        private LevelRange(int min, int max) {
            this.min = min;
            this.max = max;
        }
    }

    private static final class ActiveWaveSession {
        @Nonnull
        private final UUID playerUuid;
        @Nonnull
        private final PlayerRef playerRef;
        @Nonnull
        private final World world;
        @Nonnull
        private final String worldName;
        @Nonnull
        private final String roleName;
        @Nonnull
        private final GateRankTier rankTier;
        private final int totalWaves;
        private final int mobsPerWave;
        private final int intervalSeconds;
        private final double radius;
        @Nonnull
        private final String overrideId;
        @Nonnull
        private final LevelRange baseLevelRange;
        private final List<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        private ActiveWaveSession(@Nonnull UUID playerUuid,
                                  @Nonnull PlayerRef playerRef,
                                  @Nonnull World world,
                                  @Nonnull String worldName,
                                  @Nonnull String roleName,
                                  @Nonnull GateRankTier rankTier,
                                  int totalWaves,
                                  int mobsPerWave,
                                  int intervalSeconds,
                                  double radius,
                                  @Nonnull String overrideId,
                                  @Nonnull LevelRange baseLevelRange) {
            this.playerUuid = playerUuid;
            this.playerRef = playerRef;
            this.world = world;
            this.worldName = worldName;
            this.roleName = roleName;
            this.rankTier = rankTier;
            this.totalWaves = totalWaves;
            this.mobsPerWave = mobsPerWave;
            this.intervalSeconds = intervalSeconds;
            this.radius = radius;
            this.overrideId = overrideId;
            this.baseLevelRange = baseLevelRange;
        }
    }

    public static final class StartResult {
        public final boolean started;
        @Nonnull
        public final String message;
        @Nullable
        public final String roleName;
        @Nullable
        public final GateRankTier rankTier;
        public final int waves;
        public final int mobsPerWave;
        public final int intervalSeconds;
        public final int levelMin;
        public final int levelMax;

        private StartResult(boolean started,
                            @Nonnull String message,
                            @Nullable String roleName,
                            @Nullable GateRankTier rankTier,
                            int waves,
                            int mobsPerWave,
                            int intervalSeconds,
                            int levelMin,
                            int levelMax) {
            this.started = started;
            this.message = message;
            this.roleName = roleName;
            this.rankTier = rankTier;
            this.waves = waves;
            this.mobsPerWave = mobsPerWave;
            this.intervalSeconds = intervalSeconds;
            this.levelMin = levelMin;
            this.levelMax = levelMax;
        }

        @Nonnull
        private static StartResult started(@Nonnull String roleName,
                                           @Nonnull GateRankTier rankTier,
                                           int waves,
                                           int mobsPerWave,
                                           int intervalSeconds,
                                           int levelMin,
                                           int levelMax) {
            return new StartResult(true,
                    "Wave test started.",
                    roleName,
                    rankTier,
                    waves,
                    mobsPerWave,
                    intervalSeconds,
                    levelMin,
                    levelMax);
        }

        @Nonnull
        private static StartResult failed(@Nonnull String message) {
            return new StartResult(false, message, null, null, 0, 0, 0, 0, 0);
        }
    }

    public static final class StopResult {
        public final boolean stopped;
        @Nullable
        public final String roleName;

        private StopResult(boolean stopped, @Nullable String roleName) {
            this.stopped = stopped;
            this.roleName = roleName;
        }

        @Nonnull
        private static StopResult stopped(@Nonnull String roleName) {
            return new StopResult(true, roleName);
        }

        @Nonnull
        private static StopResult notRunning() {
            return new StopResult(false, null);
        }
    }

    public static final class StatusResult {
        @Nonnull
        public final String roleName;
        @Nonnull
        public final GateRankTier rankTier;
        public final int waves;
        public final int mobsPerWave;
        public final int intervalSeconds;
        public final int levelMin;
        public final int levelMax;

        private StatusResult(@Nonnull String roleName,
                             @Nonnull GateRankTier rankTier,
                             int waves,
                             int mobsPerWave,
                             int intervalSeconds,
                             int levelMin,
                             int levelMax) {
            this.roleName = roleName;
            this.rankTier = rankTier;
            this.waves = waves;
            this.mobsPerWave = mobsPerWave;
            this.intervalSeconds = intervalSeconds;
            this.levelMin = levelMin;
            this.levelMax = levelMax;
        }
    }
}
