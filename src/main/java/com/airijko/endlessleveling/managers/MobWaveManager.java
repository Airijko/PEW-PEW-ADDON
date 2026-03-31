package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.enums.GateRankTier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MobWaveManager {

    private static final int DEFAULT_WAVES = 3;
    private static final int DEFAULT_MOBS_PER_WAVE = 5;
    private static final int DEFAULT_INTERVAL_SECONDS = 8;
    private static final double DEFAULT_RADIUS = 10.0D;
    private static final int DYNAMIC_MIN_LEVEL = 1;
    private static final int DYNAMIC_MAX_LEVEL = 500;
    private static final int DEFAULT_NORMAL_MOB_LEVEL_RANGE = 20;
    private static final int DEFAULT_BOSS_LEVEL_BONUS = 10;
    private static final int DEFAULT_S_OFFSET = 5;
    private static final int DEFAULT_PER_WAVE_LEVEL_INCREMENT = 2;
    private static final long WAVE_CLEAR_CHECK_INTERVAL_TICKS = 1L;
    private static final String WAVE_OVERRIDE_ID_PREFIX = "elwave:";
    private static final String WAVE_PORTAL_BLOCK_BASE_ID = "EL_WavePortal";
    private static final int AIR_BLOCK_ID = 0;
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 319;
    private static final int WAVE_PORTAL_VERTICAL_OFFSET = 12;
    private static final int WAVE_PORTAL_SEARCH_HEIGHT = 24;
    private static final int WAVE_PORTAL_CLEANUP_MAX_RETRIES = 20;
    private static final long WAVE_PORTAL_CLEANUP_RETRY_DELAY_MS = 250L;

    private static final String[] HOSTILE_ROLE_KEYWORDS = new String[] {
            "skeleton", "zombie", "undead", "ghoul", "wraith", "goblin", "orc", "bandit", "raider",
            "cultist", "demon", "warlock", "necromancer", "spider", "wolf", "warg", "scorpion",
            "slime", "golem", "ogre", "troll", "assassin", "guardian", "knight", "brute", "hostile"
    };

    private static final String[] EXCLUDED_ROLE_KEYWORDS = new String[] {
            "trex", "t-rex", "dino", "dinosaur", "villager", "merchant", "trader", "civilian",
            "farmer", "worker", "pet", "mount", "companion", "friendly", "passive", "tutorial"
    };

    private static final Map<UUID, ActiveWaveSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    private MobWaveManager() {
    }

    public static void shutdown() {
        List<ActiveWaveSession> sessions = new ArrayList<>(ACTIVE_SESSIONS.values());
        ACTIVE_SESSIONS.clear();

        for (ActiveWaveSession session : sessions) {
            session.cancelled.set(true);
            for (ScheduledFuture<?> future : session.scheduledFutures) {
                if (future != null) {
                    future.cancel(false);
                }
            }
            session.scheduledFutures.clear();
            teardownSessionState(session);
        }
    }

    @Nonnull
    public static StartResult startForPlayer(@Nonnull PlayerRef playerRef, @Nonnull GateRankTier rankTier) {
        UUID playerUuid = playerRef.getUuid();

        ActiveWaveSession existing = ACTIVE_SESSIONS.get(playerUuid);
        if (existing != null && !existing.cancelled.get()) {
            return StartResult.failed("A wave is already running. Use /gate wave stop first.");
        }

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return StartResult.failed("You must be in-world to start waves.");
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

        List<String> hostileRoles = resolveHostileRolePool(roleNames);
        if (hostileRoles.isEmpty()) {
            return StartResult.failed("Could not find hostile NPC role templates for wave spawning.");
        }

        WavePoolConfig waveConfig = loadWaveConfig(rankTier);
        String sessionRoleName = waveConfig != null
                ? String.format(Locale.ROOT, "wave-config (%d pools)", waveConfig.pools.size())
                : String.format(Locale.ROOT, "hostile-rpg (%d roles)", hostileRoles.size());

        LevelRange baseLevelRange = resolveTierBaseLevelRange(playerRef, rankTier);
        String overrideId = WAVE_OVERRIDE_ID_PREFIX + playerUuid;

        ActiveWaveSession session = new ActiveWaveSession(
                playerUuid,
                playerRef,
                world,
                worldName,
                sessionRoleName,
                hostileRoles,
                waveConfig,
                rankTier,
                DEFAULT_WAVES,
                resolveMobsPerWave(rankTier),
                DEFAULT_INTERVAL_SECONDS,
                DEFAULT_RADIUS,
                overrideId,
                baseLevelRange
        );

        ACTIVE_SESSIONS.put(playerUuid, session);
        spawnWave(session, 1);
        scheduleWaveMonitor(session);

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
        teardownSessionState(session);
        return StopResult.stopped(session.roleName);
    }

    @Nonnull
    public static StopResult clearForPlayer(@Nonnull PlayerRef playerRef) {
        // Clear is intentionally equivalent to stop: cancel timers, kill active wave mobs, and remove overrides.
        return stopForPlayer(playerRef);
    }

    @Nonnull
    public static SkipResult skipWaveForPlayer(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        ActiveWaveSession session = ACTIVE_SESSIONS.get(playerUuid);
        if (session == null || session.cancelled.get()) {
            return SkipResult.notRunning();
        }

        int currentWave = Math.max(1, session.currentWave);
        int killed = forceKillActiveWaveMobs(session);

        if (currentWave >= session.totalWaves) {
            cleanupSession(playerUuid);
            return SkipResult.completed(killed, currentWave, session.totalWaves);
        }

        spawnWave(session, currentWave + 1);
        return SkipResult.skipped(killed, currentWave + 1, session.totalWaves);
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
            session.currentWave = waveNumber;
            session.activeWaveMobRefs.clear();
                ensureWavePortalVisual(session, transform.getPosition());

            // Pick a random pool for this wave; falls back to the runtime hostile pool if no config.
            WavePoolConfig.Pool currentPool = session.waveConfig != null ? session.waveConfig.pickRandomPool() : null;
            List<String> normalPool = (currentPool != null && !currentPool.mobs.isEmpty())
                    ? currentPool.mobs
                    : session.hostileRoleNames;
            boolean hasBossPool = session.waveConfig != null && !session.waveConfig.bossPool.isEmpty();
            String currentPoolId = currentPool != null ? currentPool.id : "fallback";

            Message titlePrimary = Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                    "%s WAVE %d / %d",
                    session.rankTier.letter(),
                    waveNumber,
                    session.totalWaves))).color("#f8d66d");
            Message titleSecondary = Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                    "%d hostile mobs | Lv %d-%d | 1 random boss Lv %d",
                    session.mobsPerWave,
                    waveLevelRange.min,
                    waveLevelRange.max,
                    waveLevelRange.bossLevel))).color("#8fd3ff");
            EventTitleUtil.showEventTitleToPlayer(session.playerRef, titlePrimary, titleSecondary, true);

            Vector3d playerPos = transform.getPosition();
            NPCPlugin npcPlugin = NPCPlugin.get();
            int bossIndex = session.mobsPerWave <= 0 ? -1 : (int) Math.floor(Math.random() * session.mobsPerWave);
            boolean bossSpawned = false;

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

                boolean spawnAsBoss = i == bossIndex;
                String roleName;
                if (spawnAsBoss) {
                    if (hasBossPool) {
                        String bossRole = session.waveConfig.pickRandomBoss();
                        roleName = (bossRole != null && !bossRole.isBlank()) ? bossRole : pickRandomRole(normalPool);
                    } else {
                        roleName = pickRandomRole(normalPool);
                    }
                    applyBossLevelOverride(session, waveLevelRange.bossLevel);
                } else {
                    roleName = pickRandomRole(normalPool);
                }
                Object spawnedResult = npcPlugin.spawnNPC(store, roleName, null, spawnPosition, spawnRotation);
                Ref<EntityStore> spawnedRef = extractSpawnedEntityRef(spawnedResult);
                applyWaveLevelOverride(session, waveLevelRange);
                if (spawnedRef != null) {
                    session.activeWaveMobRefs.add(spawnedRef);
                    spawned++;
                    if (spawnAsBoss) {
                        bossSpawned = true;
                        session.playerRef.sendMessage(
                                Message.raw(String.format(Locale.ROOT, "[Wave] Boss promoted: %s (Lv %d)", roleName, waveLevelRange.bossLevel))
                                        .color("#f3b37a")
                        );
                    }
                }
            }

            if (!bossSpawned && session.mobsPerWave > 0) {
                spawnFallbackBoss(session, store, playerPos, waveLevelRange, npcPlugin, hasBossPool);
            }

            session.playerRef.sendMessage(Message.raw("[Wave] --------------------------------").color("#4f5f78"));
            session.playerRef.sendMessage(
                    Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                            "[Wave] %s Rank | Wave %d/%d | Spawned %d/%d",
                            session.rankTier.letter(),
                            waveNumber,
                            session.totalWaves,
                            spawned,
                            session.mobsPerWave))).color("#8fd3ff")
            );
            session.playerRef.sendMessage(
                    Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                            "[Wave] Level Range: %d-%d | Boss Level: %d | Pool: %s | Radius: %.1f",
                            waveLevelRange.min,
                            waveLevelRange.max,
                            waveLevelRange.bossLevel,
                            currentPoolId,
                            session.radius))).color("#b4becf")
            );

            session.waveClearedAtEpochMillis = -1L;
        });
    }

    private static void spawnFallbackBoss(@Nonnull ActiveWaveSession session,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Vector3d playerPos,
                                 @Nonnull LevelRange waveLevelRange,
                                 @Nonnull NPCPlugin npcPlugin,
                                 boolean useConfigBossPool) {
        applyBossLevelOverride(session, waveLevelRange.bossLevel);

        double angle = Math.random() * Math.PI * 2.0D;
        double distance = (0.4D + Math.random() * 0.6D) * session.radius;
        double x = playerPos.x + Math.cos(angle) * distance;
        double z = playerPos.z + Math.sin(angle) * distance;
        double y = NPCPhysicsMath.heightOverGround(session.world, x, z);
        if (y < 0.0D) {
            y = playerPos.y;
        }

        Vector3d spawnPosition = new Vector3d(x, y, z);
        Vector3f spawnRotation = new Vector3f(0.0F, (float) (Math.random() * Math.PI * 2.0D), 0.0F);
        String bossRole;
        if (useConfigBossPool) {
            String picked = session.waveConfig.pickRandomBoss();
            bossRole = (picked != null && !picked.isBlank()) ? picked : pickRandomRole(session.hostileRoleNames);
        } else {
            bossRole = pickRandomRole(session.hostileRoleNames);
        }
        Object bossResult = npcPlugin.spawnNPC(store, bossRole, null, spawnPosition, spawnRotation);

        // Restore normal override immediately after boss spawn so ambient spawns stay in normal range.
        applyWaveLevelOverride(session, waveLevelRange);

        Ref<EntityStore> bossRef = extractSpawnedEntityRef(bossResult);
        if (bossRef == null) {
            return;
        }

        session.activeWaveMobRefs.add(bossRef);
        session.playerRef.sendMessage(
                Message.raw(String.format(Locale.ROOT, "[Wave] Boss fallback promoted: %s (Lv %d)", bossRole, waveLevelRange.bossLevel))
                        .color("#f3b37a")
        );
    }

    private static void scheduleWaveMonitor(@Nonnull ActiveWaveSession session) {
        ScheduledFuture<?> future = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                () -> evaluateWaveProgress(session.playerUuid),
                WAVE_CLEAR_CHECK_INTERVAL_TICKS,
                WAVE_CLEAR_CHECK_INTERVAL_TICKS,
                TimeUnit.SECONDS
        );
        session.scheduledFutures.add(future);
    }

    private static void evaluateWaveProgress(@Nonnull UUID playerUuid) {
        ActiveWaveSession session = ACTIVE_SESSIONS.get(playerUuid);
        if (session == null || session.cancelled.get()) {
            return;
        }

        session.world.execute(() -> {
            ActiveWaveSession active = ACTIVE_SESSIONS.get(playerUuid);
            if (active == null || active.cancelled.get()) {
                return;
            }

            if (!isWaveCleared(active)) {
                active.waveClearedAtEpochMillis = -1L;
                return;
            }

            if (active.waveClearedAtEpochMillis <= 0L) {
                active.waveClearedAtEpochMillis = System.currentTimeMillis();
                return;
            }

            long elapsed = System.currentTimeMillis() - active.waveClearedAtEpochMillis;
            if (elapsed < active.intervalSeconds * 1000L) {
                return;
            }

            if (active.currentWave >= active.totalWaves) {
                EventTitleUtil.showEventTitleToPlayer(
                        active.playerRef,
                        Message.raw("WAVE COMPLETE").color("#6cff78"),
                        Message.raw(String.format(Locale.ROOT,
                                "%s rank | %d waves finished",
                                active.rankTier.letter(),
                                active.totalWaves)).color("#d5f7db"),
                        false
                );
                cleanupSession(playerUuid);
                return;
            }

            spawnWave(active, active.currentWave + 1);
        });
    }

    private static boolean isWaveCleared(@Nonnull ActiveWaveSession session) {
        List<Ref<EntityStore>> stillAlive = new ArrayList<>();
        for (Ref<EntityStore> ref : session.activeWaveMobRefs) {
            if (ref != null && ref.isValid()) {
                stillAlive.add(ref);
            }
        }
        session.activeWaveMobRefs.clear();
        session.activeWaveMobRefs.addAll(stillAlive);
        return session.activeWaveMobRefs.isEmpty();
    }

    private static int forceKillActiveWaveMobs(@Nonnull ActiveWaveSession session) {
        int killed = 0;
        for (Ref<EntityStore> ref : session.activeWaveMobRefs) {
            if (ref == null || !ref.isValid()) {
                continue;
            }

            Store<EntityStore> store = ref.getStore();
            if (store == null) {
                continue;
            }

            DeathComponent.tryAddComponent(store, ref, new Damage(Damage.NULL_SOURCE, DamageCause.COMMAND, 0.0F));
            killed++;
        }
        session.activeWaveMobRefs.clear();
        session.waveClearedAtEpochMillis = -1L;
        return killed;
    }

    @Nonnull
    private static String pickRandomRole(@Nonnull List<String> roleNames) {
        if (roleNames.isEmpty()) {
            return "";
        }
        int index = (int) Math.floor(Math.random() * roleNames.size());
        return roleNames.get(Math.max(0, Math.min(index, roleNames.size() - 1)));
    }

    @Nonnull
    private static List<String> resolveHostileRolePool(@Nonnull List<String> roleNames) {
        List<String> hostile = new ArrayList<>();
        for (String role : roleNames) {
            String normalized = role.toLowerCase(Locale.ROOT);
            if (containsAny(normalized, EXCLUDED_ROLE_KEYWORDS)) {
                continue;
            }
            if (containsAny(normalized, HOSTILE_ROLE_KEYWORDS)) {
                hostile.add(role);
            }
        }
        return hostile;
    }

    private static boolean containsAny(@Nonnull String normalizedRole, @Nonnull String[] keywords) {
        for (String keyword : keywords) {
            if (normalizedRole.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private static Ref<EntityStore> extractSpawnedEntityRef(@Nullable Object spawnResult) {
        if (spawnResult == null) {
            return null;
        }

        if (spawnResult instanceof Ref<?> directRef) {
            return (Ref<EntityStore>) directRef;
        }

        String[] methodCandidates = new String[] {"getLeft", "getFirst", "getKey", "left", "first"};
        for (String methodName : methodCandidates) {
            try {
                Method method = spawnResult.getClass().getMethod(methodName);
                Object value = method.invoke(spawnResult);
                if (value instanceof Ref<?> ref) {
                    return (Ref<EntityStore>) ref;
                }
            } catch (Exception ignored) {
                // Try next candidate accessor.
            }
        }

        String[] fieldCandidates = new String[] {"left", "first", "key"};
        for (String fieldName : fieldCandidates) {
            try {
                Field field = spawnResult.getClass().getField(fieldName);
                Object value = field.get(spawnResult);
                if (value instanceof Ref<?> ref) {
                    return (Ref<EntityStore>) ref;
                }
            } catch (Exception ignored) {
                // Try next candidate field.
            }
        }

        return null;
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
            normalMax = clampDynamicLevel(normalMin + resolveNormalMobRange());
            int boss = clampDynamicLevel(Math.max(normalMin, normalMax) + resolveBossLevelBonus());
            return new LevelRange(normalMin, Math.max(normalMin, normalMax), boss);
        }

        int baseLevel = resolveTierAnchorLevel(DYNAMIC_MIN_LEVEL, playerLevel, rankTier);
        normalMax = clampDynamicLevel(baseLevel);
        normalMin = clampDynamicLevel(normalMax - resolveNormalMobRange());
        int boss = clampDynamicLevel(Math.max(normalMin, normalMax) + resolveBossLevelBonus());
        return new LevelRange(normalMin, Math.max(normalMin, normalMax), boss);
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

    private static int resolveMobsPerWave(@Nonnull GateRankTier rankTier) {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return DEFAULT_MOBS_PER_WAVE;
        }
        return Math.max(1, manager.getDungeonWaveMobCountForRank(rankTier));
    }

    @Nullable
    private static WavePoolConfig loadWaveConfig(@Nonnull GateRankTier rankTier) {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return null;
        }
        return manager.loadWavePoolConfig(rankTier);
    }

    private static int resolveNormalMobRange() {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return DEFAULT_NORMAL_MOB_LEVEL_RANGE;
        }
        return Math.max(0, manager.getDungeonNormalMobLevelRange());
    }

    private static int resolveBossLevelBonus() {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return DEFAULT_BOSS_LEVEL_BONUS;
        }
        return Math.max(0, manager.getDungeonBossLevelBonus());
    }

    @Nonnull
    private static LevelRange resolveWaveLevelRange(@Nonnull ActiveWaveSession session, int waveNumber) {
        int increment = Math.max(0, waveNumber - 1) * DEFAULT_PER_WAVE_LEVEL_INCREMENT;
        int min = clampDynamicLevel(session.baseLevelRange.min + increment);
        int max = clampDynamicLevel(session.baseLevelRange.max + increment);
        int boss = clampDynamicLevel(Math.max(min, max) + resolveBossLevelBonus());
        return new LevelRange(min, Math.max(min, max), boss);
    }

    private static void applyWaveLevelOverride(@Nonnull ActiveWaveSession session, @Nonnull LevelRange range) {
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null) {
            return;
        }

        int bossOffset = Math.max(0, range.bossLevel - range.max);
        api.registerMobWorldGateLevelOverride(session.overrideId, session.worldName, range.min, range.max, bossOffset);
    }

    private static void applyBossLevelOverride(@Nonnull ActiveWaveSession session, int bossLevel) {
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null) {
            return;
        }

        int clampedBoss = clampDynamicLevel(bossLevel);
        api.registerMobWorldGateLevelOverride(session.overrideId, session.worldName, clampedBoss, clampedBoss, 0);
    }

    private static void removeWaveLevelOverride(@Nonnull ActiveWaveSession session) {
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null) {
            return;
        }

        api.removeMobGateLevelOverride(session.overrideId);
        api.removeMobWorldFixedLevelOverride(session.overrideId);
    }

    private static void ensureWavePortalVisual(@Nonnull ActiveWaveSession session, @Nonnull Vector3d playerPos) {
        if (session.wavePortalPlacement != null) {
            return;
        }

        String portalBlockId = resolveWavePortalBlockId(session.rankTier);
        int portalBlockIntId = BlockType.getAssetMap().getIndex(portalBlockId);
        if (portalBlockIntId == Integer.MIN_VALUE) {
            return;
        }

        int baseX = (int) Math.floor(playerPos.x);
        int baseZ = (int) Math.floor(playerPos.z);
        int startY = Math.max(WORLD_MIN_Y, Math.min(WORLD_MAX_Y, (int) Math.floor(playerPos.y) + WAVE_PORTAL_VERTICAL_OFFSET));

        WorldChunk chunk = session.world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(baseX, baseZ));
        if (chunk == null) {
            return;
        }

        int maxY = Math.min(WORLD_MAX_Y, startY + WAVE_PORTAL_SEARCH_HEIGHT);
        for (int y = startY; y <= maxY; y++) {
            if (chunk.getBlock(baseX, y, baseZ) != AIR_BLOCK_ID) {
                continue;
            }

            chunk.setBlock(baseX, y, baseZ, portalBlockIntId);
            chunk.markNeedsSaving();
            session.wavePortalPlacement = new WavePortalPlacement(baseX, y, baseZ, portalBlockIntId);
            return;
        }
    }

    private static boolean cleanupWavePortalVisual(@Nonnull ActiveWaveSession session) {
        WavePortalPlacement placement = session.wavePortalPlacement;
        if (placement == null) {
            return true;
        }

        WorldChunk chunk = session.world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(placement.x, placement.z));
        if (chunk == null) {
            return false;
        }

        if (chunk.getBlock(placement.x, placement.y, placement.z) == placement.blockIntId) {
            chunk.setBlock(placement.x, placement.y, placement.z, AIR_BLOCK_ID);
            chunk.markNeedsSaving();
        }

        session.wavePortalPlacement = null;
        return true;
    }

    private static void cleanupWavePortalVisualWithRetry(@Nonnull ActiveWaveSession session, int attempt) {
        boolean removed = cleanupWavePortalVisual(session);
        if (removed) {
            return;
        }

        if (attempt >= WAVE_PORTAL_CLEANUP_MAX_RETRIES) {
            session.wavePortalPlacement = null;
            return;
        }

        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                session.world.execute(() -> cleanupWavePortalVisualWithRetry(session, attempt + 1));
            } catch (Exception ignored) {
                // Fallback keeps cleanup progressing even if dispatch fails transiently.
                cleanupWavePortalVisualWithRetry(session, attempt + 1);
            }
        }, WAVE_PORTAL_CLEANUP_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    @Nonnull
    private static String resolveWavePortalBlockId(@Nonnull GateRankTier rankTier) {
        String candidate = WAVE_PORTAL_BLOCK_BASE_ID + rankTier.blockIdSuffix();
        return BlockType.getAssetMap().getIndex(candidate) == Integer.MIN_VALUE
                ? WAVE_PORTAL_BLOCK_BASE_ID
                : candidate;
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
        teardownSessionState(removed);
    }

    private static void teardownSessionState(@Nonnull ActiveWaveSession session) {
        try {
            session.world.execute(() -> {
                forceKillActiveWaveMobs(session);
                cleanupWavePortalVisualWithRetry(session, 0);
                removeWaveLevelOverride(session);
            });
        } catch (Exception ignored) {
            // Fallback keeps cleanup working even if world dispatch is unavailable.
            forceKillActiveWaveMobs(session);
            cleanupWavePortalVisualWithRetry(session, 0);
            removeWaveLevelOverride(session);
        }
    }

    private static final class LevelRange {
        private final int min;
        private final int max;
        private final int bossLevel;

        private LevelRange(int min, int max, int bossLevel) {
            this.min = min;
            this.max = max;
            this.bossLevel = bossLevel;
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
        private final List<String> hostileRoleNames;
        @Nullable
        private final WavePoolConfig waveConfig;
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
        private int currentWave = 0;
        private long waveClearedAtEpochMillis = -1L;
        private final List<Ref<EntityStore>> activeWaveMobRefs = new ArrayList<>();
        private final List<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        @Nullable
        private WavePortalPlacement wavePortalPlacement;

        private ActiveWaveSession(@Nonnull UUID playerUuid,
                                  @Nonnull PlayerRef playerRef,
                                  @Nonnull World world,
                                  @Nonnull String worldName,
                                  @Nonnull String roleName,
                                  @Nonnull List<String> hostileRoleNames,
                                  @Nullable WavePoolConfig waveConfig,
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
            this.hostileRoleNames = hostileRoleNames;
            this.waveConfig = waveConfig;
            this.rankTier = rankTier;
            this.totalWaves = totalWaves;
            this.mobsPerWave = mobsPerWave;
            this.intervalSeconds = intervalSeconds;
            this.radius = radius;
            this.overrideId = overrideId;
            this.baseLevelRange = baseLevelRange;
        }
    }

    private static final class WavePortalPlacement {
        private final int x;
        private final int y;
        private final int z;
        private final int blockIntId;

        private WavePortalPlacement(int x, int y, int z, int blockIntId) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockIntId = blockIntId;
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
                "Wave started.",
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

    public static final class SkipResult {
        public final boolean skipped;
        public final boolean completed;
        public final int killed;
        public final int nextWave;
        public final int totalWaves;

        private SkipResult(boolean skipped, boolean completed, int killed, int nextWave, int totalWaves) {
            this.skipped = skipped;
            this.completed = completed;
            this.killed = killed;
            this.nextWave = nextWave;
            this.totalWaves = totalWaves;
        }

        @Nonnull
        private static SkipResult notRunning() {
            return new SkipResult(false, false, 0, 0, 0);
        }

        @Nonnull
        private static SkipResult skipped(int killed, int nextWave, int totalWaves) {
            return new SkipResult(true, false, killed, nextWave, totalWaves);
        }

        @Nonnull
        private static SkipResult completed(int killed, int finalWave, int totalWaves) {
            return new SkipResult(true, true, killed, finalWave, totalWaves);
        }
    }
}
