package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.enums.GateRankTier;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.packets.world.UnloadChunk;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.util.NPCPhysicsMath;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class MobWaveManager {

    private static final int DEFAULT_WAVES = 3;
    private static final int DEFAULT_MOBS_PER_WAVE = 5;
    private static final int DEFAULT_INTERVAL_SECONDS = 5;
    private static final double DEFAULT_RADIUS = 10.0D;
    private static final int DYNAMIC_MIN_LEVEL = 1;
    private static final int DYNAMIC_MAX_LEVEL = 500;
    private static final int DEFAULT_BOSS_LEVEL_BONUS = 10;
    private static final int DEFAULT_PER_WAVE_LEVEL_INCREMENT = 2;
    private static final int DEFAULT_WAVE_EXPIRY_MINUTES = 30;
    private static final int WAVE_DRY_LAND_ATTEMPTS_PER_MOB = 12;
    private static final int WAVE_DRY_LAND_ATTEMPTS_FOR_BOSS = 16;
    private static final long WAVE_CLEAR_CHECK_INTERVAL_TICKS = 1L;
    private static final String WAVE_OVERRIDE_ID_PREFIX = "elwave:";
    private static final String WAVE_PORTAL_BLOCK_BASE_ID = "EL_WavePortal";
    private static final int AIR_BLOCK_ID = 0;
    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 319;
    private static final int WAVE_PORTAL_SEARCH_HEIGHT = 24;
    private static final int WAVE_PORTAL_CLEANUP_MAX_RETRIES = 20;
    private static final long WAVE_PORTAL_CLEANUP_RETRY_DELAY_MS = 250L;
    private static final int WAVE_PORTAL_PURGE_RADIUS = 24;
    private static final int WAVE_PORTAL_PURGE_VERTICAL_BELOW = 8;
    private static final int WAVE_PORTAL_PURGE_VERTICAL_ABOVE = 48;
    private static final int WAVE_PORTAL_BREAK_SETTINGS = 256 | 4;
    private static final int LARGE_WAVE_STAGGER_THRESHOLD = 8;
    private static final String WAVE_PORTAL_PERSISTENCE_FILENAME = "wave_portals.json";
    private static final long PERSISTED_WAVE_PORTAL_SWEEP_INTERVAL_SECONDS = 5L;
    private static final int PERSISTED_WAVE_PORTAL_SWEEP_MAX_ATTEMPTS = 120;
    private static final String WAVE_GATE_SPAWN_SOUND_ID = "SFX_EL_S_Rank_Gate_Spawn";
    private static final String WAVE_10_SECOND_COUNTDOWN_SOUND_ID = "SFX_EL_DungeonBreak_10_Second_Countdown";
    private static final String S_WAVE_1_MINUTE_COUNTDOWN_SOUND_ID = "SFX_EL_S_Wave_1_Minute_Countdown";
    private static final int DEFAULT_NATURAL_WAVE_MAX_CONCURRENT_SPAWNS = 3;
    private static final long NO_KILL_HINT_INTERVAL_MS = 10_000L;
    private static final double NO_KILL_HINT_NEARBY_RADIUS = 48.0D;
    private static final int NO_KILL_HINT_MAX_LISTED_MOBS = 12;

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
    private static final Map<UUID, ScheduledFuture<?>> PENDING_NATURAL_COUNTDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, List<ScheduledFuture<?>>> PENDING_NATURAL_PRESTART_COUNTDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS = new ConcurrentHashMap<>();
    private static final Map<UUID, WavePortalPlacement> PENDING_NATURAL_PREVIEW_PORTALS = new ConcurrentHashMap<>();
    private static final Map<UUID, WaveStartSource> PENDING_NATURAL_SOURCES = new ConcurrentHashMap<>();
    private static final Map<UUID, GateRankTier> PENDING_NATURAL_RANKS = new ConcurrentHashMap<>();
    @Nullable
    private static ScheduledFuture<?> NATURAL_WAVE_TIMER;
    private static final Map<String, LinkedGateWaveState> GATE_WAVE_STATES = new ConcurrentHashMap<>();
    private static final Map<String, ScheduledFuture<?>> PENDING_LINKED_GATE_COUNTDOWNS = new ConcurrentHashMap<>();
    private static final Map<String, List<ScheduledFuture<?>>> PENDING_LINKED_PRESTART_COUNTDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, WavePortalPlacement> TRACKED_WAVE_PORTALS = new ConcurrentHashMap<>();
    private static final Object WAVE_PORTAL_PERSISTENCE_LOCK = new Object();
    private static final Gson WAVE_PORTAL_GSON = new GsonBuilder().setPrettyPrinting().create();
    @Nullable
    private static File wavePortalPersistenceFile;
    @Nullable
    private static ScheduledFuture<?> persistedWavePortalSweepFuture;
    private static final AtomicInteger persistedWavePortalSweepAttempts = new AtomicInteger(0);

    private MobWaveManager() {
    }

    public static void initialize() {
        shutdownIndividualNaturalWaveSpawner();
        initializeWavePortalPersistence();
        startPersistedWavePortalSweepTask();
        scheduleNextIndividualNaturalWaveTick();
    }

    public static void shutdown() {
        shutdownIndividualNaturalWaveSpawner();
        stopPersistedWavePortalSweepTask();

        List<ScheduledFuture<?>> countdowns = new ArrayList<>(PENDING_NATURAL_COUNTDOWNS.values());
        PENDING_NATURAL_COUNTDOWNS.clear();
        PENDING_NATURAL_SOURCES.clear();
        PENDING_NATURAL_RANKS.clear();
        PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS.clear();
        for (ScheduledFuture<?> countdown : countdowns) {
            if (countdown != null) {
                countdown.cancel(false);
            }
        }
        for (List<ScheduledFuture<?>> futures : new ArrayList<>(PENDING_NATURAL_PRESTART_COUNTDOWNS.values())) {
            cancelScheduledFutures(futures);
        }
        PENDING_NATURAL_PRESTART_COUNTDOWNS.clear();
        for (UUID playerUuid : new ArrayList<>(PENDING_NATURAL_PREVIEW_PORTALS.keySet())) {
            clearPendingNaturalPreviewPortal(playerUuid);
        }
        PENDING_NATURAL_PREVIEW_PORTALS.clear();

        List<ScheduledFuture<?>> linkedCountdowns = new ArrayList<>(PENDING_LINKED_GATE_COUNTDOWNS.values());
        PENDING_LINKED_GATE_COUNTDOWNS.clear();
        for (ScheduledFuture<?> countdown : linkedCountdowns) {
            if (countdown != null) {
                countdown.cancel(false);
            }
        }
        for (List<ScheduledFuture<?>> futures : new ArrayList<>(PENDING_LINKED_PRESTART_COUNTDOWNS.values())) {
            cancelScheduledFutures(futures);
        }
        PENDING_LINKED_PRESTART_COUNTDOWNS.clear();
        GATE_WAVE_STATES.clear();

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

        persistTrackedWavePortalsToDisk();
    }

    @Nonnull
    public static StartResult startForPlayer(@Nonnull PlayerRef playerRef, @Nonnull GateRankTier rankTier) {
        return startForPlayerInternal(playerRef, rankTier, true, null, null, null, WaveStartSource.DIRECT_COMMAND);
    }

    @Nonnull
    private static StartResult startForPlayerInternal(@Nonnull PlayerRef playerRef,
                                                      @Nonnull GateRankTier rankTier,
                                                      boolean wavePortalVisualEnabled,
                                                      @Nullable String linkedGateId,
                                                      @Nullable WavePortalPlacement initialPortalPlacement,
                                                      @Nullable Vector3d waveCenterPosition,
                                                      @Nonnull WaveStartSource startSource) {
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
                resolveWaveIntervalSeconds(rankTier),
                DEFAULT_RADIUS,
                overrideId,
                baseLevelRange,
                wavePortalVisualEnabled,
                linkedGateId,
                initialPortalPlacement,
                waveCenterPosition,
                startSource,
                resolveWaveSessionExpiryAtMillis(linkedGateId)
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

    public static boolean startLinkedGateWaveForGate(@Nonnull PlayerRef playerRef,
                                                     @Nonnull GateRankTier rankTier,
                                                     @Nonnull String gateIdentity) {
        return startLinkedGateWaveForGate(playerRef, rankTier, gateIdentity, true);
    }

    public static boolean startLinkedGateWaveForGate(@Nonnull PlayerRef playerRef,
                                                     @Nonnull GateRankTier rankTier,
                                                     @Nonnull String gateIdentity,
                                                     boolean announceLinkedBreak) {
        String canonicalGateId = normalizeGateIdentity(gateIdentity);
        if (canonicalGateId == null) {
            return false;
        }

        if (GATE_WAVE_STATES.containsKey(canonicalGateId)) {
            return true;
        }

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return false;
        }

        int delayMinutes = resolveNaturalWaveOpenDelay(rankTier);
        long delaySeconds = Math.max(1L, TimeUnit.MINUTES.toSeconds(delayMinutes));
        long opensAtEpochMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);
        Vector3d linkedGateCenter = resolveLinkedGateWaveCenter(canonicalGateId, world);
        if (linkedGateCenter == null) {
            return false;
        }
        GATE_WAVE_STATES.put(canonicalGateId,
            LinkedGateWaveState.pending(rankTier, playerRef.getUuid(), opensAtEpochMillis));
        if (announceLinkedBreak) {
            announceLinkedGateBreak(rankTier, delayMinutes);
        }
        if (delaySeconds >= 10L) {
            PENDING_LINKED_PRESTART_COUNTDOWNS.put(canonicalGateId,
                    schedulePrestartCountdown(rankTier, delaySeconds, true));
        }

        ScheduledFuture<?> countdownFuture = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            PENDING_LINKED_GATE_COUNTDOWNS.remove(canonicalGateId);
            cancelScheduledFutures(PENDING_LINKED_PRESTART_COUNTDOWNS.remove(canonicalGateId));
            world.execute(() -> {
                StartResult result = startForPlayerInternal(
                        playerRef,
                        rankTier,
                        false,
                        canonicalGateId,
                        null,
                    linkedGateCenter,
                        WaveStartSource.LINKED_GATE);
                if (result.started) {
                    GATE_WAVE_STATES.put(canonicalGateId,
                            LinkedGateWaveState.active(rankTier, playerRef.getUuid(), System.currentTimeMillis()));
                    announceLinkedGateOpen(rankTier);
                } else {
                    GATE_WAVE_STATES.remove(canonicalGateId);
                }
            });
        }, delaySeconds, TimeUnit.SECONDS);

        PENDING_LINKED_GATE_COUNTDOWNS.put(canonicalGateId, countdownFuture);
        return true;
    }

    @Nullable
    private static Vector3d resolveLinkedGateWaveCenter(@Nonnull String canonicalGateId, @Nonnull World fallbackWorld) {
        NaturalPortalGateManager.TrackedGateSnapshot trackedGate = NaturalPortalGateManager.findTrackedGate(canonicalGateId);
        if (trackedGate != null) {
            return new Vector3d(trackedGate.x(), trackedGate.y(), trackedGate.z());
        }
        return null;
    }

    public static boolean isGateEntryLocked(@Nullable String gateIdentity) {
        String canonicalGateId = normalizeGateIdentity(gateIdentity);
        if (canonicalGateId == null) {
            return false;
        }

        LinkedGateWaveState state = GATE_WAVE_STATES.get(canonicalGateId);
        if (state == null) {
            return false;
        }

        // Treat orphaned map entries as stale so standalone gates are never permanently sealed.
        boolean hasPendingCountdown = PENDING_LINKED_GATE_COUNTDOWNS.containsKey(canonicalGateId);
        boolean hasActiveLinkedSession = false;
        for (ActiveWaveSession session : ACTIVE_SESSIONS.values()) {
            if (session == null || session.cancelled.get()) {
                continue;
            }
            if (canonicalGateId.equals(session.linkedGateId)) {
                hasActiveLinkedSession = true;
                break;
            }
        }

        if (!hasPendingCountdown && !hasActiveLinkedSession) {
            GATE_WAVE_STATES.remove(canonicalGateId, state);
            return false;
        }

        return true;
    }

    @Nullable
    public static LinkedGateWaveTimingSnapshot getLinkedGateWaveTimingSnapshot(@Nullable String gateIdentity) {
        String canonicalGateId = normalizeGateIdentity(gateIdentity);
        if (canonicalGateId == null) {
            return null;
        }

        LinkedGateWaveState state = GATE_WAVE_STATES.get(canonicalGateId);
        if (state == null) {
            return null;
        }

        long expiresAtEpochMillis = 0L;
        for (ActiveWaveSession session : ACTIVE_SESSIONS.values()) {
            if (session == null || session.cancelled.get()) {
                continue;
            }
            if (!canonicalGateId.equals(session.linkedGateId)) {
                continue;
            }
            expiresAtEpochMillis = session.expiryAtEpochMillis;
            break;
        }

        return new LinkedGateWaveTimingSnapshot(
                state.stage,
                state.opensAtEpochMillis,
                state.openedAtEpochMillis,
                expiresAtEpochMillis);
    }

    public static void unregisterLinkedGateWave(@Nullable String gateIdentity) {
        String canonicalGateId = normalizeGateIdentity(gateIdentity);
        if (canonicalGateId == null) {
            return;
        }

        ScheduledFuture<?> pending = PENDING_LINKED_GATE_COUNTDOWNS.remove(canonicalGateId);
        if (pending != null) {
            pending.cancel(false);
        }
        cancelScheduledFutures(PENDING_LINKED_PRESTART_COUNTDOWNS.remove(canonicalGateId));

        GATE_WAVE_STATES.remove(canonicalGateId);

        for (ActiveWaveSession session : new ArrayList<>(ACTIVE_SESSIONS.values())) {
            if (session == null || session.linkedGateId == null) {
                continue;
            }
            if (!canonicalGateId.equals(session.linkedGateId)) {
                continue;
            }
            cleanupSession(session.playerUuid);
        }
    }

    public static int clearLinkedGateCombosForPlayer(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        int cleared = 0;

        for (Map.Entry<String, LinkedGateWaveState> entry : new ArrayList<>(GATE_WAVE_STATES.entrySet())) {
            LinkedGateWaveState state = entry.getValue();
            if (state == null || state.ownerUuid == null || !state.ownerUuid.equals(playerUuid)) {
                continue;
            }
            unregisterLinkedGateWave(entry.getKey());
            cleared++;
        }

        return cleared;
    }

    @Nonnull
    public static NaturalStartResult startNaturalForPlayer(@Nonnull PlayerRef playerRef, @Nonnull GateRankTier rankTier) {
        return startNaturalForPlayerInternal(playerRef, rankTier, WaveStartSource.MANUAL_NATURAL);
    }

    @Nonnull
    private static NaturalStartResult startNaturalForPlayerInternal(@Nonnull PlayerRef playerRef,
                                                                    @Nonnull GateRankTier rankTier,
                                                                    @Nonnull WaveStartSource startSource) {
        UUID playerUuid = playerRef.getUuid();

        if (PENDING_NATURAL_COUNTDOWNS.containsKey(playerUuid)) {
            return NaturalStartResult.failed("A dungeon break is already incoming for you. Use /gate wave stop to cancel.");
        }

        ActiveWaveSession existing = ACTIVE_SESSIONS.get(playerUuid);
        if (existing != null && !existing.cancelled.get()) {
            return NaturalStartResult.failed("A wave is already running. Use /gate wave stop first.");
        }

        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return NaturalStartResult.failed("You must be in-world to start waves.");
        }

        Store<EntityStore> store = entityRef.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return NaturalStartResult.failed("Could not resolve your current world.");
        }

        int delayMinutes = resolveNaturalWaveOpenDelay(rankTier);
        long delaySeconds = Math.max(1L, TimeUnit.MINUTES.toSeconds(delayMinutes));
        long opensAtEpochMillis = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(delaySeconds);

        WavePortalPlacement previewPortal = createWavePortalPlacement(world,
                playerRef.getTransform() == null ? null : playerRef.getTransform().getPosition(),
                rankTier);
        if (previewPortal == null) {
            return NaturalStartResult.failed("Could not prepare dungeon break marker location.");
        }
        PENDING_NATURAL_PREVIEW_PORTALS.put(playerUuid, previewPortal);
        PENDING_NATURAL_SOURCES.put(playerUuid, startSource);
        PENDING_NATURAL_RANKS.put(playerUuid, rankTier);
        PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS.put(playerUuid, opensAtEpochMillis);

        announceNaturalWaveBreak(rankTier, delayMinutes, previewPortal);
        if (delaySeconds >= 10L) {
            PENDING_NATURAL_PRESTART_COUNTDOWNS.put(playerUuid,
                    schedulePrestartCountdown(rankTier, delaySeconds, false));
        }

        ScheduledFuture<?> countdownFuture = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            PENDING_NATURAL_COUNTDOWNS.remove(playerUuid);
            PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS.remove(playerUuid);
            cancelScheduledFutures(PENDING_NATURAL_PRESTART_COUNTDOWNS.remove(playerUuid));
            WavePortalPlacement placement = PENDING_NATURAL_PREVIEW_PORTALS.remove(playerUuid);
            WaveStartSource source = PENDING_NATURAL_SOURCES.remove(playerUuid);
            PENDING_NATURAL_RANKS.remove(playerUuid);
            world.execute(() -> {
                Vector3d waveCenter = resolveWaveCenterFromPortalPlacement(placement, rankTier);
                if (placement != null) {
                    boolean removed = clearPortalPlacementFromLoadedChunk(world, placement);
                    if (!removed) {
                        requestPortalPlacementClearWithChunkLoad(world, placement);
                    }
                    untrackWavePortalPlacement(placement.placementUuid);
                }
                StartResult result = startForPlayerInternal(
                        playerRef,
                        rankTier,
                        true,
                        null,
                        null,
                        waveCenter,
                        source == null ? WaveStartSource.MANUAL_NATURAL : source);
                if (result.started) {
                    announceNaturalWaveOpen(rankTier);
                }
            });
        }, delaySeconds, TimeUnit.SECONDS);

        PENDING_NATURAL_COUNTDOWNS.put(playerUuid, countdownFuture);
        return NaturalStartResult.scheduled(rankTier, delayMinutes);
    }

    @Nonnull
    public static StopResult stopForPlayer(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        int clearedLinkedCombos = clearLinkedGateCombosForPlayer(playerRef);
        boolean shouldForceAnchorCleanup = false;

        ScheduledFuture<?> countdown = PENDING_NATURAL_COUNTDOWNS.remove(playerUuid);
        PENDING_NATURAL_SOURCES.remove(playerUuid);
        PENDING_NATURAL_RANKS.remove(playerUuid);
        PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS.remove(playerUuid);
        if (countdown != null) {
            countdown.cancel(false);
            shouldForceAnchorCleanup = true;
        }
        cancelScheduledFutures(PENDING_NATURAL_PRESTART_COUNTDOWNS.remove(playerUuid));
        clearPendingNaturalPreviewPortal(playerUuid);

        ActiveWaveSession session = ACTIVE_SESSIONS.remove(playerUuid);
        if (session == null) {
            if (shouldForceAnchorCleanup) {
                clearWavePortalVisualsNearPlayer(playerRef);
            }
            if (countdown != null) {
                if (clearedLinkedCombos > 0) {
                    return StopResult.stopped("(incoming dungeon break cancelled, cleared " + clearedLinkedCombos + " linked combo(s))");
                }
                return StopResult.stopped("(incoming dungeon break cancelled)");
            }
            if (clearedLinkedCombos > 0) {
                return StopResult.stopped("(cleared " + clearedLinkedCombos + " linked combo(s))");
            }
            return StopResult.notRunning();
        }

        session.cancelled.set(true);
        shouldForceAnchorCleanup = true;
        for (ScheduledFuture<?> future : session.scheduledFutures) {
            if (future != null) {
                future.cancel(false);
            }
        }
        session.scheduledFutures.clear();
        teardownSessionState(session);
        if (shouldForceAnchorCleanup) {
            clearWavePortalVisualsNearPlayer(playerRef);
        }
        if (clearedLinkedCombos > 0) {
            return StopResult.stopped(session.roleName + " | cleared " + clearedLinkedCombos + " linked combo(s)");
        }
        return StopResult.stopped(session.roleName);
    }

    public static long getPendingNaturalWaveOpensAtEpochMillis(@Nullable UUID ownerUuid) {
        if (ownerUuid == null) {
            return 0L;
        }
        Long value = PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS.get(ownerUuid);
        return value == null ? 0L : value;
    }

    @Nonnull
    public static StopResult clearForPlayer(@Nonnull PlayerRef playerRef) {
        // Clear is intentionally equivalent to stop: cancel timers, kill active wave mobs, and remove overrides.
        return stopForPlayer(playerRef);
    }

    public static int stopAllWavesInWorld(@Nonnull World world) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return 0;
        }

        int stopped = 0;

        for (ActiveWaveSession session : new ArrayList<>(ACTIVE_SESSIONS.values())) {
            if (session == null || session.cancelled.get()) {
                continue;
            }

            UUID sessionWorldUuid = session.world.getWorldConfig() == null
                    ? null
                    : session.world.getWorldConfig().getUuid();
            if (!worldUuid.equals(sessionWorldUuid)) {
                continue;
            }

            cleanupSession(session.playerUuid);
            stopped++;
        }

        for (UUID ownerUuid : new ArrayList<>(PENDING_NATURAL_COUNTDOWNS.keySet())) {
            if (!isPlayerInWorld(ownerUuid, worldUuid)) {
                continue;
            }

            ScheduledFuture<?> countdown = PENDING_NATURAL_COUNTDOWNS.remove(ownerUuid);
            if (countdown != null) {
                countdown.cancel(false);
                stopped++;
            }
            cancelScheduledFutures(PENDING_NATURAL_PRESTART_COUNTDOWNS.remove(ownerUuid));
            clearPendingNaturalPreviewPortal(ownerUuid);
            PENDING_NATURAL_SOURCES.remove(ownerUuid);
            PENDING_NATURAL_RANKS.remove(ownerUuid);
            PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS.remove(ownerUuid);
        }

        return stopped;
    }

    private static boolean isPlayerInWorld(@Nonnull UUID playerUuid, @Nonnull UUID worldUuid) {
        Universe universe = Universe.get();
        if (universe == null) {
            return false;
        }

        PlayerRef playerRef = universe.getPlayer(playerUuid);
        if (playerRef == null) {
            return false;
        }

        UUID playerWorldUuid = playerRef.getWorldUuid();
        return worldUuid.equals(playerWorldUuid);
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
            if (session.wavePortalVisualEnabled) {
                ensureWavePortalVisual(session, transform.getPosition());
            }

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
            Vector3d waveCenter = session.waveCenterPosition == null ? playerPos : session.waveCenterPosition;
            NPCPlugin npcPlugin = NPCPlugin.get();
            int bossIndex = session.mobsPerWave <= 0 ? -1 : (int) Math.floor(Math.random() * session.mobsPerWave);

            int batchSize = resolveSpawnBatchSize(session.rankTier, session.mobsPerWave);
            int batchDelaySeconds = resolveSpawnBatchDelaySeconds(session.rankTier, session.mobsPerWave);
            int totalBatches = batchSize <= 0 ? 1 : (int) Math.ceil(session.mobsPerWave / (double) batchSize);
            WaveSpawnProgress progress = new WaveSpawnProgress(totalBatches);

            if (totalBatches > 1 && batchDelaySeconds > 0) {
                session.playerRef.sendMessage(
                        Message.raw(String.format(Locale.ROOT,
                                "[Wave] Staggered spawn enabled: %d mobs in %d batches (%ds apart)",
                                session.mobsPerWave,
                                totalBatches,
                                batchDelaySeconds)).color("#d9c88d")
                );
            }

            for (int batch = 0; batch < totalBatches; batch++) {
                int startIndex = batch * batchSize;
                int endExclusive = Math.min(session.mobsPerWave, startIndex + batchSize);
                if (startIndex >= endExclusive) {
                    progress.completedBatches.incrementAndGet();
                    continue;
                }

                long delaySeconds = Math.max(0L, (long) batch * Math.max(0, batchDelaySeconds));
                Runnable batchTask = () -> spawnWaveBatch(
                        session,
                        waveNumber,
                        waveLevelRange,
                        normalPool,
                        hasBossPool,
                        currentPoolId,
                    waveCenter,
                        bossIndex,
                        startIndex,
                        endExclusive,
                        progress
                );

                if (delaySeconds <= 0L) {
                    batchTask.run();
                } else {
                    ScheduledFuture<?> future = HytaleServer.SCHEDULED_EXECUTOR.schedule(batchTask, delaySeconds, TimeUnit.SECONDS);
                    session.scheduledFutures.add(future);
                }
            }
        });
    }

    private static void spawnWaveBatch(@Nonnull ActiveWaveSession session,
                                       int waveNumber,
                                       @Nonnull LevelRange waveLevelRange,
                                       @Nonnull List<String> normalPool,
                                       boolean hasBossPool,
                                       @Nonnull String currentPoolId,
                                       @Nonnull Vector3d waveCenter,
                                       int bossIndex,
                                       int startIndex,
                                       int endExclusive,
                                       @Nonnull WaveSpawnProgress progress) {
        if (session.cancelled.get()) {
            return;
        }

        session.world.execute(() -> {
            if (session.cancelled.get() || session.currentWave != waveNumber) {
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

            NPCPlugin npcPlugin = NPCPlugin.get();

            for (int i = startIndex; i < endExclusive; i++) {
                Vector3d spawnPosition = findDryLandSpawnPosition(session.world, waveCenter, session.radius,
                        WAVE_DRY_LAND_ATTEMPTS_PER_MOB);
                if (spawnPosition == null) {
                    continue;
                }
                Vector3f spawnRotation = new Vector3f(0.0F, (float) (Math.random() * Math.PI * 2.0D), 0.0F);

                boolean spawnAsBoss = i == bossIndex;
                String roleName;
                if (spawnAsBoss) {
                    if (hasBossPool && session.waveConfig != null) {
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
                    session.activeWaveMobNames.put(spawnedRef, roleName);
                    progress.spawned.incrementAndGet();
                    if (spawnAsBoss) {
                        progress.bossSpawned.set(true);
                        session.playerRef.sendMessage(
                                Message.raw(String.format(Locale.ROOT, "[Wave] Boss promoted: %s (Lv %d)", roleName, waveLevelRange.bossLevel))
                                        .color("#f3b37a")
                        );
                    }
                }
            }

            int completed = progress.completedBatches.incrementAndGet();
            if (completed < progress.totalBatches) {
                return;
            }

            if (!progress.bossSpawned.get() && session.mobsPerWave > 0) {
                spawnFallbackBoss(session, store, waveCenter, waveLevelRange, npcPlugin, hasBossPool);
            }

            session.playerRef.sendMessage(Message.raw("[Wave] --------------------------------").color("#4f5f78"));
            session.playerRef.sendMessage(
                    Message.raw(Objects.requireNonNull(String.format(Locale.ROOT,
                            "[Wave] %s Rank | Wave %d/%d | Spawned %d/%d",
                            session.rankTier.letter(),
                            waveNumber,
                            session.totalWaves,
                            progress.spawned.get(),
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

    private static int resolveSpawnBatchSize(@Nonnull GateRankTier rankTier, int mobsPerWave) {
        int clampedMobs = Math.max(1, mobsPerWave);
        if (rankTier == GateRankTier.E || clampedMobs <= LARGE_WAVE_STAGGER_THRESHOLD) {
            return clampedMobs;
        }

        return switch (rankTier) {
            case S, A -> 3;
            case B, C -> 4;
            case D -> 5;
            case E -> clampedMobs;
        };
    }

    private static int resolveSpawnBatchDelaySeconds(@Nonnull GateRankTier rankTier, int mobsPerWave) {
        if (rankTier == GateRankTier.E || mobsPerWave <= LARGE_WAVE_STAGGER_THRESHOLD) {
            return 0;
        }

        return switch (rankTier) {
            case S, A, B, C -> 2;
            case D -> 1;
            case E -> 0;
        };
    }

    private static void spawnFallbackBoss(@Nonnull ActiveWaveSession session,
                                 @Nonnull Store<EntityStore> store,
                                 @Nonnull Vector3d waveCenter,
                                 @Nonnull LevelRange waveLevelRange,
                                 @Nonnull NPCPlugin npcPlugin,
                                 boolean useConfigBossPool) {
        applyBossLevelOverride(session, waveLevelRange.bossLevel);

        Vector3d spawnPosition = findDryLandSpawnPosition(session.world, waveCenter, session.radius,
                WAVE_DRY_LAND_ATTEMPTS_FOR_BOSS);
        if (spawnPosition == null) {
            return;
        }
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
        session.activeWaveMobNames.put(bossRef, bossRole);
        session.playerRef.sendMessage(
                Message.raw(String.format(Locale.ROOT, "[Wave] Boss fallback promoted: %s (Lv %d)", bossRole, waveLevelRange.bossLevel))
                        .color("#f3b37a")
        );
    }

    @Nullable
    private static Vector3d findDryLandSpawnPosition(@Nonnull World world,
                                                      @Nonnull Vector3d waveCenter,
                                                      double radius,
                                                      int attempts) {
        int maxAttempts = Math.max(1, attempts);
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            double angle = Math.random() * Math.PI * 2.0D;
            double distance = (0.35D + Math.random() * 0.65D) * Math.max(1.0D, radius);
            double x = waveCenter.x + Math.cos(angle) * distance;
            double z = waveCenter.z + Math.sin(angle) * distance;
            double y = NPCPhysicsMath.heightOverGround(world, x, z);
            if (y < 0.0D) {
                continue;
            }
            if (!isDryLandSpawnLocation(world, x, y, z)) {
                continue;
            }
            return new Vector3d(x, y, z);
        }
        return null;
    }

    private static boolean isDryLandSpawnLocation(@Nonnull World world, double x, double y, double z) {
        int blockX = (int) Math.floor(x);
        int blockZ = (int) Math.floor(z);
        int blockY = Math.max(WORLD_MIN_Y + 1, Math.min(WORLD_MAX_Y - 1, (int) Math.floor(y)));

        WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(blockX, blockZ));
        if (chunk == null) {
            return false;
        }

        int support = chunk.getBlock(blockX, blockY - 1, blockZ);
        int feet = chunk.getBlock(blockX, blockY, blockZ);
        int head = chunk.getBlock(blockX, blockY + 1, blockZ);

        if (support == AIR_BLOCK_ID) {
            return false;
        }

        return !isLikelyLiquidBlock(support)
                && !isLikelyLiquidBlock(feet)
                && !isLikelyLiquidBlock(head);
    }

    private static boolean isLikelyLiquidBlock(int blockIntId) {
        BlockType blockType = BlockType.getAssetMap().getAsset(blockIntId);
        if (blockType == null || blockType.getId() == null) {
            return false;
        }

        String id = blockType.getId().toLowerCase(Locale.ROOT);
        return id.contains("water")
                || id.contains("ocean")
                || id.contains("river")
                || id.contains("lava")
                || id.contains("liquid");
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

            if (hasWaveSessionExpired(active)) {
                announceWaveFailureDueToTimeout(active);
                cleanupSession(playerUuid);
                return;
            }

            int aliveBefore = active.lastAliveMobCount;
            if (!isWaveCleared(active)) {
                int aliveAfter = active.activeWaveMobRefs.size();
                long now = System.currentTimeMillis();
                if (aliveAfter < aliveBefore) {
                    active.lastKillAtEpochMillis = now;
                    active.lastNoKillHintAtEpochMillis = -1L;
                }
                active.lastAliveMobCount = aliveAfter;
                maybeSendNoKillMobCoordinatesHint(active, now);
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
        Map<Ref<EntityStore>, String> aliveMobNames = new IdentityHashMap<>();
        for (Ref<EntityStore> ref : session.activeWaveMobRefs) {
            if (ref != null && ref.isValid()) {
                stillAlive.add(ref);
                String mobName = session.activeWaveMobNames.get(ref);
                if (mobName != null) {
                    aliveMobNames.put(ref, mobName);
                }
            }
        }
        session.activeWaveMobRefs.clear();
        session.activeWaveMobRefs.addAll(stillAlive);
        session.activeWaveMobNames.clear();
        session.activeWaveMobNames.putAll(aliveMobNames);
        return session.activeWaveMobRefs.isEmpty();
    }

    private static void maybeSendNoKillMobCoordinatesHint(@Nonnull ActiveWaveSession session, long nowMillis) {
        if (session.activeWaveMobRefs.isEmpty()) {
            return;
        }
        if (session.lastKillAtEpochMillis <= 0L) {
            session.lastKillAtEpochMillis = nowMillis;
            return;
        }
        if (nowMillis - session.lastKillAtEpochMillis < NO_KILL_HINT_INTERVAL_MS) {
            return;
        }
        if (session.lastNoKillHintAtEpochMillis > 0L
                && nowMillis - session.lastNoKillHintAtEpochMillis < NO_KILL_HINT_INTERVAL_MS) {
            return;
        }

        List<MobCoordinateHintLine> hintLines = buildMobCoordinateHintLines(session);
        if (hintLines.isEmpty()) {
            return;
        }

        List<PlayerRef> nearbyPlayers = resolveNearbyWavePlayers(session);
        if (nearbyPlayers.isEmpty()) {
            return;
        }

        Message header = Message.raw(String.format(Locale.ROOT,
                "[Wave] No kills for %ds. Remaining mobs:",
                NO_KILL_HINT_INTERVAL_MS / 1000L)).color("#ffd27a");

        for (PlayerRef nearby : nearbyPlayers) {
            nearby.sendMessage(header);
            int index = 1;
            for (MobCoordinateHintLine line : hintLines) {
                nearby.sendMessage(Message.raw(String.format(Locale.ROOT,
                        "[Wave] %d. %s @ (%d, %d, %d)",
                        index++,
                        line.mobName,
                        line.x,
                        line.y,
                        line.z)).color("#d9f0ff"));
            }
        }

        session.lastNoKillHintAtEpochMillis = nowMillis;
    }

    @Nonnull
    private static List<MobCoordinateHintLine> buildMobCoordinateHintLines(@Nonnull ActiveWaveSession session) {
        List<MobCoordinateHintLine> lines = new ArrayList<>();
        for (Ref<EntityStore> mobRef : session.activeWaveMobRefs) {
            if (mobRef == null || !mobRef.isValid()) {
                continue;
            }
            Store<EntityStore> store = mobRef.getStore();
            if (store == null) {
                continue;
            }
            TransformComponent transform = store.getComponent(mobRef,
                    Objects.requireNonNull(TransformComponent.getComponentType()));
            if (transform == null) {
                continue;
            }
            Vector3d pos = transform.getPosition();
            if (pos == null) {
                continue;
            }

            String mobName = session.activeWaveMobNames.getOrDefault(mobRef, "Unknown Mob");
            lines.add(new MobCoordinateHintLine(
                    mobName,
                    (int) Math.floor(pos.x),
                    (int) Math.floor(pos.y),
                    (int) Math.floor(pos.z)));
            if (lines.size() >= NO_KILL_HINT_MAX_LISTED_MOBS) {
                break;
            }
        }
        return lines;
    }

    @Nonnull
    private static List<PlayerRef> resolveNearbyWavePlayers(@Nonnull ActiveWaveSession session) {
        Universe universe = Universe.get();
        if (universe == null) {
            return List.of();
        }

        Vector3d center = resolveWaveHintCenter(session);
        if (center == null) {
            return List.of();
        }

        List<PlayerRef> nearby = new ArrayList<>();
        double radiusSquared = NO_KILL_HINT_NEARBY_RADIUS * NO_KILL_HINT_NEARBY_RADIUS;
        for (PlayerRef player : universe.getPlayers()) {
            Ref<EntityStore> playerEntityRef = player.getReference();
            if (playerEntityRef == null || !playerEntityRef.isValid()) {
                continue;
            }

            Store<EntityStore> store = playerEntityRef.getStore();
            if (store == null) {
                continue;
            }

            World world = store.getExternalData().getWorld();
            if (world == null || !session.worldName.equals(world.getName())) {
                continue;
            }

            TransformComponent transform = store.getComponent(playerEntityRef,
                    Objects.requireNonNull(TransformComponent.getComponentType()));
            if (transform == null) {
                continue;
            }

            Vector3d playerPos = transform.getPosition();
            if (playerPos == null) {
                continue;
            }

            if (distanceSquared(center, playerPos) <= radiusSquared) {
                nearby.add(player);
            }
        }
        return nearby;
    }

    @Nullable
    private static Vector3d resolveWaveHintCenter(@Nonnull ActiveWaveSession session) {
        if (session.waveCenterPosition != null) {
            return session.waveCenterPosition;
        }
        if (session.wavePortalPlacement != null) {
            return new Vector3d(session.wavePortalPlacement.x, session.wavePortalPlacement.y, session.wavePortalPlacement.z);
        }

        Ref<EntityStore> ref = session.playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        if (store == null) {
            return null;
        }
        TransformComponent transform = store.getComponent(ref, Objects.requireNonNull(TransformComponent.getComponentType()));
        return transform == null ? null : transform.getPosition();
    }

    private static double distanceSquared(@Nonnull Vector3d a, @Nonnull Vector3d b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
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
        session.activeWaveMobNames.clear();
        session.lastAliveMobCount = 0;
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
        int[] r = NaturalPortalGateManager.resolveLevelRangeForRank(playerRef, rankTier);
        return new LevelRange(r[0], r[1], r[2]);
    }

    private static int resolveMobsPerWave(@Nonnull GateRankTier rankTier) {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return DEFAULT_MOBS_PER_WAVE;
        }
        return Math.max(1, manager.getDungeonWaveMobCountForRank(rankTier));
    }

    private static int resolveWaveIntervalSeconds(@Nonnull GateRankTier rankTier) {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return DEFAULT_INTERVAL_SECONDS;
        }
        return Math.max(1, manager.getDungeonWaveIntervalSecondsForRank(rankTier));
    }

    @Nullable
    private static WavePoolConfig loadWaveConfig(@Nonnull GateRankTier rankTier) {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return null;
        }
        return manager.loadWavePoolConfig(rankTier);
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
            if (!isWavePortalPlacementStillPresent(session.world, session.wavePortalPlacement)) {
                untrackWavePortalPlacement(session.wavePortalPlacement.placementUuid);
                session.wavePortalPlacement = null;
            }
            return;
        }

        Vector3d portalCenter = session.waveCenterPosition == null ? playerPos : session.waveCenterPosition;
        WavePortalPlacement placement = createWavePortalPlacement(session.world, portalCenter, session.rankTier);
        if (placement != null) {
            session.wavePortalPlacement = placement;
        }
    }

    @Nullable
    private static WavePortalPlacement createWavePortalPlacement(@Nonnull World world,
                                                                 @Nullable Vector3d center,
                                                                 @Nonnull GateRankTier rankTier) {
        if (center == null) {
            return null;
        }

        String portalBlockId = resolveWavePortalBlockId(rankTier);
        int portalBlockIntId = BlockType.getAssetMap().getIndex(portalBlockId);
        if (portalBlockIntId == Integer.MIN_VALUE) {
            return null;
        }

        int baseX = (int) Math.floor(center.x);
        int baseZ = (int) Math.floor(center.z);
        int preferredY = Math.max(
            WORLD_MIN_Y + 1,
            Math.min(
                WORLD_MAX_Y - 1,
                (int) Math.floor(center.y) + resolveWavePortalVerticalOffset(rankTier)));
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();

        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(baseX, baseZ));
        if (chunk == null) {
            return null;
        }

        int maxY = Math.min(WORLD_MAX_Y - 1, preferredY + WAVE_PORTAL_SEARCH_HEIGHT);
        for (int y = preferredY; y <= maxY; y++) {
            int existing = chunk.getBlock(baseX, y, baseZ);
            if (isAnyWavePortalBlockIntId(existing)) {
                WavePortalPlacement tracked = findTrackedWavePortalAt(worldUuid, baseX, y, baseZ);
                if (tracked != null) {
                    return tracked;
                }

                WavePortalPlacement recovered = new WavePortalPlacement(
                        UUID.randomUUID(),
                        worldUuid,
                        baseX,
                        y,
                        baseZ,
                        existing,
                        resolveWavePortalBlockIdFromIntId(existing, portalBlockId));
                trackWavePortalPlacement(recovered);
                return recovered;
            }

        }

        int placementY = resolveWavePortalPlacementY(chunk, baseX, preferredY, baseZ, maxY);
        if (placementY < 0) {
            return null;
        }

        int existing = chunk.getBlock(baseX, placementY, baseZ);
        if (existing != AIR_BLOCK_ID && !isAnyWavePortalBlockIntId(existing)) {
            return null;
        }

        if (isAnyWavePortalBlockIntId(existing)) {
            WavePortalPlacement tracked = findTrackedWavePortalAt(worldUuid, baseX, placementY, baseZ);
            if (tracked != null) {
                return tracked;
            }

            WavePortalPlacement recovered = new WavePortalPlacement(
                    UUID.randomUUID(),
                    worldUuid,
                    baseX,
                    placementY,
                    baseZ,
                    existing,
                    resolveWavePortalBlockIdFromIntId(existing, portalBlockId));
            trackWavePortalPlacement(recovered);
            return recovered;
        }

        chunk.setBlock(baseX, placementY, baseZ, portalBlockIntId);
        chunk.markNeedsSaving();
        requestWavePortalChunkRefresh(world, baseX, baseZ);
        WavePortalPlacement placement = new WavePortalPlacement(
                UUID.randomUUID(),
                worldUuid,
                baseX,
                placementY,
                baseZ,
                portalBlockIntId,
                portalBlockId);
        trackWavePortalPlacement(placement);
        return placement;

    }

    private static int resolveWavePortalPlacementY(@Nonnull WorldChunk chunk,
                                                   int x,
                                                   int preferredY,
                                                   int z,
                                                   int maxY) {
        int startY = Math.max(WORLD_MIN_Y + 1, Math.min(WORLD_MAX_Y - 1, preferredY));
        for (int y = startY; y <= maxY; y++) {
            int placeBlock = chunk.getBlock(x, y, z);
            if (placeBlock != AIR_BLOCK_ID && !isAnyWavePortalBlockIntId(placeBlock)) {
                continue;
            }

            return y;
        }

        return -1;
    }

    private static int resolveWavePortalVerticalOffset(@Nonnull GateRankTier rankTier) {
        return switch (rankTier) {
            case E -> 12;
            case D -> 13;
            case C -> 14;
            case B -> 15;
            case A -> 16;
            case S -> 20;
        };
    }

    @Nullable
    private static Vector3d resolveWaveCenterFromPortalPlacement(@Nullable WavePortalPlacement placement,
                                                                 @Nonnull GateRankTier rankTier) {
        if (placement == null) {
            return null;
        }

        return new Vector3d(
                placement.x,
                placement.y - resolveWavePortalVerticalOffset(rankTier),
                placement.z);
    }

    private static boolean isWavePortalPlacementStillPresent(@Nonnull World world,
                                                             @Nonnull WavePortalPlacement placement) {
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(placement.x, placement.z));
        if (chunk == null) {
            return true;
        }

        int currentBlock = chunk.getBlock(placement.x, placement.y, placement.z);
        int persistedBlockId = BlockType.getAssetMap().getIndex(placement.blockId);
        return currentBlock == placement.blockIntId
                || (persistedBlockId != Integer.MIN_VALUE && currentBlock == persistedBlockId)
                || isAnyWavePortalBlockIntId(currentBlock);
    }

    private static boolean cleanupWavePortalVisual(@Nonnull ActiveWaveSession session) {
        WavePortalPlacement placement = session.wavePortalPlacement;
        if (placement == null) {
            return true;
        }

        boolean removed = clearPortalPlacementFromLoadedChunk(session.world, placement);
        if (!removed) {
            return false;
        }

        untrackWavePortalPlacement(placement.placementUuid);
        session.wavePortalPlacement = null;
        return true;
    }

    private static void cleanupWavePortalVisualWithRetry(@Nonnull ActiveWaveSession session, int attempt) {
        boolean removed = cleanupWavePortalVisual(session);
        if (removed) {
            return;
        }

        if (attempt >= WAVE_PORTAL_CLEANUP_MAX_RETRIES) {
            WavePortalPlacement placement = session.wavePortalPlacement;
            session.wavePortalPlacement = null;
            if (placement != null) {
                requestPortalPlacementClearWithChunkLoad(session.world, placement);
                untrackWavePortalPlacement(placement.placementUuid);
            }
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

    private static boolean isAnyWavePortalBlockIntId(int blockIntId) {
        if (blockIntId == Integer.MIN_VALUE || blockIntId == AIR_BLOCK_ID) {
            return false;
        }

        if (blockIntId == BlockType.getAssetMap().getIndex(WAVE_PORTAL_BLOCK_BASE_ID)) {
            return true;
        }

        for (GateRankTier tier : GateRankTier.values()) {
            String candidate = WAVE_PORTAL_BLOCK_BASE_ID + tier.blockIdSuffix();
            if (blockIntId == BlockType.getAssetMap().getIndex(candidate)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static String resolveWavePortalBlockIdFromIntId(int blockIntId, @Nonnull String fallback) {
        if (blockIntId == BlockType.getAssetMap().getIndex(WAVE_PORTAL_BLOCK_BASE_ID)) {
            return WAVE_PORTAL_BLOCK_BASE_ID;
        }
        for (GateRankTier tier : GateRankTier.values()) {
            String candidate = WAVE_PORTAL_BLOCK_BASE_ID + tier.blockIdSuffix();
            if (blockIntId == BlockType.getAssetMap().getIndex(candidate)) {
                return candidate;
            }
        }
        return fallback;
    }

    @Nullable
    private static WavePortalPlacement findTrackedWavePortalAt(@Nullable UUID worldUuid, int x, int y, int z) {
        if (worldUuid == null) {
            return null;
        }
        for (WavePortalPlacement placement : TRACKED_WAVE_PORTALS.values()) {
            if (placement == null || placement.worldUuid == null) {
                continue;
            }
            if (!worldUuid.equals(placement.worldUuid)) {
                continue;
            }
            if (placement.x == x && placement.y == y && placement.z == z) {
                return placement;
            }
        }
        return null;
    }

    private static int clampDynamicLevel(int level) {
        return Math.max(DYNAMIC_MIN_LEVEL, Math.min(DYNAMIC_MAX_LEVEL, level));
    }

    private static boolean hasWaveSessionExpired(@Nonnull ActiveWaveSession session) {
        if (session.expiryAtEpochMillis <= 0L) {
            return false;
        }
        return System.currentTimeMillis() >= session.expiryAtEpochMillis;
    }

    private static long resolveWaveSessionExpiryAtMillis(@Nullable String linkedGateId) {
        long now = System.currentTimeMillis();
        int expiryMinutes = DEFAULT_WAVE_EXPIRY_MINUTES;

        if (linkedGateId != null) {
            AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
            if (manager != null) {
                int gateDuration = manager.getDungeonDurationMinutes();
                if (gateDuration < 0) {
                    return -1L;
                }
                expiryMinutes = Math.max(1, gateDuration);
            }
        }

        return now + TimeUnit.MINUTES.toMillis(Math.max(1, expiryMinutes));
    }

    private static void announceWaveFailureDueToTimeout(@Nonnull ActiveWaveSession session) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        String rankLetter = session.rankTier.letter();
        String reason = session.linkedGateId == null
                ? "The dungeon break was not closed before the time limit."
                : "The linked dungeon gate duration expired before the break was closed.";

        showTitleToAllPlayers(
                Message.raw("DUNGEON BREAK FAILED").color("#ff5f5f"),
                Message.raw(String.format(Locale.ROOT, "%s-Rank breach remained unstable.", rankLetter)).color("#ffd6d6"),
                true
        );

        universe.sendMessage(Message.join(
                Message.raw("[DUNGEON BREAK] ").color("#ff5f5f"),
                Message.raw(String.format(Locale.ROOT, "%s-Rank failure: ", rankLetter)).color("#ffd6d6"),
                Message.raw(reason).color("#ffffff")
        ));
    }

    /**
     * Called when a player naturally breaks a block. If the block is a wave portal anchor,
     * the placement tracking is removed and the chunk is refreshed for all clients so the
     * particle system detaches immediately instead of lingering until a chunk reload.
     */
    public static void handleNaturalWavePortalBlockBreak(@Nonnull World world,
                                                         @Nonnull Vector3i blockPos,
                                                         @Nonnull String blockTypeId) {
        if (!blockTypeId.startsWith(WAVE_PORTAL_BLOCK_BASE_ID)) {
            return;
        }

        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        int x = blockPos.x, y = blockPos.y, z = blockPos.z;

        WavePortalPlacement tracked = findTrackedWavePortalAt(worldUuid, x, y, z);
        if (tracked != null) {
            untrackWavePortalPlacement(tracked.placementUuid);
        }

        // Defer the chunk refresh to the next world tick so clients reload after the block
        // has actually been set to air by the game engine.
        world.execute(() -> requestWavePortalChunkRefresh(world, x, z));
    }

    public static int clearWavePortalVisualsNearPlayer(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return 0;
        }

        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return 0;
        }

        var transform = playerRef.getTransform();
        if (transform == null) {
            return 0;
        }

        Vector3d position = transform.getPosition();
        int baseX = (int) Math.floor(position.x);
        int baseY = (int) Math.floor(position.y);
        int baseZ = (int) Math.floor(position.z);
        int minY = Math.max(WORLD_MIN_Y, baseY - WAVE_PORTAL_PURGE_VERTICAL_BELOW);
        int maxY = Math.min(WORLD_MAX_Y, baseY + WAVE_PORTAL_PURGE_VERTICAL_ABOVE);

        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        int removed = 0;

        for (int x = baseX - WAVE_PORTAL_PURGE_RADIUS; x <= baseX + WAVE_PORTAL_PURGE_RADIUS; x++) {
            for (int z = baseZ - WAVE_PORTAL_PURGE_RADIUS; z <= baseZ + WAVE_PORTAL_PURGE_RADIUS; z++) {
                WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    continue;
                }

                boolean chunkDirty = false;
                for (int y = minY; y <= maxY; y++) {
                    int block = chunk.getBlock(x, y, z);
                    if (!isAnyWavePortalBlockIntId(block)) {
                        continue;
                    }

                    clearWavePortalBlock(world, chunk, x, y, z);
                    chunkDirty = true;
                    removed++;

                    WavePortalPlacement tracked = findTrackedWavePortalAt(worldUuid, x, y, z);
                    if (tracked != null) {
                        untrackWavePortalPlacement(tracked.placementUuid);
                    }
                }
                if (chunkDirty) {
                    chunk.markNeedsSaving();
                    requestWavePortalChunkRefresh(world, x, z);
                }
            }
        }

        Set<UUID> pendingPreviewOwners = Set.copyOf(PENDING_NATURAL_PREVIEW_PORTALS.keySet());
        for (UUID pendingOwner : pendingPreviewOwners) {
            WavePortalPlacement pendingPlacement = PENDING_NATURAL_PREVIEW_PORTALS.get(pendingOwner);
            if (pendingPlacement == null || pendingPlacement.worldUuid == null || worldUuid == null) {
                continue;
            }
            if (!worldUuid.equals(pendingPlacement.worldUuid)) {
                continue;
            }
            if (Math.abs(pendingPlacement.x - baseX) > WAVE_PORTAL_PURGE_RADIUS
                    || Math.abs(pendingPlacement.z - baseZ) > WAVE_PORTAL_PURGE_RADIUS
                    || pendingPlacement.y < minY
                    || pendingPlacement.y > maxY) {
                continue;
            }

            PENDING_NATURAL_PREVIEW_PORTALS.remove(pendingOwner);
            untrackWavePortalPlacement(pendingPlacement.placementUuid);
        }

        for (ActiveWaveSession session : ACTIVE_SESSIONS.values()) {
            if (session == null || session.wavePortalPlacement == null) {
                continue;
            }
            WavePortalPlacement sessionPlacement = session.wavePortalPlacement;
            if (sessionPlacement.worldUuid == null || worldUuid == null || !worldUuid.equals(sessionPlacement.worldUuid)) {
                continue;
            }
            if (Math.abs(sessionPlacement.x - baseX) > WAVE_PORTAL_PURGE_RADIUS
                    || Math.abs(sessionPlacement.z - baseZ) > WAVE_PORTAL_PURGE_RADIUS
                    || sessionPlacement.y < minY
                    || sessionPlacement.y > maxY) {
                continue;
            }
            session.wavePortalPlacement = null;
            untrackWavePortalPlacement(sessionPlacement.placementUuid);
        }

        return removed;
    }

    public static int clearWavePortalVisualsInPlayerWorld(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return 0;
        }

        Store<EntityStore> store = ref.getStore();
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return 0;
        }

        int removedFromTracked = clearWavePortalVisualsByTrackedDataInWorld(world);
        if (removedFromTracked > 0) {
            return removedFromTracked;
        }

        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        int removed = 0;

        for (Long chunkIndexObj : world.getChunkStore().getChunkIndexes()) {
            if (chunkIndexObj == null) {
                continue;
            }

            long chunkIndex = chunkIndexObj;
            WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
            if (chunk == null) {
                continue;
            }

            int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
            int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
            int baseX = chunkX << 5;
            int baseZ = chunkZ << 5;
            boolean chunkDirty = false;

            for (int localX = 0; localX < 32; localX++) {
                int x = baseX + localX;
                for (int localZ = 0; localZ < 32; localZ++) {
                    int z = baseZ + localZ;
                    for (int y = WORLD_MIN_Y; y <= WORLD_MAX_Y; y++) {
                        int block = chunk.getBlock(x, y, z);
                        if (!isAnyWavePortalBlockIntId(block)) {
                            continue;
                        }

                        clearWavePortalBlock(world, chunk, x, y, z);
                        chunkDirty = true;
                        removed++;

                        WavePortalPlacement tracked = findTrackedWavePortalAt(worldUuid, x, y, z);
                        if (tracked != null) {
                            untrackWavePortalPlacement(tracked.placementUuid);
                        }
                    }
                }
            }

            if (chunkDirty) {
                chunk.markNeedsSaving();
                requestWavePortalChunkRefresh(world, baseX, baseZ);
            }
        }

        if (worldUuid != null) {
            for (UUID pendingOwner : Set.copyOf(PENDING_NATURAL_PREVIEW_PORTALS.keySet())) {
                WavePortalPlacement pending = PENDING_NATURAL_PREVIEW_PORTALS.get(pendingOwner);
                if (pending == null || pending.worldUuid == null || !worldUuid.equals(pending.worldUuid)) {
                    continue;
                }
                PENDING_NATURAL_PREVIEW_PORTALS.remove(pendingOwner);
                untrackWavePortalPlacement(pending.placementUuid);
            }

            for (ActiveWaveSession session : ACTIVE_SESSIONS.values()) {
                if (session == null || session.wavePortalPlacement == null) {
                    continue;
                }
                WavePortalPlacement sessionPlacement = session.wavePortalPlacement;
                if (sessionPlacement.worldUuid == null || !worldUuid.equals(sessionPlacement.worldUuid)) {
                    continue;
                }
                session.wavePortalPlacement = null;
                untrackWavePortalPlacement(sessionPlacement.placementUuid);
            }
        }

        return removed;
    }

    public static int clearWavePortalVisualsByTrackedDataInWorld(@Nonnull World world) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return 0;
        }

        int removed = 0;

        for (WavePortalPlacement placement : new ArrayList<>(TRACKED_WAVE_PORTALS.values())) {
            if (placement == null || placement.worldUuid == null || !worldUuid.equals(placement.worldUuid)) {
                continue;
            }

            removed += clearTrackedWavePortalPlacement(world, placement);
        }

        for (UUID pendingOwner : Set.copyOf(PENDING_NATURAL_PREVIEW_PORTALS.keySet())) {
            WavePortalPlacement pending = PENDING_NATURAL_PREVIEW_PORTALS.get(pendingOwner);
            if (pending == null || pending.worldUuid == null || !worldUuid.equals(pending.worldUuid)) {
                continue;
            }

            removed += clearTrackedWavePortalPlacement(world, pending);
            PENDING_NATURAL_PREVIEW_PORTALS.remove(pendingOwner);
            PENDING_NATURAL_SOURCES.remove(pendingOwner);
            PENDING_NATURAL_RANKS.remove(pendingOwner);
            PENDING_NATURAL_OPENS_AT_EPOCH_MILLIS.remove(pendingOwner);
            ScheduledFuture<?> countdown = PENDING_NATURAL_COUNTDOWNS.remove(pendingOwner);
            if (countdown != null) {
                countdown.cancel(false);
            }
            cancelScheduledFutures(PENDING_NATURAL_PRESTART_COUNTDOWNS.remove(pendingOwner));
        }

        for (ActiveWaveSession session : ACTIVE_SESSIONS.values()) {
            if (session == null || session.wavePortalPlacement == null) {
                continue;
            }
            WavePortalPlacement sessionPlacement = session.wavePortalPlacement;
            if (sessionPlacement.worldUuid == null || !worldUuid.equals(sessionPlacement.worldUuid)) {
                continue;
            }

            removed += clearTrackedWavePortalPlacement(world, sessionPlacement);
            session.wavePortalPlacement = null;
        }

        return removed;
    }

    private static int clearTrackedWavePortalPlacement(@Nonnull World world,
                                                       @Nonnull WavePortalPlacement placement) {
        WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(placement.x, placement.z));
        if (chunk == null) {
            requestTrackedWavePortalClearWithChunkLoad(world, placement);
            return 0;
        }

        int removed = 0;
        int currentBlock = chunk.getBlock(placement.x, placement.y, placement.z);
        if (isAnyWavePortalBlockIntId(currentBlock)) {
            clearWavePortalBlock(world, chunk, placement.x, placement.y, placement.z);
            chunk.markNeedsSaving();
            requestWavePortalChunkRefresh(world, placement.x, placement.z);
            removed = 1;
        }

        untrackWavePortalPlacement(placement.placementUuid);
        return removed;
    }

    private static void requestTrackedWavePortalClearWithChunkLoad(@Nonnull World world,
                                                                    @Nonnull WavePortalPlacement placement) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(placement.x, placement.z);
        world.getNonTickingChunkAsync(chunkIndex).whenComplete((loadedChunk, throwable) ->
                world.execute(() -> {
                    WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
                    if (chunk == null) {
                        return;
                    }

                    int currentBlock = chunk.getBlock(placement.x, placement.y, placement.z);
                    if (isAnyWavePortalBlockIntId(currentBlock)) {
                        clearWavePortalBlock(world, chunk, placement.x, placement.y, placement.z);
                        chunk.markNeedsSaving();
                        requestWavePortalChunkRefresh(world, placement.x, placement.z);
                    }

                    untrackWavePortalPlacement(placement.placementUuid);
                }));
    }

    private static int resolveNaturalWaveOpenDelay(@Nonnull GateRankTier rankTier) {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return switch (rankTier) {
                case S -> 10;
                case A -> 5;
                default -> 1;
            };
        }
        return Math.max(1, manager.getDungeonNaturalWaveOpenDelayMinutesForRank(rankTier));
    }

    private static void announceNaturalWaveBreak(@Nonnull GateRankTier rankTier, int delayMinutes) {
        announceNaturalWaveBreak(rankTier, delayMinutes, null);
    }

    private static void announceNaturalWaveBreak(@Nonnull GateRankTier rankTier,
                                                 int delayMinutes,
                                                 @Nullable WavePortalPlacement previewPortal) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        String timeLabel = delayMinutes == 1 ? "1 minute" : delayMinutes + " minutes";
        showTitleToAllPlayers(
                Message.raw("DUNGEON BREAK").color(rankTier.color().hex()),
                Message.raw(String.format(Locale.ROOT, "%s-Rank breach in %s", rankTier.letter(), timeLabel)).color("#d0e8ff"),
                true
        );

        universe.sendMessage(Message.join(
                Message.raw("[DUNGEON BREAK] ").color(rankTier.color().hex()),
                Message.raw(String.format(Locale.ROOT,
                        "%s-Rank breach detected. Wave opens in %s.",
                        rankTier.letter(),
                        timeLabel)).color("#ffffff")
        ));
    }

    private static void announceNaturalWaveOpen(@Nonnull GateRankTier rankTier) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        playSoundToAllPlayers(WAVE_GATE_SPAWN_SOUND_ID);
        if (rankTier == GateRankTier.S) {
            universe.sendMessage(Message.join(
                    Message.raw("[WORLD DISASTER] ").color("#ff5a36"),
                    Message.raw("The Sovereign Rift has opened. Survive the storm.").color("#ffd7cf")
            ));
            return;
        }
        universe.sendMessage(Message.join(
                Message.raw("[DUNGEON BREAK] ").color(rankTier.color().hex()),
                Message.raw(String.format(Locale.ROOT, "%s-Rank breach opened. Slay the wave commander to stabilize the rift.", rankTier.letter())).color("#ffffff")
        ));
    }

    private static void announceLinkedGateBreak(@Nonnull GateRankTier rankTier, int delayMinutes) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        String timeLabel = delayMinutes == 1 ? "1 minute" : delayMinutes + " minutes";
        showTitleToAllPlayers(
                Message.raw("RIFT LOCK").color(rankTier.color().hex()),
                Message.raw(String.format(Locale.ROOT, "%s-Rank linked wave in %s", rankTier.letter(), timeLabel)).color("#d0e8ff"),
                true
        );
        universe.sendMessage(Message.join(
            Message.raw("[RIFT LOCK] ").color(rankTier.color().hex()),
            Message.raw(String.format(Locale.ROOT,
                "%s-Rank gate resonance detected. Entry is sealed.",
                rankTier.letter())).color("#ffffff"),
            Message.raw("\n"),
            Message.raw("[RIFT LOCK] ").color(rankTier.color().hex()),
            Message.raw(String.format(Locale.ROOT,
                "Linked wave begins in %s. Clear it to unseal the gate.",
                timeLabel)).color("#d0e8ff")
        ));
    }

    private static void announceLinkedGateOpen(@Nonnull GateRankTier rankTier) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        playSoundToAllPlayers(WAVE_GATE_SPAWN_SOUND_ID);
        universe.sendMessage(Message.join(
                Message.raw("[RIFT LOCK] ").color(rankTier.color().hex()),
                Message.raw(String.format(Locale.ROOT,
                        "%s-Rank linked wave is active. Gate remains sealed until all waves are cleared.",
                        rankTier.letter())).color("#ffffff")
        ));
    }

    @Nonnull
    private static List<ScheduledFuture<?>> schedulePrestartCountdown(@Nonnull GateRankTier rankTier,
                                                                       long delaySeconds,
                                                                       boolean linkedGateMode) {
        List<ScheduledFuture<?>> futures = new ArrayList<>();
        List<Integer> markers = resolveCountdownMarkers(rankTier, delaySeconds);
        for (int secondsRemaining : markers) {
            long stepDelay = Math.max(0L, delaySeconds - secondsRemaining);
            ScheduledFuture<?> future = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                Universe universe = Universe.get();
                if (universe != null) {
                    String channel = linkedGateMode ? "RIFT LOCK" : "DUNGEON BREAK";
                    universe.sendMessage(Message.join(
                            Message.raw("[" + channel + "] ").color(rankTier.color().hex()),
                            Message.raw(String.format(Locale.ROOT,
                                    "%s-Rank wave begins in %s",
                                    rankTier.letter(),
                                    formatSeconds(secondsRemaining))).color("#ffe8b5")
                    ));
                }

                if (rankTier == GateRankTier.S && secondsRemaining == 60) {
                    playSoundToAllPlayers(S_WAVE_1_MINUTE_COUNTDOWN_SOUND_ID);
                    return;
                }

                if (rankTier != GateRankTier.S && secondsRemaining == 10) {
                    playSoundToAllPlayers(WAVE_10_SECOND_COUNTDOWN_SOUND_ID);
                }
            }, stepDelay, TimeUnit.SECONDS);
            futures.add(future);
        }
        return futures;
    }

    @Nonnull
    private static List<Integer> resolveCountdownMarkers(@Nonnull GateRankTier rankTier, long delaySeconds) {
        List<Integer> markers = new ArrayList<>();

        if (rankTier == GateRankTier.S) {
            addCountdownMarker(markers, delaySeconds, 300);
            addCountdownMarker(markers, delaySeconds, 60);
        } else if (rankTier == GateRankTier.A) {
            addCountdownMarker(markers, delaySeconds, 60);
        }

        addCountdownMarker(markers, delaySeconds, 30);
        addCountdownMarker(markers, delaySeconds, 10);
        addCountdownMarker(markers, delaySeconds, 5);
        addCountdownMarker(markers, delaySeconds, 4);
        addCountdownMarker(markers, delaySeconds, 3);
        addCountdownMarker(markers, delaySeconds, 2);
        addCountdownMarker(markers, delaySeconds, 1);

        return markers;
    }

    private static void addCountdownMarker(@Nonnull List<Integer> markers, long delaySeconds, int markerSeconds) {
        if (markerSeconds <= 0) {
            return;
        }
        if (markerSeconds > delaySeconds) {
            return;
        }
        if (markers.contains(markerSeconds)) {
            return;
        }
        markers.add(markerSeconds);
    }

    @Nonnull
    private static String formatSeconds(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(Locale.ROOT, "00:%02d", safe);
    }

    private static void cancelScheduledFutures(@Nullable List<ScheduledFuture<?>> futures) {
        if (futures == null || futures.isEmpty()) {
            return;
        }
        for (ScheduledFuture<?> future : futures) {
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private static void clearPendingNaturalPreviewPortal(@Nonnull UUID playerUuid) {
        WavePortalPlacement placement = PENDING_NATURAL_PREVIEW_PORTALS.remove(playerUuid);
        PENDING_NATURAL_RANKS.remove(playerUuid);
        if (placement == null) {
            return;
        }
        clearPortalPlacement(placement);
    }

    @Nonnull
    public static List<TrackedWaveSnapshot> listTrackedStandaloneWaves() {
        Universe universe = Universe.get();
        List<TrackedWaveSnapshot> snapshots = new ArrayList<>();

        for (ActiveWaveSession session : ACTIVE_SESSIONS.values()) {
            if (session == null || session.cancelled.get() || session.linkedGateId != null) {
                continue;
            }

            snapshots.add(buildStandaloneWaveSnapshot(session));
        }

        for (Map.Entry<UUID, WavePortalPlacement> entry : PENDING_NATURAL_PREVIEW_PORTALS.entrySet()) {
            UUID ownerUuid = entry.getKey();
            WavePortalPlacement placement = entry.getValue();
            if (ownerUuid == null || placement == null) {
                continue;
            }

            GateRankTier rankTier = PENDING_NATURAL_RANKS.get(ownerUuid);
            if (rankTier == null) {
                continue;
            }

            WaveStartSource source = PENDING_NATURAL_SOURCES.get(ownerUuid);
            String worldName = resolveWorldName(universe, placement.worldUuid);
            snapshots.add(new TrackedWaveSnapshot(
                    ownerUuid,
                    resolvePlayerName(universe, ownerUuid),
                    rankTier,
                    "pending",
                    source == WaveStartSource.AUTO_NATURAL ? "auto-wave" : "wave",
                    placement.worldUuid,
                    worldName,
                    null,
                    placement.x,
                    placement.y,
                    placement.z));
        }

        snapshots.sort(Comparator
                .comparing((TrackedWaveSnapshot entry) -> entry.stage)
                .thenComparing(entry -> entry.worldName == null ? "" : entry.worldName)
                .thenComparingInt(entry -> entry.y == null ? Integer.MIN_VALUE : entry.y)
                .thenComparingInt(entry -> entry.x == null ? Integer.MIN_VALUE : entry.x)
                .thenComparingInt(entry -> entry.z == null ? Integer.MIN_VALUE : entry.z));
        return snapshots;
    }

    @Nonnull
    public static List<TrackedWaveSnapshot> listTrackedGateWaveCombos() {
        Universe universe = Universe.get();
        List<TrackedWaveSnapshot> snapshots = new ArrayList<>();

        for (Map.Entry<String, LinkedGateWaveState> entry : GATE_WAVE_STATES.entrySet()) {
            String gateId = entry.getKey();
            LinkedGateWaveState state = entry.getValue();
            if (gateId == null || state == null) {
                continue;
            }

            NaturalPortalGateManager.TrackedGateSnapshot gate = NaturalPortalGateManager.findTrackedGate(gateId);
            UUID worldUuid = gate == null ? null : gate.worldUuid();
            String worldName = gate == null ? null : gate.worldName();
            Integer x = gate == null ? null : gate.x();
            Integer y = gate == null ? null : gate.y();
            Integer z = gate == null ? null : gate.z();

            snapshots.add(new TrackedWaveSnapshot(
                    state.ownerUuid,
                    resolvePlayerName(universe, state.ownerUuid),
                    state.rankTier,
                    state.stage,
                    "gate+wave",
                    worldUuid,
                    worldName,
                    gateId,
                    x,
                    y,
                    z));
        }

        snapshots.sort(Comparator
                .comparing((TrackedWaveSnapshot entry) -> entry.stage)
                .thenComparing(entry -> entry.worldName == null ? "" : entry.worldName)
                .thenComparing(entry -> entry.linkedGateId == null ? "" : entry.linkedGateId));
        return snapshots;
    }

    @Nonnull
    private static TrackedWaveSnapshot buildStandaloneWaveSnapshot(@Nonnull ActiveWaveSession session) {
        WavePortalPlacement placement = session.wavePortalPlacement;
        Integer x = placement == null ? null : placement.x;
        Integer y = placement == null ? null : placement.y;
        Integer z = placement == null ? null : placement.z;
        UUID worldUuid = session.world.getWorldConfig() == null ? null : session.world.getWorldConfig().getUuid();
        return new TrackedWaveSnapshot(
                session.playerUuid,
                session.playerRef.getUsername(),
                session.rankTier,
                "active",
                session.startSource == WaveStartSource.AUTO_NATURAL ? "auto-wave" : "wave",
                worldUuid,
                session.worldName,
                null,
                x,
                y,
                z);
    }

    @Nullable
    private static String resolvePlayerName(@Nullable Universe universe, @Nullable UUID ownerUuid) {
        if (universe == null || ownerUuid == null) {
            return null;
        }

        PlayerRef playerRef = universe.getPlayer(ownerUuid);
        return playerRef == null ? null : playerRef.getUsername();
    }

    @Nullable
    private static String resolveWorldName(@Nullable Universe universe, @Nullable UUID worldUuid) {
        if (universe == null || worldUuid == null) {
            return null;
        }

        World world = universe.getWorld(worldUuid);
        return world == null ? null : world.getName();
    }

    private static void shutdownIndividualNaturalWaveSpawner() {
        ScheduledFuture<?> timer = NATURAL_WAVE_TIMER;
        NATURAL_WAVE_TIMER = null;
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private static void scheduleNextIndividualNaturalWaveTick() {
        long delaySeconds = Math.max(1L, resolveNaturalWaveSpawnIntervalSeconds());
        ScheduledFuture<?> future = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                spawnIndependentNaturalWaveTick();
            } finally {
                if (NATURAL_WAVE_TIMER != null) {
                    scheduleNextIndividualNaturalWaveTick();
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
        NATURAL_WAVE_TIMER = future;
    }

    private static void spawnIndependentNaturalWaveTick() {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager != null && !manager.isDungeonGateEnabled()) {
            return;
        }

        int maxConcurrentNaturalWaves = resolveNaturalWaveMaxConcurrentSpawns();
        int activeOrPending = countActiveOrPendingIndependentNaturalWaves();
        if (maxConcurrentNaturalWaves >= 0 && activeOrPending >= maxConcurrentNaturalWaves) {
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        List<PlayerRef> eligiblePlayers = resolveEligiblePlayersForNaturalWave(universe);
        if (eligiblePlayers.isEmpty()) {
            return;
        }

        PlayerRef target = eligiblePlayers.get((int) Math.floor(Math.random() * eligiblePlayers.size()));
        GateRankTier rankTier = NaturalPortalGateManager.rollGateRankTierForPlayer(target);
        startNaturalForPlayerInternal(target, rankTier, WaveStartSource.AUTO_NATURAL);
    }

    private static int countActiveOrPendingIndependentNaturalWaves() {
        int pending = 0;
        for (WaveStartSource source : PENDING_NATURAL_SOURCES.values()) {
            if (source == WaveStartSource.AUTO_NATURAL) {
                pending++;
            }
        }

        int active = 0;
        for (ActiveWaveSession session : ACTIVE_SESSIONS.values()) {
            if (session != null && !session.cancelled.get() && session.startSource == WaveStartSource.AUTO_NATURAL) {
                active++;
            }
        }

        return pending + active;
    }

    @Nonnull
    private static List<PlayerRef> resolveEligiblePlayersForNaturalWave(@Nonnull Universe universe) {
        List<PlayerRef> eligiblePlayers = new ArrayList<>();
        int minLevelRequired = resolveNaturalWaveMinLevelRequired();

        for (PlayerRef player : universe.getPlayers()) {
            if (player == null || !player.isValid()) {
                continue;
            }

            UUID worldUuid = player.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }

            World world = universe.getWorld(worldUuid);
            if (world == null) {
                continue;
            }
            if (!isNaturalWaveWorldAllowed(world)) {
                continue;
            }

            int playerLevel = resolvePlayerLevel(player.getUuid());
            if (playerLevel < minLevelRequired) {
                continue;
            }

            eligiblePlayers.add(player);
        }

        return eligiblePlayers;
    }

    private static boolean isNaturalWaveWorldAllowed(@Nonnull World world) {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return true;
        }

        String worldName = world.getName();
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        for (String allowed : manager.getDungeonPortalWorldWhitelist()) {
            if (worldName.equalsIgnoreCase(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static int resolvePlayerLevel(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return 0;
        }

        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null) {
            return 0;
        }
        return Math.max(0, api.getPlayerLevel(playerUuid));
    }

    private static int resolveNaturalWaveMinLevelRequired() {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return 1;
        }
        return Math.max(1, manager.getDungeonMinLevelRequired());
    }

    private static long resolveNaturalWaveSpawnIntervalSeconds() {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return TimeUnit.MINUTES.toSeconds(15L + (long) Math.floor(Math.random() * 16.0D));
        }

        int minMinutes = Math.max(1, manager.getDungeonNaturalWaveSpawnIntervalMinutesMin());
        int maxMinutes = Math.max(minMinutes, manager.getDungeonNaturalWaveSpawnIntervalMinutesMax());
        int range = maxMinutes - minMinutes;
        int selectedMinutes = minMinutes + (range <= 0 ? 0 : (int) Math.floor(Math.random() * (range + 1)));
        return Math.max(1L, TimeUnit.MINUTES.toSeconds(selectedMinutes));
    }

    private static int resolveNaturalWaveMaxConcurrentSpawns() {
        AddonFilesManager manager = NaturalPortalGateManager.getFilesManager();
        if (manager == null) {
            return DEFAULT_NATURAL_WAVE_MAX_CONCURRENT_SPAWNS;
        }
        return manager.getDungeonNaturalWaveMaxConcurrentSpawns();
    }

    private static void clearPortalPlacement(@Nonnull WavePortalPlacement placement) {
        Universe universe = Universe.get();
        if (universe == null || placement.worldUuid == null) {
            return;
        }
        World world = universe.getWorld(placement.worldUuid);
        if (world == null) {
            return;
        }

        world.execute(() -> {
            boolean removed = clearPortalPlacementFromLoadedChunk(world, placement);
            if (!removed) {
                requestPortalPlacementClearWithChunkLoad(world, placement);
            }
            untrackWavePortalPlacement(placement.placementUuid);
        });
    }

    private static boolean clearPortalPlacementFromLoadedChunk(@Nonnull World world,
                                                               @Nonnull WavePortalPlacement placement) {
        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(placement.x, placement.z));
        if (chunk == null) {
            return false;
        }

        int currentBlock = chunk.getBlock(placement.x, placement.y, placement.z);
        int persistedBlockId = BlockType.getAssetMap().getIndex(placement.blockId);
        if (currentBlock == placement.blockIntId
                || (persistedBlockId != Integer.MIN_VALUE && currentBlock == persistedBlockId)) {
            clearWavePortalBlock(world, chunk, placement.x, placement.y, placement.z);
            chunk.markNeedsSaving();
            requestWavePortalChunkRefresh(world, placement.x, placement.z);
        }
        return true;
    }

    private static void clearWavePortalBlock(@Nonnull World world, @Nonnull WorldChunk chunk, int x, int y, int z) {
        try {
            chunk.breakBlock(x, y, z, WAVE_PORTAL_BREAK_SETTINGS);
        } catch (Exception ignored) {
            chunk.setBlock(x, y, z, AIR_BLOCK_ID);
        }
    }

    private static void requestWavePortalChunkRefresh(@Nonnull World world, int x, int z) {
        try {
            long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
            int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
            int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
            UnloadChunk unloadChunk = new UnloadChunk(chunkX, chunkZ);
            for (PlayerRef playerRef : world.getPlayerRefs()) {
                if (playerRef == null) {
                    continue;
                }

                try {
                    if (!playerRef.getChunkTracker().isLoaded(chunkIndex)) {
                        continue;
                    }

                    playerRef.getPacketHandler().writeNoCache(unloadChunk);
                    playerRef.getChunkTracker().removeForReload(chunkIndex);
                } catch (Exception ignored) {
                    // Fall through to the generic chunk reload path below.
                }
            }

            world.getNotificationHandler().updateChunk(chunkIndex);
        } catch (Exception ignored) {
            // If the runtime cannot push a chunk reload here, block cleanup still succeeds server-side.
        }
    }

    private static void requestPortalPlacementClearWithChunkLoad(@Nonnull World world,
                                                                 @Nonnull WavePortalPlacement placement) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(placement.x, placement.z);
        world.getChunkAsync(chunkIndex).whenComplete((loadedChunk, throwable) ->
                world.execute(() -> clearPortalPlacementFromLoadedChunk(world, placement)));
    }

    private static void initializeWavePortalPersistence() {
        try {
            File dataFolder = PluginManager.MODS_PATH.resolve("EndlessLevelingAddon").toFile();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            wavePortalPersistenceFile = new File(dataFolder, WAVE_PORTAL_PERSISTENCE_FILENAME);
            loadTrackedWavePortalsFromDisk();
        } catch (Exception ignored) {
            // If persistence init fails, in-memory cleanup still works.
        }
    }

    private static void startPersistedWavePortalSweepTask() {
        stopPersistedWavePortalSweepTask();
        persistedWavePortalSweepAttempts.set(0);
        persistedWavePortalSweepFuture = HytaleServer.SCHEDULED_EXECUTOR.scheduleAtFixedRate(
                MobWaveManager::sweepPersistedWavePortals,
                1L,
                PERSISTED_WAVE_PORTAL_SWEEP_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private static void stopPersistedWavePortalSweepTask() {
        ScheduledFuture<?> future = persistedWavePortalSweepFuture;
        persistedWavePortalSweepFuture = null;
        if (future != null) {
            future.cancel(false);
        }
    }

    private static void sweepPersistedWavePortals() {
        if (TRACKED_WAVE_PORTALS.isEmpty()) {
            stopPersistedWavePortalSweepTask();
            return;
        }

        if (persistedWavePortalSweepAttempts.incrementAndGet() > PERSISTED_WAVE_PORTAL_SWEEP_MAX_ATTEMPTS) {
            stopPersistedWavePortalSweepTask();
            persistTrackedWavePortalsToDisk();
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        boolean changed = false;
        for (WavePortalPlacement placement : new ArrayList<>(TRACKED_WAVE_PORTALS.values())) {
            if (placement == null || placement.worldUuid == null) {
                continue;
            }
            World world = universe.getWorld(placement.worldUuid);
            if (world == null) {
                continue;
            }

            clearPortalPlacement(placement);
            changed |= TRACKED_WAVE_PORTALS.remove(placement.placementUuid) != null;
        }

        if (changed) {
            persistTrackedWavePortalsToDisk();
        }
    }

    private static void trackWavePortalPlacement(@Nonnull WavePortalPlacement placement) {
        TRACKED_WAVE_PORTALS.put(placement.placementUuid, placement);
        if (placement.worldUuid != null) {
            ChunkKeepaliveManager.register(
                    "wave-portal:" + placement.placementUuid,
                    placement.worldUuid,
                    ChunkUtil.indexChunkFromBlock(placement.x, placement.z));
        }
        persistTrackedWavePortalsToDisk();
    }

    private static void untrackWavePortalPlacement(@Nonnull UUID placementUuid) {
        ChunkKeepaliveManager.unregister("wave-portal:" + placementUuid);
        if (TRACKED_WAVE_PORTALS.remove(placementUuid) != null) {
            persistTrackedWavePortalsToDisk();
        }
    }

    private static void loadTrackedWavePortalsFromDisk() {
        File file = wavePortalPersistenceFile;
        if (file == null || !file.exists()) {
            return;
        }

        synchronized (WAVE_PORTAL_PERSISTENCE_LOCK) {
            try (FileReader reader = new FileReader(file)) {
                JsonObject root = WAVE_PORTAL_GSON.fromJson(reader, JsonObject.class);
                if (root == null || !root.has("wavePortals")) {
                    return;
                }

                JsonArray entries = root.getAsJsonArray("wavePortals");
                TRACKED_WAVE_PORTALS.clear();
                for (var element : entries) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject entry = element.getAsJsonObject();
                    if (!entry.has("placementUuid") || !entry.has("worldUuid") || !entry.has("x") || !entry.has("y")
                            || !entry.has("z") || !entry.has("blockIntId") || !entry.has("blockId")) {
                        continue;
                    }

                    UUID placementUuid = UUID.fromString(entry.get("placementUuid").getAsString());
                    UUID worldUuid = UUID.fromString(entry.get("worldUuid").getAsString());
                    int x = entry.get("x").getAsInt();
                    int y = entry.get("y").getAsInt();
                    int z = entry.get("z").getAsInt();
                    int blockIntId = entry.get("blockIntId").getAsInt();
                    String blockId = entry.get("blockId").getAsString();

                    WavePortalPlacement placement = new WavePortalPlacement(
                            placementUuid,
                            worldUuid,
                            x,
                            y,
                            z,
                            blockIntId,
                            blockId);
                    TRACKED_WAVE_PORTALS.put(placementUuid, placement);
                }
            } catch (Exception ignored) {
                // Ignore malformed persistence and continue with empty tracking.
            }
        }
    }

    private static void persistTrackedWavePortalsToDisk() {
        File file = wavePortalPersistenceFile;
        if (file == null) {
            return;
        }

        synchronized (WAVE_PORTAL_PERSISTENCE_LOCK) {
            try {
                JsonObject root = new JsonObject();
                JsonArray entries = new JsonArray();
                for (WavePortalPlacement placement : TRACKED_WAVE_PORTALS.values()) {
                    if (placement == null || placement.worldUuid == null) {
                        continue;
                    }
                    JsonObject entry = new JsonObject();
                    entry.addProperty("placementUuid", placement.placementUuid.toString());
                    entry.addProperty("worldUuid", placement.worldUuid.toString());
                    entry.addProperty("x", placement.x);
                    entry.addProperty("y", placement.y);
                    entry.addProperty("z", placement.z);
                    entry.addProperty("blockIntId", placement.blockIntId);
                    entry.addProperty("blockId", placement.blockId);
                    entries.add(entry);
                }
                root.add("wavePortals", entries);
                root.addProperty("lastSaved", System.currentTimeMillis());

                try (FileWriter writer = new FileWriter(file)) {
                    WAVE_PORTAL_GSON.toJson(root, writer);
                }
            } catch (Exception ignored) {
                // Persistence failures should not break gameplay or cleanup paths.
            }
        }
    }

    private static void showTitleToAllPlayers(@Nonnull Message titlePrimary,
                                              @Nonnull Message titleSecondary,
                                              boolean playSound) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            EventTitleUtil.showEventTitleToPlayer(playerRef, titlePrimary, titleSecondary, playSound);
        }
    }

    private static void playSoundToAllPlayers(@Nonnull String soundEventId) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        int soundIndex = resolveSoundIndex(soundEventId);
        if (soundIndex == 0) {
            return;
        }

        for (PlayerRef playerRef : universe.getPlayers()) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }

            UUID worldUuid = playerRef.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }

            World playerWorld = universe.getWorld(worldUuid);
            if (playerWorld == null) {
                continue;
            }

            playerWorld.execute(() -> {
                if (!playerRef.isValid()) {
                    return;
                }
                Ref<EntityStore> playerEntityRef = playerRef.getReference();
                if (playerEntityRef == null || !playerEntityRef.isValid()) {
                    return;
                }

                try {
                    SoundUtil.playSoundEvent2d(
                            playerEntityRef,
                            soundIndex,
                            SoundCategory.SFX,
                            playerEntityRef.getStore());
                } catch (Exception ignored) {
                    // Ignore per-player playback errors; other players should still hear the cue.
                }
            });
        }
    }

    private static int resolveSoundIndex(@Nullable String soundEventId) {
        if (soundEventId == null || soundEventId.isBlank()) {
            return 0;
        }

        int index = SoundEvent.getAssetMap().getIndex(soundEventId);
        return index == Integer.MIN_VALUE ? 0 : index;
    }

    @Nullable
    private static String normalizeGateIdentity(@Nullable String gateIdentity) {
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return null;
        }
        return gateIdentity.startsWith("el_gate:") ? gateIdentity : "el_gate:" + gateIdentity;
    }

    private static void cleanupSession(UUID playerUuid) {
        ActiveWaveSession removed = ACTIVE_SESSIONS.remove(playerUuid);
        if (removed == null) {
            return;
        }

        if (removed.linkedGateId != null) {
            PENDING_LINKED_GATE_COUNTDOWNS.remove(removed.linkedGateId);
            GATE_WAVE_STATES.remove(removed.linkedGateId);
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
                if (session.wavePortalVisualEnabled) {
                    cleanupWavePortalVisualWithRetry(session, 0);
                }
                removeWaveLevelOverride(session);
            });
        } catch (Exception ignored) {
            // Fallback keeps cleanup working even if world dispatch is unavailable.
            forceKillActiveWaveMobs(session);
            if (session.wavePortalVisualEnabled) {
                cleanupWavePortalVisualWithRetry(session, 0);
            }
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
        private final boolean wavePortalVisualEnabled;
        @Nullable
        private final String linkedGateId;
        @Nullable
        private final Vector3d waveCenterPosition;
        @Nonnull
        private final WaveStartSource startSource;
        private final long expiryAtEpochMillis;
        private int currentWave = 0;
        private long waveClearedAtEpochMillis = -1L;
        private final List<Ref<EntityStore>> activeWaveMobRefs = new ArrayList<>();
        private final Map<Ref<EntityStore>, String> activeWaveMobNames = new IdentityHashMap<>();
        private int lastAliveMobCount = 0;
        private long lastKillAtEpochMillis = System.currentTimeMillis();
        private long lastNoKillHintAtEpochMillis = -1L;
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
                                  @Nonnull LevelRange baseLevelRange,
                                  boolean wavePortalVisualEnabled,
                                  @Nullable String linkedGateId,
                                  @Nullable WavePortalPlacement initialPortalPlacement,
                                  @Nullable Vector3d waveCenterPosition,
                                  @Nonnull WaveStartSource startSource,
                                  long expiryAtEpochMillis) {
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
            this.wavePortalVisualEnabled = wavePortalVisualEnabled;
            this.linkedGateId = linkedGateId;
            this.wavePortalPlacement = initialPortalPlacement;
            this.waveCenterPosition = waveCenterPosition;
            this.startSource = startSource;
            this.expiryAtEpochMillis = expiryAtEpochMillis;
        }
    }

    private static final class MobCoordinateHintLine {
        @Nonnull
        private final String mobName;
        private final int x;
        private final int y;
        private final int z;

        private MobCoordinateHintLine(@Nonnull String mobName, int x, int y, int z) {
            this.mobName = mobName;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private enum WaveStartSource {
        DIRECT_COMMAND,
        MANUAL_NATURAL,
        AUTO_NATURAL,
        LINKED_GATE
    }

    private static final class LinkedGateWaveState {
        @Nonnull
        private final GateRankTier rankTier;
        @Nullable
        private final UUID ownerUuid;
        @Nonnull
        private final String stage;
        private final long opensAtEpochMillis;
        private final long openedAtEpochMillis;

        private LinkedGateWaveState(@Nonnull GateRankTier rankTier,
                                    @Nullable UUID ownerUuid,
                                    @Nonnull String stage,
                                    long opensAtEpochMillis,
                                    long openedAtEpochMillis) {
            this.rankTier = rankTier;
            this.ownerUuid = ownerUuid;
            this.stage = stage;
            this.opensAtEpochMillis = opensAtEpochMillis;
            this.openedAtEpochMillis = openedAtEpochMillis;
        }

        @Nonnull
        private static LinkedGateWaveState pending(@Nonnull GateRankTier rankTier,
                                                   @Nullable UUID ownerUuid,
                                                   long opensAtEpochMillis) {
            return new LinkedGateWaveState(rankTier, ownerUuid, "pending", opensAtEpochMillis, 0L);
        }

        @Nonnull
        private static LinkedGateWaveState active(@Nonnull GateRankTier rankTier,
                                                  @Nullable UUID ownerUuid,
                                                  long openedAtEpochMillis) {
            return new LinkedGateWaveState(rankTier, ownerUuid, "active", 0L, openedAtEpochMillis);
        }
    }

    private static final class WaveSpawnProgress {
        private final int totalBatches;
        private final AtomicInteger completedBatches = new AtomicInteger(0);
        private final AtomicInteger spawned = new AtomicInteger(0);
        private final AtomicBoolean bossSpawned = new AtomicBoolean(false);

        private WaveSpawnProgress(int totalBatches) {
            this.totalBatches = Math.max(1, totalBatches);
        }
    }

    private static final class WavePortalPlacement {
        @Nonnull
        private final UUID placementUuid;
        @Nullable
        private final UUID worldUuid;
        private final int x;
        private final int y;
        private final int z;
        private final int blockIntId;
        @Nonnull
        private final String blockId;

        private WavePortalPlacement(@Nonnull UUID placementUuid,
                                    @Nullable UUID worldUuid,
                                    int x,
                                    int y,
                                    int z,
                                    int blockIntId,
                                    @Nonnull String blockId) {
            this.placementUuid = placementUuid;
            this.worldUuid = worldUuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockIntId = blockIntId;
            this.blockId = blockId;
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

    public record TrackedWaveSnapshot(@Nullable UUID ownerUuid,
                                      @Nullable String ownerName,
                                      @Nonnull GateRankTier rankTier,
                                      @Nonnull String stage,
                                      @Nonnull String kind,
                                      @Nullable UUID worldUuid,
                                      @Nullable String worldName,
                                      @Nullable String linkedGateId,
                                      @Nullable Integer x,
                                      @Nullable Integer y,
                                      @Nullable Integer z) {
    }

    public record LinkedGateWaveTimingSnapshot(@Nonnull String stage,
                                               long opensAtEpochMillis,
                                               long openedAtEpochMillis,
                                               long expiresAtEpochMillis) {
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

    public static final class NaturalStartResult {
        public final boolean scheduled;
        @Nonnull
        public final String message;
        @Nullable
        public final GateRankTier rankTier;
        public final int delayMinutes;

        private NaturalStartResult(boolean scheduled, @Nonnull String message,
                                   @Nullable GateRankTier rankTier, int delayMinutes) {
            this.scheduled = scheduled;
            this.message = message;
            this.rankTier = rankTier;
            this.delayMinutes = delayMinutes;
        }

        @Nonnull
        static NaturalStartResult scheduled(@Nonnull GateRankTier rankTier, int delayMinutes) {
            return new NaturalStartResult(true,
                    String.format(Locale.ROOT,
                            "Dungeon break scheduled. %s-Rank wave opens in %d minute(s).",
                            rankTier.letter(), delayMinutes),
                    rankTier, delayMinutes);
        }

        @Nonnull
        static NaturalStartResult failed(@Nonnull String message) {
            return new NaturalStartResult(false, message, null, 0);
        }
    }
}
