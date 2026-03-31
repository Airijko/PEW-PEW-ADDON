package com.airijko.endlessleveling.events;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.managers.AddonFilesManager;
import com.airijko.endlessleveling.managers.AddonLoggingManager;
import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
import com.airijko.endlessleveling.managers.PortalProximityManager;
import com.airijko.endlessleveling.managers.GateInstancePersistenceManager;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceEntityConfig;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.config.WorldReturnPoint;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.PendingTeleport;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.HashSet;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.logging.Level;
import java.util.HashMap;

public final class PortalLeveledInstanceRouter {

    private static final Map<String, String> ROUTING_TO_SUFFIX = Map.of(
            "EL_MJ_Instance_D01", "MJ_D01",
            "EL_MJ_Instance_D02", "MJ_D02",
            "EL_MJ_Instance_D03", "MJ_D03",
            "EL_Endgame_Frozen_Dungeon", "EG_Frozen",
            "EL_Endgame_Golem_Void", "EG_Golem",
            "EL_Endgame_Swamp_Dungeon", "EG_Swamp"
    );
        private static final Map<String, String> SUFFIX_TO_ORIGINAL_TEMPLATE = Map.of(
            "MJ_D01", "MJ_Instance_D01",
            "MJ_D02", "MJ_Instance_D02",
            "MJ_D03", "MJ_Instance_D03",
            "EG_Frozen", "Endgame_Frozen_Dungeon",
            "EG_Golem", "Endgame_Golem_Void",
            "EG_Swamp", "Endgame_Swamp_Dungeon"
        );

    private static final Map<String, String> INSTANCE_TEMPLATE_TO_SUFFIX = Map.ofEntries(
            Map.entry("EL_MJ_Instance_D01", "MJ_D01"),
            Map.entry("EL_MJ_Instance_D02", "MJ_D02"),
            Map.entry("EL_MJ_Instance_D03", "MJ_D03"),
            Map.entry("EL_Endgame_Frozen_Dungeon", "EG_Frozen"),
            Map.entry("EL_Endgame_Golem_Void", "EG_Golem"),
            Map.entry("EL_Endgame_Swamp_Dungeon", "EG_Swamp")
    );

    private static final Map<String, String> ROUTING_TO_DISPLAY = Map.of(
            "EL_MJ_Instance_D01", "Major Dungeon I",
            "EL_MJ_Instance_D02", "Major Dungeon II",
            "EL_MJ_Instance_D03", "Major Dungeon III",
            "EL_Endgame_Frozen_Dungeon", "Endgame Frozen Dungeon",
            "EL_Endgame_Golem_Void", "Endgame Golem Void",
            "EL_Endgame_Swamp_Dungeon", "Endgame Swamp Dungeon"
    );
    private static final int DYNAMIC_MIN_LEVEL = 1;
        private static final int DYNAMIC_MAX_LEVEL = 500;
        private static final int DYNAMIC_RANGE_SIZE = 15;

    /** Block ID → routing world template name (EL_MajorDungeonPortal_D01 → EL_MJ_Instance_D01, etc.) */
    private static final Map<String, String> BLOCK_ID_TO_ROUTING_NAME = Map.of(
            "EL_MajorDungeonPortal_D01", "EL_MJ_Instance_D01",
            "EL_MajorDungeonPortal_D02", "EL_MJ_Instance_D02",
            "EL_MajorDungeonPortal_D03", "EL_MJ_Instance_D03",
            "EL_EndgamePortal_Frozen_Dungeon", "EL_Endgame_Frozen_Dungeon",
            "EL_EndgamePortal_Swamp_Dungeon", "EL_Endgame_Swamp_Dungeon",
            "EL_EndgamePortal_Golem_Void", "EL_Endgame_Golem_Void"
    );

            /** Routing template name -> base portal block ID for pairing backfill. */
            private static final Map<String, String> ROUTING_NAME_TO_BLOCK_ID = Map.of(
                "EL_MJ_Instance_D01", "EL_MajorDungeonPortal_D01",
                "EL_MJ_Instance_D02", "EL_MajorDungeonPortal_D02",
                "EL_MJ_Instance_D03", "EL_MajorDungeonPortal_D03",
                "EL_Endgame_Frozen_Dungeon", "EL_EndgamePortal_Frozen_Dungeon",
                "EL_Endgame_Swamp_Dungeon", "EL_EndgamePortal_Swamp_Dungeon",
                "EL_Endgame_Golem_Void", "EL_EndgamePortal_Golem_Void"
            );

    /** Routing world template name → level profile announced when the portal gate was placed. */
    private static final Map<String, PendingLevelProfile> PENDING_LEVEL_RANGES = new ConcurrentHashMap<>();
    /** Gate identity -> level profile announced when that specific gate was placed. */
    private static final Map<String, PendingLevelProfile> PENDING_LEVEL_RANGES_BY_GATE = new ConcurrentHashMap<>();

    /** Instance world name → resolved level range, kept until the world is removed. */
    private static final Map<String, LevelRange> ACTIVE_LEVEL_RANGES = new ConcurrentHashMap<>();
    /** Instance world name → resolved boss level for gate notifications. */
    private static final Map<String, Integer> ACTIVE_BOSS_LEVELS = new ConcurrentHashMap<>();
    /** Instance world name → gate rank letter (E/D/C/B/A/S) for notifications. */
    private static final Map<String, String> ACTIVE_RANK_LETTERS = new ConcurrentHashMap<>();

    /** Player UUID -> recently triggered gate entry context for direct-entry reroute recovery. */
    private static final Map<UUID, PendingGateEntry> PLAYER_PENDING_GATE_ENTRIES = new ConcurrentHashMap<>();
    /** Player UUID -> pending death-return offset teleport, executed on PlayerReady. */
    private static final Map<UUID, PendingDeathReturn> PLAYER_PENDING_DEATH_RETURNS = new ConcurrentHashMap<>();
    /** Player UUID -> instance world name they were blocked from entering (deferred return executed on PlayerReady). */
    private static final Map<UUID, String> PLAYER_PENDING_BLOCK_RETURNS = new ConcurrentHashMap<>();
    /** Player UUID -> millis until custom return-portal triggers should be ignored after instance entry. */
    private static final Map<UUID, Long> PLAYER_RETURN_PORTAL_SUPPRESSION_UNTIL = new ConcurrentHashMap<>();

    /** Player UUID -> routing template -> throttle-until millis to suppress duplicate spawn races. */
    private static final Map<UUID, Map<String, Long>> PLAYER_ROUTING_THROTTLES = new ConcurrentHashMap<>();
    /** Player UUID -> last time we queued any teleport component to avoid rapid teleportId churn. */
    private static final Map<UUID, Long> PLAYER_LAST_TELEPORT_REQUEST_MILLIS = new ConcurrentHashMap<>();

    /** Gate key (world+xyz) -> instance world name (e.g., "uuid:120:65:-40" -> "instance-EL_MJ_Instance_D01-abc123") */
    private static final Map<String, String> GATE_KEY_TO_INSTANCE_NAME = new ConcurrentHashMap<>();
    /** Gate identity -> expected instance metadata captured at gate spawn time. */
    private static final Map<String, GateInstanceExpectation> GATE_INSTANCE_EXPECTATIONS = new ConcurrentHashMap<>();
    /** Gate key -> spawn lock start millis to prevent concurrent duplicate spawns per gate. */
    private static final Map<String, Long> GATE_SPAWN_IN_FLIGHT = new ConcurrentHashMap<>();
    /** Instance world names currently being reloaded after unload while still paired to an active gate. */
    private static final Set<String> PAIRED_INSTANCE_RELOADS_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /** Return portal placement keys currently queued to prevent duplicate add/ready scheduling. */
    private static final Set<String> RETURN_PORTAL_PLACEMENT_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static final long ROUTING_DEDUPE_WINDOW_MILLIS = 5000L;
    private static final long GATE_SPAWN_STALE_MILLIS = 120000L;
    private static final long FALLBACK_TELEPORT_RETRY_DELAY_MILLIS = 100L;
    private static final int FALLBACK_TELEPORT_MAX_RETRIES = 8;
    private static final long DEATH_RETURN_RETRY_DELAY_MILLIS = 100L;
    private static final int DEATH_RETURN_MAX_RETRIES = 3;
    private static final long PENDING_GATE_ENTRY_TTL_MILLIS = 15000L;
    private static final long DIRECT_ENTRY_FALLBACK_GATE_TTL_MILLIS = 120000L;
    private static final long DIRECT_ENTRY_REROUTE_RETRY_DELAY_MILLIS = 100L;
    private static final int DIRECT_ENTRY_REROUTE_MAX_ATTEMPTS = 3;
    private static final long RETURN_PORTAL_RETRY_DELAY_MILLIS = 150L;
    private static final int RETURN_PORTAL_MAX_RETRIES = 12;
    private static final long RETURN_PORTAL_ENTRY_SUPPRESSION_MILLIS = 8000L;
    private static final long MANAGED_ENTRY_CONTEXT_TTL_MILLIS = 15000L;
    private static final long FIXED_SPAWN_TELEPORT_COOLDOWN_MILLIS = 1500L;
    private static final String GATE_OVERRIDE_ID_PREFIX = "elportal:gate:";
    private static final String ENDLESS_LEVELING_PREFIX = "[EndlessLeveling] ";

    /** Player UUID → latest known entry target used for custom return portal fallback. */
    private static final Map<UUID, ReturnTarget> PLAYER_ENTRY_TARGETS = new ConcurrentHashMap<>();
    /** Player UUID -> instance world names they are locked out from after death. */
    private static final Map<UUID, Set<String>> PLAYER_DEATH_REENTRY_LOCKS = new ConcurrentHashMap<>();
    /** Player UUID -> canonical gate identities they are permanently locked out from after death. */
    private static final Map<UUID, Set<String>> PLAYER_DEATH_REENTRY_GATE_LOCKS = new ConcurrentHashMap<>();
    private static volatile boolean DEBUG_PREVENT_ENTER = false;

    private static JavaPlugin plugin;
    private static AddonFilesManager filesManager;

    private PortalLeveledInstanceRouter() {
    }

    public static void initialize(@Nonnull JavaPlugin owner) {
        plugin = owner;
    }

    public static void setFilesManager(@Nullable AddonFilesManager manager) {
        filesManager = manager;
    }

    public static void setDebugPreventEnter(boolean enabled) {
        DEBUG_PREVENT_ENTER = enabled;
        log(Level.WARNING,
                "[ELPortal] Debug prevent-enter is now %s",
                enabled ? "ENABLED" : "DISABLED");
    }

    public static boolean isDebugPreventEnterEnabled() {
        return DEBUG_PREVENT_ENTER;
    }

    public static void markPlayerDeathReentryLock(@Nonnull UUID playerUuid,
                                                   @Nonnull String instanceWorldName) {
        if (instanceWorldName.isBlank()) {
            return;
        }

        PLAYER_DEATH_REENTRY_LOCKS.compute(playerUuid, (ignored, existing) -> {
            Set<String> next = existing == null ? new HashSet<>() : new HashSet<>(existing);
            next.add(instanceWorldName);
            return next;
        });

        log(Level.INFO,
                "[ELPortal] Death re-entry lock set player=%s instance=%s",
                playerUuid,
                instanceWorldName);

        String gateIdentity = findGateIdentityForInstanceWorld(instanceWorldName);
        if (gateIdentity != null && !gateIdentity.isBlank()) {
            String canonicalGateIdentity = canonicalizeGateIdentity(gateIdentity);
            PLAYER_DEATH_REENTRY_GATE_LOCKS.compute(playerUuid, (ignored, existing) -> {
                Set<String> next = existing == null ? new HashSet<>() : new HashSet<>(existing);
                next.add(canonicalGateIdentity);
                return next;
            });

            // Persist immediately so a crash cannot drop this death lock.
            persistGateInstanceImmediate(canonicalGateIdentity, instanceWorldName, null);
            return;
        }

        // Persist the updated death-lock list immediately so crashes don't lose it.
        for (Map.Entry<String, String> e : GATE_KEY_TO_INSTANCE_NAME.entrySet()) {
            if (instanceWorldName.equals(e.getValue()) && e.getKey().startsWith("el_gate:")) {
                persistGateInstanceImmediate(e.getKey(), instanceWorldName, null);
                break;
            }
        }
    }

    public static void clearDeathReentryLocksForInstance(@Nonnull String instanceWorldName) {
        if (instanceWorldName.isBlank()) {
            return;
        }

        // Keep canonical gate locks intact for permanent lockout semantics.
        // This only clears legacy world-name locks.
        for (Map.Entry<UUID, Set<String>> entry : PLAYER_DEATH_REENTRY_LOCKS.entrySet()) {
            Set<String> locks = entry.getValue();
            if (locks == null || locks.isEmpty()) {
                continue;
            }

            Set<String> next = new HashSet<>(locks);
            if (!next.remove(instanceWorldName)) {
                continue;
            }

            if (next.isEmpty()) {
                PLAYER_DEATH_REENTRY_LOCKS.remove(entry.getKey(), locks);
            } else {
                PLAYER_DEATH_REENTRY_LOCKS.put(entry.getKey(), next);
            }
        }
    }

    public static void shutdown() {
        PENDING_LEVEL_RANGES.clear();
        PENDING_LEVEL_RANGES_BY_GATE.clear();
        ACTIVE_LEVEL_RANGES.clear();
        ACTIVE_BOSS_LEVELS.clear();
        ACTIVE_RANK_LETTERS.clear();
        PLAYER_ENTRY_TARGETS.clear();
        PLAYER_ROUTING_THROTTLES.clear();
        PLAYER_PENDING_GATE_ENTRIES.clear();
        PLAYER_PENDING_DEATH_RETURNS.clear();
        PLAYER_RETURN_PORTAL_SUPPRESSION_UNTIL.clear();
        PLAYER_LAST_TELEPORT_REQUEST_MILLIS.clear();
        PLAYER_DEATH_REENTRY_LOCKS.clear();
        PLAYER_DEATH_REENTRY_GATE_LOCKS.clear();
        GATE_KEY_TO_INSTANCE_NAME.clear();
        GATE_INSTANCE_EXPECTATIONS.clear();
        GATE_SPAWN_IN_FLIGHT.clear();
        PAIRED_INSTANCE_RELOADS_IN_FLIGHT.clear();
        RETURN_PORTAL_PLACEMENT_IN_FLIGHT.clear();
        filesManager = null;
        plugin = null;
    }

    /**
     * Saves current gate-to-instance mappings and level profiles to persistent storage.
     * Called during server shutdown to preserve dungeons across restarts.
     */
    public static void saveGateInstances() {
        try {
            List<GateInstancePersistenceManager.StoredGateInstance> instances = new ArrayList<>();
            Set<String> savedCanonicalKeys = new java.util.HashSet<>();
            for (Map.Entry<String, String> entry : GATE_KEY_TO_INSTANCE_NAME.entrySet()) {
                String gateKey = entry.getKey();
                String instanceWorldName = entry.getValue();

                // Always persist under the canonical key to prevent legacy-key drift.
                // Skip this entry if we already saved a canonical form for this key.
                String canonicalKey = gateKey.startsWith("el_gate:") ? gateKey : ("el_gate:" + gateKey);
                if (!savedCanonicalKeys.add(canonicalKey)) {
                    continue;
                }

                String legacyKey = canonicalKey.startsWith("el_gate:")
                        ? canonicalKey.substring("el_gate:".length())
                        : canonicalKey;

                GateInstancePersistenceManager.StoredGateInstance existingSaved =
                        GateInstancePersistenceManager.getSavedInstance(canonicalKey);
                if (existingSaved == null && !legacyKey.equals(canonicalKey)) {
                    existingSaved = GateInstancePersistenceManager.getSavedInstance(legacyKey);
                }

                LevelRange range = ACTIVE_LEVEL_RANGES.get(instanceWorldName);
                Integer bossLvl = ACTIVE_BOSS_LEVELS.get(instanceWorldName);
                String rankLtr = ACTIVE_RANK_LETTERS.get(instanceWorldName);

                // Look up blockId under canonical, legacy, active-gate, then persisted fallback.
                GateInstanceExpectation exp = GATE_INSTANCE_EXPECTATIONS.get(canonicalKey);
                if (exp == null && !legacyKey.equals(canonicalKey)) {
                    exp = GATE_INSTANCE_EXPECTATIONS.get(legacyKey);
                }

                String blkId = exp != null && !exp.blockId().isBlank() ? exp.blockId() : "";
                if (blkId.isBlank()) {
                    String activeBlockId = NaturalPortalGateManager.resolveGateBlockId(canonicalKey);
                    if ((activeBlockId == null || activeBlockId.isBlank()) && !legacyKey.equals(canonicalKey)) {
                        activeBlockId = NaturalPortalGateManager.resolveGateBlockId(legacyKey);
                    }
                    if (activeBlockId != null && !activeBlockId.isBlank()) {
                        blkId = activeBlockId;
                    }
                }
                if (blkId.isBlank() && existingSaved != null && existingSaved.blockId != null && !existingSaved.blockId.isBlank()) {
                    blkId = existingSaved.blockId;
                }
                if (blkId.isBlank()) {
                    log(Level.WARNING,
                            "[ELPortal] Skipping persistence for gateId=%s instance=%s due to unresolved blockId",
                            canonicalKey,
                            instanceWorldName);
                    continue;
                }

                int min = range != null
                        ? range.min()
                        : (existingSaved != null ? existingSaved.minLevel : 1);
                int max = range != null
                        ? range.max()
                        : (existingSaved != null ? existingSaved.maxLevel : 500);
                int boss = bossLvl != null
                        ? bossLvl
                        : (existingSaved != null ? existingSaved.bossLevel : max);
                if (rankLtr == null || rankLtr.isBlank()) {
                    rankLtr = existingSaved != null && existingSaved.rankLetter != null && !existingSaved.rankLetter.isBlank()
                            ? existingSaved.rankLetter
                            : "E";
                }

                List<String> deathLockedPlayerUuids = getDeathLockedPlayerUuidsForGate(canonicalKey, instanceWorldName);
                if (deathLockedPlayerUuids.isEmpty() && existingSaved != null && existingSaved.deathLockedPlayerUuids != null) {
                    deathLockedPlayerUuids = existingSaved.deathLockedPlayerUuids;
                }

                instances.add(new GateInstancePersistenceManager.StoredGateInstance(
                        canonicalKey,
                        instanceWorldName,
                        min,
                        max,
                        boss,
                        rankLtr,
                        blkId,
                        deathLockedPlayerUuids));
            }
            GateInstancePersistenceManager.saveGateInstances(instances);
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Failed to save gate instances: %s", ex.getMessage());
        }
    }

    /**
     * Immediately persists a single gate-to-instance pairing to disk (write-through).
     * Uses the same blockId / level fallback chain as the full saveGateInstances() sweep.
     * Call this whenever a gate pairing is created or updated to ensure crash-safe persistence.
     */
    private static void persistGateInstanceImmediate(@Nonnull String gateKey,
                                                     @Nonnull String instanceWorldName,
                                                     @Nullable String blockIdHint) {
        try {
            String canonicalKey = gateKey.startsWith("el_gate:") ? gateKey : ("el_gate:" + gateKey);
            String legacyKey = canonicalKey.substring("el_gate:".length());

            GateInstancePersistenceManager.StoredGateInstance existingSaved =
                    GateInstancePersistenceManager.getSavedInstance(canonicalKey);
            if (existingSaved == null) {
                existingSaved = GateInstancePersistenceManager.getSavedInstance(legacyKey);
            }

            LevelRange range   = ACTIVE_LEVEL_RANGES.get(instanceWorldName);
            Integer bossLvl    = ACTIVE_BOSS_LEVELS.get(instanceWorldName);
            String rankLtr     = ACTIVE_RANK_LETTERS.get(instanceWorldName);

            // blockId: hint → expectation map → active-gate tracking → persisted fallback
            String blkId = blockIdHint != null && !blockIdHint.isBlank() ? blockIdHint : "";
            if (blkId.isBlank()) {
                GateInstanceExpectation exp = GATE_INSTANCE_EXPECTATIONS.get(canonicalKey);
                if (exp == null) exp = GATE_INSTANCE_EXPECTATIONS.get(legacyKey);
                if (exp != null && !exp.blockId().isBlank()) blkId = exp.blockId();
            }
            if (blkId.isBlank()) {
                String activeBlockId = NaturalPortalGateManager.resolveGateBlockId(canonicalKey);
                if (activeBlockId == null || activeBlockId.isBlank()) {
                    activeBlockId = NaturalPortalGateManager.resolveGateBlockId(legacyKey);
                }
                if (activeBlockId != null && !activeBlockId.isBlank()) {
                    blkId = activeBlockId;
                }
            }
            if (blkId.isBlank() && existingSaved != null && existingSaved.blockId != null && !existingSaved.blockId.isBlank()) {
                blkId = existingSaved.blockId;
            }
            if (blkId.isBlank()) {
                log(Level.WARNING,
                        "[ELPortal] Skipping immediate persistence for gateId=%s instance=%s due to unresolved blockId",
                        canonicalKey, instanceWorldName);
                return;
            }

            int min  = range   != null ? range.min() : (existingSaved != null ? existingSaved.minLevel  : 1);
            int max  = range   != null ? range.max() : (existingSaved != null ? existingSaved.maxLevel  : 500);
            int boss = bossLvl != null ? bossLvl     : (existingSaved != null ? existingSaved.bossLevel : max);
            if (rankLtr == null || rankLtr.isBlank()) {
                rankLtr = existingSaved != null && existingSaved.rankLetter != null && !existingSaved.rankLetter.isBlank()
                        ? existingSaved.rankLetter : "E";
            }

            List<String> deathLockedUuids = getDeathLockedPlayerUuidsForGate(canonicalKey, instanceWorldName);
            if (deathLockedUuids.isEmpty() && existingSaved != null && existingSaved.deathLockedPlayerUuids != null) {
                deathLockedUuids = existingSaved.deathLockedPlayerUuids;
            }

            GateInstancePersistenceManager.StoredGateInstance inst =
                    new GateInstancePersistenceManager.StoredGateInstance(
                            canonicalKey, instanceWorldName, min, max, boss, rankLtr, blkId, deathLockedUuids);
            GateInstancePersistenceManager.upsertGateInstance(inst);
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Failed immediate gate instance persistence gateId=%s: %s",
                    gateKey, ex.getMessage());
        }
    }

    /**
     * Restores saved gate-to-instance mappings and level profiles from persistent storage.
     * Called during server startup to recover dungeons from before shutdown.
     */
    public static void restoreSavedGateInstances() {
        try {
            var savedInstances = GateInstancePersistenceManager.getSavedInstances();
            if (savedInstances.isEmpty()) {
                return;
            }

            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            EndlessLevelingAPI api = EndlessLevelingAPI.get();
            int restored = 0;
            for (GateInstancePersistenceManager.StoredGateInstance stored : savedInstances.values()) {
                try {
                    String gateKey = stored.gateKey;
                    String instanceWorldName = stored.instanceWorldName;

                    // The instance world is rarely loaded at startup — accept either loaded
                    // or loadable. For stale entries where the world no longer exists, still
                    // restore expectations so registerGateExpectedInstance finds them and doesn't
                    // default to using fake group IDs.
                    boolean worldExists = universe.getWorld(instanceWorldName) != null
                            || universe.isWorldLoadable(instanceWorldName);
                    if (!worldExists) {
                        log(Level.WARNING,
                                "[ELPortal] Restoring expectations for stale gate %s (instance world %s not loadable)",
                                gateKey, instanceWorldName);
                    }

                    // --- pairing ---
                    // Always store under BOTH the canonical (el_gate:...) and legacy key so
                    // registerGateExpectedInstance (canonical lookup) and backfill (legacy lookup)
                    // both find the correct world regardless of which key format was saved.
                    String canonicalGateKey = gateKey.startsWith("el_gate:") ? gateKey : ("el_gate:" + gateKey);
                    String legacyGateKey = gateKey.startsWith("el_gate:") ? gateKey.substring("el_gate:".length()) : gateKey;
                    GATE_KEY_TO_INSTANCE_NAME.put(canonicalGateKey, instanceWorldName);
                    GATE_KEY_TO_INSTANCE_NAME.putIfAbsent(legacyGateKey, instanceWorldName);

                    // --- level data ---
                    LevelRange range = new LevelRange(stored.minLevel, stored.maxLevel);
                    ACTIVE_LEVEL_RANGES.put(instanceWorldName, range);
                    ACTIVE_BOSS_LEVELS.put(instanceWorldName, stored.bossLevel);
                    ACTIVE_RANK_LETTERS.put(instanceWorldName, stored.rankLetter);

                    if (stored.deathLockedPlayerUuids != null && !stored.deathLockedPlayerUuids.isEmpty()) {
                        for (String playerUuidString : stored.deathLockedPlayerUuids) {
                            try {
                                UUID playerUuid = UUID.fromString(playerUuidString);
                                markPlayerDeathReentryLock(playerUuid, instanceWorldName);
                            } catch (Exception ignored) {
                                log(Level.FINE,
                                        "[ELPortal] Skipped invalid death lock UUID '%s' for instance=%s",
                                        playerUuidString,
                                        instanceWorldName);
                            }
                        }
                    }

                    // Re-register the level override immediately so mobs are scaled
                    // correctly on first entry — before cacheResolvedInstanceWorld runs.
                    // Only do this if the world actually exists; stale entries can't register yet.
                    if (api != null && worldExists) {
                        int bossOffset = Math.max(0, stored.bossLevel - stored.maxLevel);
                        registerGateOverrideCompat(
                            api,
                            gateLevelOverrideId(instanceWorldName),
                            instanceWorldName,
                            stored.minLevel,
                            stored.maxLevel,
                            bossOffset);

                        // Cleanup legacy fixed key from older builds.
                        api.removeMobWorldFixedLevelOverride(instanceWorldName);
                    }

                    // --- gate expectation (enables attemptRouteToExpectedWorldId fallback) ---
                    if (!stored.blockId.isBlank()) {
                        String routingName = resolveRoutingName(stored.blockId);
                        if (routingName != null) {
                            String expectedGroupId = buildExpectedGroupId(canonicalGateKey, routingName);
                            GATE_INSTANCE_EXPECTATIONS.put(canonicalGateKey,
                                    new GateInstanceExpectation(
                                            stored.blockId,
                                            routingName,
                                            expectedGroupId,
                                            instanceWorldName,
                                            System.currentTimeMillis()));

                            // Restore pending-by-gate so the saved levels are used if
                            // a fresh gate block triggers a new spawn before any player enters.
                            int bossOffset = Math.max(0, stored.bossLevel - stored.maxLevel);
                                PendingLevelProfile pendingProfile = new PendingLevelProfile(
                                    stored.minLevel,
                                    stored.maxLevel,
                                    bossOffset,
                                    stored.rankLetter);
                                PENDING_LEVEL_RANGES_BY_GATE.put(canonicalGateKey, pendingProfile);
                                PENDING_LEVEL_RANGES_BY_GATE.putIfAbsent(legacyGateKey, pendingProfile);
                        }
                    }

                    // --- re-populate NaturalPortalGateManager.ACTIVE_GATES ---
                    // so resolveGateIdAt() returns the stable ID and both sides use the same key.
                    NaturalPortalGateManager.restoreActiveGate(stored, universe);

                    restored++;
                    log(Level.INFO,
                            "[ELPortal] Restored gate %s \u2192 instance %s (levels %d-%d boss=%d rank=%s)",
                            canonicalGateKey, instanceWorldName,
                            stored.minLevel, stored.maxLevel, stored.bossLevel, stored.rankLetter);
                } catch (Exception ex) {
                    log(Level.WARNING, "[ELPortal] Failed to restore gate instance %s: %s",
                            stored.gateKey, ex.getMessage());
                }
            }

            if (restored > 0) {
                log(Level.INFO, "[ELPortal] Startup: restored %d gate instance mappings", restored);
            }
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Failed to restore saved gate instances: %s", ex.getMessage());
        }
    }

    /**
     * Teleports a player to their return/home world during shutdown or emergencies.
     * Used by NaturalPortalGateManager to kick players out of instances.
     */
    public static void teleportPlayerToReturnWorld(@Nonnull PlayerRef playerRef, @Nonnull World targetWorld) {
        try {
            Ref<EntityStore> entityRef = playerRef.getReference();
            if (entityRef == null || !entityRef.isValid()) {
                return;
            }

            Store<EntityStore> store = entityRef.getStore();
            
            // Try to get spawn point from world config, fallback to hardcoded coords
            Transform spawnTransform = null;
            if (targetWorld.getWorldConfig() != null && targetWorld.getWorldConfig().getSpawnProvider() != null) {
                spawnTransform = targetWorld.getWorldConfig().getSpawnProvider().getSpawnPoint(targetWorld, playerRef.getUuid());
            }
            
            if (spawnTransform == null) {
                // Fallback spawn location (0, 64, 0) - using Transform(double,double,double) constructor
                spawnTransform = new Transform(0.0, 64.0, 0.0);
            }

            store.addComponent(entityRef, Teleport.getComponentType(), Teleport.createForPlayer(targetWorld, spawnTransform));
            log(Level.INFO, "[ELPortal] Teleported player %s to return world %s", playerRef.getUsername(), targetWorld.getName());
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Failed to teleport player %s: %s", playerRef.getUsername(), ex.getMessage());
        }
    }

    public static void registerGateExpectedInstance(@Nonnull String gateIdentity,
                                                    @Nonnull String blockId) {
        if (gateIdentity.isBlank() || blockId.isBlank()) {
            return;
        }

        String canonicalGateIdentity = canonicalizeGateIdentity(gateIdentity);
        String legacyGateIdentity = legacyGateIdentity(canonicalGateIdentity);

        String routingName = resolveRoutingName(blockId);
        if (routingName == null) {
            return;
        }

        String expectedGroupId = buildExpectedGroupId(canonicalGateIdentity, routingName);

        // If a saved pairing already exists for this gate key, reuse the known instance world
        // name as expectedWorldId so we don't clobber the restored mapping with a fake group-ID
        // string. Without this, a post-restart gate detection would reset expectedWorldId to the
        // el_gate_..._x_y_z format, causing a spurious "mismatch" rotation on first re-entry.
        String existingInstance = GATE_KEY_TO_INSTANCE_NAME.get(canonicalGateIdentity);
        if ((existingInstance == null || existingInstance.isBlank())
                && legacyGateIdentity != null
                && !legacyGateIdentity.equals(canonicalGateIdentity)) {
            existingInstance = GATE_KEY_TO_INSTANCE_NAME.get(legacyGateIdentity);
        }
        String expectedWorldId = (existingInstance != null && !existingInstance.isBlank())
            ? existingInstance
            : null;

        GateInstanceExpectation expectation = new GateInstanceExpectation(
                blockId,
                routingName,
                expectedGroupId,
                expectedWorldId,
                System.currentTimeMillis());
        GATE_INSTANCE_EXPECTATIONS.put(canonicalGateIdentity, expectation);
        if (legacyGateIdentity != null && !legacyGateIdentity.equals(canonicalGateIdentity)) {
            GATE_INSTANCE_EXPECTATIONS.put(legacyGateIdentity, expectation);
        }
        log(Level.INFO,
                "[ELPortal] Gate expectation cached gateId=%s block=%s template=%s expectedGroupId=%s expectedWorldId=%s",
                canonicalGateIdentity,
                blockId,
                routingName,
                expectedGroupId,
                expectedWorldId);

        // Emit a warning-level twin log so this is visible even when addon INFO logs are suppressed.
        log(Level.WARNING,
                "[ELPortal] Gate expectation cached gateId=%s block=%s template=%s expectedGroupId=%s expectedWorldId=%s",
            canonicalGateIdentity,
                blockId,
                routingName,
                expectedGroupId,
                expectedWorldId);
    }

    /**
     * Called by gate manager when a portal gate expires. Removes the paired instance if it exists.
     */
    public static void cleanupGateInstance(@Nonnull World world,
                                           int x,
                                           int y,
                                           int z,
                                           @Nonnull String blockId) {
        String stableGateId = NaturalPortalGateManager.resolveGateIdAt(world, x, y, z);
        if (stableGateId == null || stableGateId.isBlank()) {
            stableGateId = deriveStableGateIdFromWorldName(world);
        }
        String legacyKey = buildGateKey(world, x, y, z);
        String gateIdentity = stableGateId != null && !stableGateId.isBlank() ? stableGateId : legacyKey;
        String instanceName = GATE_KEY_TO_INSTANCE_NAME.remove(gateIdentity);
        PENDING_LEVEL_RANGES_BY_GATE.remove(gateIdentity);
        GATE_INSTANCE_EXPECTATIONS.remove(gateIdentity);
        GATE_SPAWN_IN_FLIGHT.remove(gateIdentity);
        if (instanceName == null && !legacyKey.equals(gateIdentity)) {
            instanceName = GATE_KEY_TO_INSTANCE_NAME.remove(legacyKey);
            PENDING_LEVEL_RANGES_BY_GATE.remove(legacyKey);
            GATE_INSTANCE_EXPECTATIONS.remove(legacyKey);
            GATE_SPAWN_IN_FLIGHT.remove(legacyKey);
            gateIdentity = legacyKey;
        }
        if (instanceName != null) {
            InstancesPlugin instances = InstancesPlugin.get();
            if (instances != null) {
                try {
                    instances.safeRemoveInstance(instanceName);
                    log(Level.INFO, "[ELPortal] Cleaned up paired instance %s for expired gate %s key=%s",
                            instanceName, blockId, gateIdentity);
                } catch (Exception ex) {
                    log(Level.WARNING, "[ELPortal] Failed to remove instance %s for gate %s key=%s: %s",
                            instanceName, blockId, gateIdentity, ex.getMessage());
                }
            }
            // Also clear level ranges
            ACTIVE_LEVEL_RANGES.remove(instanceName);
            ACTIVE_BOSS_LEVELS.remove(instanceName);
            ACTIVE_RANK_LETTERS.remove(instanceName);
        }
    }

    public static void cleanupGateInstanceByIdentity(@Nonnull String gateIdentity,
                                                     @Nonnull String blockId) {
        String canonicalGateIdentity = canonicalizeGateIdentity(gateIdentity);
        String legacyGateIdentity = legacyGateIdentity(gateIdentity);

        String instanceName = GATE_KEY_TO_INSTANCE_NAME.remove(gateIdentity);
        if (instanceName == null && canonicalGateIdentity != null && !canonicalGateIdentity.equals(gateIdentity)) {
            instanceName = GATE_KEY_TO_INSTANCE_NAME.remove(canonicalGateIdentity);
        }
        if (instanceName == null && legacyGateIdentity != null && !legacyGateIdentity.equals(gateIdentity)) {
            instanceName = GATE_KEY_TO_INSTANCE_NAME.remove(legacyGateIdentity);
        }

        removePendingRangeByGateIdentity(gateIdentity);
        removePendingRangeByGateIdentity(canonicalGateIdentity);
        removePendingRangeByGateIdentity(legacyGateIdentity);

        GATE_INSTANCE_EXPECTATIONS.remove(gateIdentity);
        if (canonicalGateIdentity != null) {
            GATE_INSTANCE_EXPECTATIONS.remove(canonicalGateIdentity);
        }
        if (legacyGateIdentity != null) {
            GATE_INSTANCE_EXPECTATIONS.remove(legacyGateIdentity);
        }

        GATE_SPAWN_IN_FLIGHT.remove(gateIdentity);
        if (canonicalGateIdentity != null) {
            GATE_SPAWN_IN_FLIGHT.remove(canonicalGateIdentity);
        }
        if (legacyGateIdentity != null) {
            GATE_SPAWN_IN_FLIGHT.remove(legacyGateIdentity);
        }

        if (instanceName == null || instanceName.isBlank()) {
            GateInstancePersistenceManager.removeGateInstance(gateIdentity);
            if (canonicalGateIdentity != null && !canonicalGateIdentity.equals(gateIdentity)) {
                GateInstancePersistenceManager.removeGateInstance(canonicalGateIdentity);
            }
            if (legacyGateIdentity != null && !legacyGateIdentity.equals(gateIdentity)) {
                GateInstancePersistenceManager.removeGateInstance(legacyGateIdentity);
            }
            return;
        }

        InstancesPlugin instances = InstancesPlugin.get();
        if (instances != null) {
            try {
                instances.safeRemoveInstance(instanceName);
                log(Level.INFO,
                        "[ELPortal] Cleaned up paired instance %s for expired gate %s identity=%s",
                        instanceName,
                        blockId,
                        gateIdentity);
            } catch (Exception ex) {
                log(Level.WARNING,
                        "[ELPortal] Failed to remove instance %s for gate %s identity=%s: %s",
                        instanceName,
                        blockId,
                        gateIdentity,
                        ex.getMessage());
            }
        }

        ACTIVE_LEVEL_RANGES.remove(instanceName);
        ACTIVE_BOSS_LEVELS.remove(instanceName);
        ACTIVE_RANK_LETTERS.remove(instanceName);
        // Remove from disk persistence so stale entries don't survive restarts.
        GateInstancePersistenceManager.removeGateInstance(gateIdentity);
        if (canonicalGateIdentity != null && !canonicalGateIdentity.equals(gateIdentity)) {
            GateInstancePersistenceManager.removeGateInstance(canonicalGateIdentity);
        }
        if (legacyGateIdentity != null && !legacyGateIdentity.equals(gateIdentity)) {
            GateInstancePersistenceManager.removeGateInstance(legacyGateIdentity);
        }
    }

    /**
     * Returns the currently paired instance world name for a gate identity without removing it.
     * Tries the provided key, then the canonical and legacy forms.
     */
    @Nullable
    public static String resolveInstanceNameForGate(@Nonnull String gateIdentity) {
        String name = GATE_KEY_TO_INSTANCE_NAME.get(gateIdentity);
        if (name == null) {
            String canonical = canonicalizeGateIdentity(gateIdentity);
            name = GATE_KEY_TO_INSTANCE_NAME.get(canonical);
        }
        if (name == null) {
            String legacy = legacyGateIdentity(gateIdentity);
            if (legacy != null) {
                name = GATE_KEY_TO_INSTANCE_NAME.get(legacy);
            }
        }
        return name;
    }

    /**
     * Kicks every player currently inside {@code instanceWorldName} back to their saved entry
     * portal location (or world spawn as fallback). Safe to call on any thread; teleports are
     * dispatched via the target world's executor.
     */
    public static void kickPlayersFromGateInstance(@Nonnull String instanceWorldName) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        World instanceWorld = universe.getWorld(instanceWorldName);
        if (instanceWorld == null) {
            return;
        }
        List<Player> players = instanceWorld.getPlayers();
        if (players == null || players.isEmpty()) {
            return;
        }
        World returnWorld = universe.getDefaultWorld();
        for (Player player : players) {
            try {
                Ref<EntityStore> entityRef = player.getReference();
                if (entityRef == null || !entityRef.isValid()) {
                    continue;
                }
                Store<EntityStore> store = entityRef.getStore();
                PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
                if (playerRef == null) {
                    continue;
                }
                UUID playerUuid = playerRef.getUuid();
                ReturnTarget entry = playerUuid != null ? PLAYER_ENTRY_TARGETS.get(playerUuid) : null;
                if (entry != null && teleportToReturnTarget(playerRef, entry)) {
                    if (playerUuid != null) {
                        PLAYER_ENTRY_TARGETS.remove(playerUuid);
                    }
                    log(Level.INFO,
                            "[ELPortal] Kicked player %s from deleted instance %s → saved entry portal",
                            playerRef.getUsername(),
                            instanceWorldName);
                } else if (returnWorld != null) {
                    teleportPlayerToReturnWorld(playerRef, returnWorld);
                    log(Level.INFO,
                            "[ELPortal] Kicked player %s from deleted instance %s → default world",
                            playerRef.getUsername(),
                            instanceWorldName);
                } else {
                    fallbackReturnPlayerToWorldSpawn(playerRef, instanceWorld);
                    log(Level.WARNING,
                            "[ELPortal] Kicked player %s from deleted instance %s → world spawn fallback (no return world)",
                            playerRef.getUsername(),
                            instanceWorldName);
                }
            } catch (Exception ex) {
                log(Level.WARNING,
                        "[ELPortal] Failed to kick player from deleted instance %s: %s",
                        instanceWorldName,
                        ex.getMessage());
            }
        }
    }

    @Nonnull
    private static String buildGateKey(@Nonnull World world, int x, int y, int z) {
        UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
        String worldPart = worldUuid == null ? world.getName() : worldUuid.toString();
        return worldPart + ":" + x + ":" + y + ":" + z;
    }

    /**
     * Called by the gate manager when a portal block is placed so the announced
     * level range is preserved and used when a player enters that instance.
     */
    public static void setPendingLevelRange(@Nonnull String blockId,
                                            @Nullable String gateIdentity,
                                            int min,
                                            int max,
                                            int bossLevel) {
        String routingName = resolveRoutingName(blockId);
        if (routingName != null) {
            String rankLetter = resolveRankLetterFromBlockId(blockId);
            int bossOffset = Math.max(0, bossLevel - Math.max(min, max));
            PendingLevelProfile profile = new PendingLevelProfile(
                    min,
                    max,
                    bossOffset,
                    rankLetter);

            // Template-level pending data is a legacy fallback for flows that do not
            // carry a stable gate identity. Keep ranked profiles scoped to gate-id
            // entries so they cannot bleed into non-gate instance routing.
            if (gateIdentity == null || gateIdentity.isBlank()) {
                PENDING_LEVEL_RANGES.put(routingName, profile);
            }

            if (gateIdentity != null && !gateIdentity.isBlank()) {
                putPendingRangeByGateIdentity(gateIdentity, profile);
            }

            log(Level.WARNING,
                    "[ELPortal] Pending gate range captured block=%s gateId=%s template=%s range=%d-%d bossOffset=%d rank=%s",
                    blockId,
                    gateIdentity == null || gateIdentity.isBlank() ? "<none>" : gateIdentity,
                    routingName,
                    profile.min(),
                    profile.max(),
                    profile.bossLevelFromRangeMaxOffset(),
                    profile.rankLetter());
        }
    }

    public static void setPendingLevelRange(@Nonnull String blockId, int min, int max, int bossLevel) {
        setPendingLevelRange(blockId, null, min, max, bossLevel);
    }

    public static void setPendingLevelRange(@Nonnull String blockId, int min, int max) {
        setPendingLevelRange(blockId, min, max, Math.max(min, max));
    }

    public static boolean enterPortalFromBlockId(@Nonnull PlayerRef playerRef,
                                                 @Nonnull World sourceWorld,
                                                 @Nonnull String blockId) {
        Transform transform = playerRef.getTransform();
        int x = transform != null && transform.getPosition() != null ? (int) Math.floor(transform.getPosition().x) : 0;
        int y = transform != null && transform.getPosition() != null ? (int) Math.floor(transform.getPosition().y) : 0;
        int z = transform != null && transform.getPosition() != null ? (int) Math.floor(transform.getPosition().z) : 0;
        return enterPortalFromBlock(playerRef, sourceWorld, blockId, x, y, z);
    }

    public static boolean enterPortalFromBlock(@Nonnull PlayerRef playerRef,
                                                @Nonnull World sourceWorld,
                                                @Nonnull String blockId,
                                                int x,
                                                int y,
                                                int z) {
        return enterPortalFromBlock(playerRef, sourceWorld, blockId, x, y, z, null);
    }

    public static boolean enterPortalFromBlock(@Nonnull PlayerRef playerRef,
                                               @Nonnull World sourceWorld,
                                               @Nonnull String blockId,
                                               int x,
                                               int y,
                                               int z,
                                               @Nullable String stableGateId) {
        if (isDebugPreventEnterEnabled()) {
            playerRef.sendMessage(Message.raw("[Gate Debug] Entry blocked (prevententer=true)").color("#ff6666"));
            log(Level.WARNING,
                    "[ELPortal] Debug prevented gate entry player=%s world=%s block=%s at %d %d %d",
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    blockId,
                    x,
                    y,
                    z);
            return false;
        }

        String routingName = resolveRoutingName(blockId);
        if (routingName == null) {
            log(Level.WARNING,
                    "[ELPortal] No routing template found for placed portal block=%s world=%s",
                    blockId,
                    sourceWorld.getName());
            return false;
        }

        if (isRoutingThrottled(playerRef, routingName, sourceWorld.getName(), "portal-block")) {
            return false;
        }

        String worldNameGateId = deriveStableGateIdFromWorldName(sourceWorld);
        if (stableGateId != null && !stableGateId.isBlank()
                && worldNameGateId != null
                && !stableGateId.equals(worldNameGateId)) {
                log(Level.SEVERE,
                    "[ELPortal] Gate identity mismatch: tracked=%s worldNameDerived=%s player=%s world=%s block=%s at %d %d %d",
                    stableGateId,
                    worldNameGateId,
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    blockId,
                    x,
                    y,
                    z);
        }

        String gateKey;
        if (stableGateId != null && !stableGateId.isBlank()) {
            gateKey = stableGateId;
        } else if (worldNameGateId != null && !worldNameGateId.isBlank()) {
            gateKey = worldNameGateId;
            log(Level.WARNING,
                    "[ELPortal] Gate identity recovered from world name gateId=%s player=%s world=%s block=%s at %d %d %d",
                    gateKey,
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    blockId,
                    x,
                    y,
                    z);
        } else {
            String recoveredGateKey = recoverCanonicalGateIdentity(sourceWorld, blockId, x, y, z);
            gateKey = recoveredGateKey != null ? recoveredGateKey : buildGateKey(sourceWorld, x, y, z);
            log(Level.WARNING,
                    "[ELPortal] Gate identity fallback: missing stable gate id; using key=%s player=%s world=%s block=%s at %d %d %d",
                    gateKey,
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    blockId,
                    x,
                    y,
                    z);
                }

                String canonicalGateKey = canonicalizeGateIdentity(gateKey);
                String nearbyCanonicalKey = resolveNearbyCanonicalGateIdentity(sourceWorld, blockId, x, y, z, canonicalGateKey);
                if (nearbyCanonicalKey != null && !nearbyCanonicalKey.equals(canonicalGateKey)) {
                    log(Level.WARNING,
                        "[ELPortal] Canonical gate key snapped to nearby tracked gate: original=%s snapped=%s player=%s world=%s block=%s at %d %d %d",
                        canonicalGateKey,
                        nearbyCanonicalKey,
                        playerRef.getUsername(),
                        sourceWorld.getName(),
                        blockId,
                        x,
                        y,
                        z);
                    canonicalGateKey = nearbyCanonicalKey;
                }
                if (!canonicalGateKey.equals(gateKey)) {
                    log(Level.WARNING,
                            "[ELPortal] Canonicalized gate identity: original=%s canonical=%s player=%s world=%s block=%s at %d %d %d",
                            gateKey,
                            canonicalGateKey,
                            playerRef.getUsername(),
                            sourceWorld.getName(),
                            blockId,
                            x,
                            y,
                            z);
                    gateKey = canonicalGateKey;
                }
        logGateEntryExpectation(gateKey, blockId, routingName, playerRef.getUsername());
        rememberPendingGateEntry(playerRef, gateKey, blockId, routingName);
        // Try to route to existing instance for this gate, or create new one
        boolean started = routePlayerToGateInstance(playerRef,
                sourceWorld,
                gateKey,
                blockId,
                routingName,
                sourceWorld,
                playerRef.getTransform());
        if (!started) {
            clearRoutingThrottle(playerRef, routingName);
        }
        return started;
    }

    public static void onAddPlayerToWorld(@Nonnull AddPlayerToWorldEvent event) {
        World routingWorld = event.getWorld();
        String routingName = routingWorld.getName();

        PlayerRef playerRef = getPlayerRef(event.getHolder());
        if (playerRef == null) {
            return;
        }

        // Some portals add players directly into generated instance worlds like
        // "instance-EL_MJ_Instance_D03-<uuid>", bypassing the routing aliases.
        String directWorldSuffix = resolveSuffixFromWorldName(routingName);
        if (directWorldSuffix != null && !isRoutingTemplateWorldName(routingName)) {
            String directWorldTemplate = resolveTemplateNameFromWorldName(routingName);
            boolean originalTemplate = isOriginalTemplateName(directWorldTemplate);
            boolean gatePrefixedWorld = routingName.toLowerCase(Locale.ROOT).startsWith("el_gate_");

            if (isDebugPreventEnterEnabled()) {
                log(Level.WARNING,
                        "[ELPortal] Debug prevented direct-entry world access player=%s world=%s template=%s",
                        playerRef.getUsername(),
                        routingName,
                        directWorldTemplate == null ? "unknown" : directWorldTemplate);
                playerRef.sendMessage(Message.raw("[Gate Debug] Entry blocked (prevententer=true)").color("#ff6666"));
                queueBlockedDirectEntryReturn(playerRef, routingName);
                return;
            }

            PendingGateEntry pending = resolvePendingGateEntry(playerRef, directWorldTemplate);
            if (gatePrefixedWorld
                    && pending == null
                    && !hasRecentManagedEntryContext(playerRef)) {
                log(Level.SEVERE,
                        "[ELPortal] Blocking unmanaged direct entry into gate world player=%s world=%s template=%s",
                        playerRef.getUsername(),
                        routingName,
                        directWorldTemplate == null ? "unknown" : directWorldTemplate);
                queueBlockedDirectEntryReturn(playerRef, routingName);
                return;
            }

            Universe universe = Universe.get();
            if (universe != null) {
                World returnWorld = resolveReturnWorld(routingWorld, universe);
                Transform returnTransform = resolveReturnTransform(routingWorld, playerRef);

                // Ignore vanilla non-gate instance templates unless we have explicit gate-entry
                // context. This avoids false anti-bypass handling for normal MJ/Endgame portals.
                if (originalTemplate && pending == null) {
                    log(Level.INFO,
                            "[ELPortal] Ignoring non-gate direct instance world player=%s world=%s template=%s",
                            playerRef.getUsername(),
                            routingName,
                            directWorldTemplate == null ? "unknown" : directWorldTemplate);
                    return;
                }

                // Guard against rerouting loops: if we are already in a canonical gate world,
                // never invoke routePlayerToGateInstance again from direct-entry handling.
                if (!gatePrefixedWorld) {
                    if (pending == null) {
                        pending = resolveFallbackGateEntryForDirectWorld(directWorldTemplate);
                    }
                    if (pending != null) {
                        // Gate reroute: update the saved return target now that we have the context.
                        rememberEntryTarget(playerRef, returnWorld, returnTransform);
                        attemptDirectEntryGateReroute(
                                playerRef,
                                routingWorld,
                                routingName,
                                pending,
                                returnWorld,
                                returnTransform,
                                1);
                        return;
                    }
                    // No gate context to reroute through — block entry into the non-gate-prefixed
                    // instance world and return the player to their portal entry location.
                    // Do NOT call rememberEntryTarget here: the player still has a valid saved
                    // entry target from when they originally went through the gate, and overwriting
                    // it with instance-world coordinates (or a null transform mid-transition) would
                    // destroy the return point we need.  Defer the actual teleport to onPlayerReady
                    // so it fires after isWaitingForClientReady is cleared and the entity ref is live.
                    log(Level.SEVERE,
                            "[ELPortal] Blocking direct entry into non-gate-prefixed world player=%s world=%s" +
                            " template=%s — queuing deferred return to entry location",
                            playerRef.getUsername(),
                            routingName,
                            directWorldTemplate == null ? "unknown" : directWorldTemplate);
                    queueBlockedDirectEntryReturn(playerRef, routingName);
                    return;
                }
                // Normal el_gate_-prefixed direct-entry: remember the return target now.
                rememberEntryTarget(playerRef, returnWorld, returnTransform);
            }

            enforcePersistentInstanceLifecycle(routingWorld, "direct-entry");

            // Register the level range immediately so mobs are leveled correctly
            // before the player sends ClientReady (which fires ~4s later).
            if (!originalTemplate) {
                registerInstanceLevelOverride(routingName, null, directWorldTemplate);
            }

            backfillGatePairingFromDirectEntry(playerRef,
                    routingWorld,
                    routingName,
                    directWorldTemplate,
                    null);

            applyFixedGateSpawn(playerRef, routingWorld, directWorldSuffix);
            log(Level.INFO,
                "[ELPortal] Applied direct instance spawn correction player=%s world=%s suffix=%s template=%s",
                    playerRef.getUsername(),
                    routingName,
                directWorldSuffix,
                directWorldTemplate == null ? "unknown" : directWorldTemplate);
            return;
        }

        String suffix = ROUTING_TO_SUFFIX.get(routingName);
        if (suffix == null) {
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        World returnWorld = resolveReturnWorld(routingWorld, universe);
        Transform returnTransform = resolveReturnTransform(routingWorld, playerRef);

        if (isRoutingThrottled(playerRef, routingName, routingWorld.getName(), "routing-world-add")) {
            return;
        }

        PendingGateEntry pendingGateEntry = resolvePendingGateEntry(playerRef, routingName);
        if (pendingGateEntry != null) {
            boolean started = routePlayerToGateInstance(
                    playerRef,
                    routingWorld,
                    pendingGateEntry.gateIdentity(),
                    pendingGateEntry.blockId(),
                    pendingGateEntry.routingName(),
                    returnWorld,
                    returnTransform);
            if (!started) {
                clearRoutingThrottle(playerRef, routingName);
            }
            return;
        }

        // No gate context found — block template fallback that would produce a non-el_gate_ world name
        // and return the player to wherever they portal'd from so they can try again.
        log(Level.WARNING,
                "[ELPortal] Blocking no-gate-context template fallback player=%s template=%s — returning player to entry location",
                playerRef.getUsername(),
                routingName);
        clearRoutingThrottle(playerRef, routingName);
        teleportToReturnTarget(playerRef, new ReturnTarget(null, returnWorld.getName(), returnTransform));
    }

    private static void attemptDirectEntryGateReroute(@Nonnull PlayerRef playerRef,
                                                      @Nonnull World sourceWorld,
                                                      @Nonnull String sourceWorldName,
                                                      @Nonnull PendingGateEntry pending,
                                                      @Nonnull World returnWorld,
                                                      @Nullable Transform returnTransform,
                                                      int attempt) {
        boolean rerouted = routePlayerToGateInstance(
                playerRef,
                sourceWorld,
                pending.gateIdentity(),
                pending.blockId(),
                pending.routingName(),
                returnWorld,
                returnTransform);
        if (rerouted) {
            log(Level.WARNING,
                    "[ELPortal] Direct-entry forced reroute player=%s world=%s -> gateId=%s template=%s attempt=%d/%d",
                    playerRef.getUsername(),
                    sourceWorldName,
                    pending.gateIdentity(),
                    pending.routingName(),
                    attempt,
                    DIRECT_ENTRY_REROUTE_MAX_ATTEMPTS);
            return;
        }

        if (attempt >= DIRECT_ENTRY_REROUTE_MAX_ATTEMPTS) {
            log(Level.SEVERE,
                    "[ELPortal] Direct-entry reroute failed after retries player=%s world=%s gateId=%s template=%s",
                    playerRef.getUsername(),
                    sourceWorldName,
                    pending.gateIdentity(),
                    pending.routingName());
            return;
        }

        int nextAttempt = attempt + 1;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            Runnable retryTask = () -> attemptDirectEntryGateReroute(
                    playerRef,
                    sourceWorld,
                    sourceWorldName,
                    pending,
                    returnWorld,
                    returnTransform,
                    nextAttempt);

            if (executeOnPlayerWorldThread(playerRef, retryTask)) {
                return;
            }
            retryTask.run();
        }, DIRECT_ENTRY_REROUTE_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static boolean isRoutingThrottled(@Nonnull PlayerRef playerRef,
                                              @Nonnull String routingName,
                                              @Nonnull String sourceWorldName,
                                              @Nonnull String sourceReason) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        Map<String, Long> perTemplate = PLAYER_ROUTING_THROTTLES.computeIfAbsent(playerUuid,
                ignored -> new ConcurrentHashMap<>());
        perTemplate.entrySet().removeIf(entry -> entry.getValue() <= now);

        Long until = perTemplate.get(routingName);
        if (until != null && now < until) {
            log(Level.INFO,
                    "[ELPortal] Duplicate routing suppressed player=%s template=%s source=%s reason=%s",
                    playerRef.getUsername(),
                    routingName,
                    sourceWorldName,
                    sourceReason);
            return true;
        }

        perTemplate.put(routingName, now + ROUTING_DEDUPE_WINDOW_MILLIS);
        return false;
    }

    private static void clearRoutingThrottle(@Nonnull PlayerRef playerRef, @Nonnull String routingName) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        Map<String, Long> perTemplate = PLAYER_ROUTING_THROTTLES.get(playerUuid);
        if (perTemplate == null) {
            return;
        }

        perTemplate.remove(routingName);
        if (perTemplate.isEmpty()) {
            PLAYER_ROUTING_THROTTLES.remove(playerUuid);
        }
    }

    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        World world = player.getWorld();
        if (world == null) {
            return;
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return;
        }

        world.execute(() -> {
            if (!ref.isValid()) {
                return;
            }

            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }

            processPendingDeathReturnOnPlayerReady(playerRef, world);

            // If the player was blocked from entering a non-gate-prefixed instance world,
            // return them to their original entry location now that the entity ref is live.
            if (processPendingBlockReturnOnPlayerReady(playerRef, world)) {
                return;  // Skip applyFixedGateSpawn — player is being sent back out.
            }

            String suffix = resolveSuffixFromWorldName(world.getName());
            if (suffix == null) {
                return;
            }

            String worldName = world.getName();
            String templateName = resolveTemplateNameFromWorldName(worldName);
            if (!worldName.toLowerCase(Locale.ROOT).startsWith("el_gate_")
                    && isOriginalTemplateName(templateName)
                    && resolvePendingGateEntry(playerRef, templateName) == null) {
                log(Level.INFO,
                        "[ELPortal] PlayerReady ignoring non-gate instance world player=%s world=%s template=%s",
                        playerRef.getUsername(),
                        worldName,
                        templateName == null ? "unknown" : templateName);
                return;
            }

            log(Level.INFO,
                    "[ELPortal] PlayerReady re-apply spawn player=%s world=%s suffix=%s",
                    playerRef.getUsername(),
                    world.getName(),
                    suffix);
            applyFixedGateSpawn(playerRef, world, suffix);
        });
    }

    @Nonnull
    private static World resolveReturnWorld(@Nonnull World routingWorld, @Nonnull Universe universe) {
        InstanceWorldConfig cfg = InstanceWorldConfig.get(routingWorld.getWorldConfig());
        WorldReturnPoint rp = cfg != null ? cfg.getReturnPoint() : null;
        if (rp != null) {
            UUID returnUuid = rp.getWorld();
            if (returnUuid != null) {
                World returnWorld = universe.getWorld(returnUuid);
                if (returnWorld != null) {
                    return returnWorld;
                }
            }
        }
        return routingWorld;
    }

    @Nonnull
    private static Transform resolveReturnTransform(@Nonnull World routingWorld, @Nonnull PlayerRef playerRef) {
        InstanceWorldConfig cfg = InstanceWorldConfig.get(routingWorld.getWorldConfig());
        WorldReturnPoint rp = cfg != null ? cfg.getReturnPoint() : null;
        if (rp != null && rp.getReturnPoint() != null) {
            return rp.getReturnPoint();
        }
        return playerRef.getTransform();
    }

    /**
     * Routes player to a gate's instance. If the gate already has a paired instance running,
     * reuses it. Otherwise creates a new instance and registers the pairing.
     */
    private static boolean routePlayerToGateInstance(@Nonnull PlayerRef playerRef,
                                                     @Nonnull World sourceWorld,
                                                     @Nonnull String gateKey,
                                                     @Nonnull String blockId,
                                                     @Nonnull String routingName,
                                                     @Nonnull World returnWorld,
                                                     @Nullable Transform returnTransform) {
        if (!gateKey.startsWith("el_gate:")) {
            log(Level.WARNING,
                    "[ELPortal] Route-to-gate using non-canonical key=%s player=%s world=%s block=%s template=%s",
                    gateKey,
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    blockId,
                    routingName);
        }

        // Check if this gate already has a paired instance
        String existingInstance = GATE_KEY_TO_INSTANCE_NAME.get(gateKey);
        if (existingInstance != null) {
            cacheResolvedInstanceWorld(gateKey, existingInstance, blockId, routingName, "existing-pairing");
            Universe universe = Universe.get();
            if (universe != null) {
                World targetInstance = universe.getWorld(existingInstance);
                if (targetInstance != null) {
                    registerInstanceLevelOverride(targetInstance.getName(), gateKey, routingName);
                    // Reuse existing instance
                    if (teleportToInstanceSpawn(playerRef, targetInstance, playerRef.getTransform())) {
                        clearPendingGateEntry(playerRef);
                        rememberEntryTarget(playerRef, returnWorld, playerRef.getTransform());
                        log(Level.INFO, "[ELPortal] Reused existing gate instance %s for key=%s block=%s player=%s",
                                existingInstance, gateKey, blockId, playerRef.getUsername());
                        return true;
                    }
                }

                // Instance may be unloaded while still paired to an active gate; reopen it instead of spawning a new one.
                if (universe.isWorldLoadable(existingInstance)) {
                    if (!PAIRED_INSTANCE_RELOADS_IN_FLIGHT.add(existingInstance)) {
                        log(Level.WARNING,
                                "[ELPortal] Skipping duplicate paired gate reload world=%s gateId=%s block=%s player=%s",
                                existingInstance,
                                gateKey,
                                blockId,
                                playerRef.getUsername());
                        return true;
                    }

                    CompletableFuture<World> loadFuture = universe.loadWorld(existingInstance);
                    loadFuture.thenAccept(loadedWorld -> {
                        try {
                            if (loadedWorld == null) {
                                log(Level.WARNING,
                                        "[ELPortal] Paired gate world load returned null; keeping mapping gateId=%s world=%s",
                                        gateKey,
                                        existingInstance);
                                return;
                            }

                            enforcePersistentInstanceLifecycle(loadedWorld, "paired-instance-load");
                            registerInstanceLevelOverride(loadedWorld.getName(), gateKey, routingName);

                            Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
                            queueTeleportToInstanceSpawn(playerRef,
                                    loadedWorld,
                                    effectiveReturnTransform,
                                    () -> {
                                        clearPendingGateEntry(playerRef);
                                        cacheResolvedInstanceWorld(gateKey,
                                                loadedWorld.getName(),
                                                blockId,
                                                routingName,
                                                "reload-paired-instance");
                                        rememberEntryTarget(playerRef, returnWorld, effectiveReturnTransform);
                                        String suffix = resolveSuffixFromWorldName(loadedWorld.getName());
                                        if (suffix != null) {
                                            applyFixedGateSpawn(playerRef, loadedWorld, suffix);
                                        }
                                        log(Level.INFO,
                                                "[ELPortal] Reloaded paired gate instance %s for key=%s block=%s player=%s",
                                                existingInstance,
                                                gateKey,
                                                blockId,
                                                playerRef.getUsername());
                                    });
                        } finally {
                            PAIRED_INSTANCE_RELOADS_IN_FLIGHT.remove(existingInstance);
                        }
                    }).exceptionally(ex -> {
                        PAIRED_INSTANCE_RELOADS_IN_FLIGHT.remove(existingInstance);
                        Universe retryUniverse = Universe.get();
                        World racedWorld = retryUniverse == null ? null : retryUniverse.getWorld(existingInstance);
                        if (racedWorld != null) {
                            enforcePersistentInstanceLifecycle(racedWorld, "paired-instance-race-recover");
                            registerInstanceLevelOverride(racedWorld.getName(), gateKey, routingName);

                            Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
                            queueTeleportToInstanceSpawn(playerRef,
                                    racedWorld,
                                    effectiveReturnTransform,
                                    () -> {
                                        clearPendingGateEntry(playerRef);
                                        cacheResolvedInstanceWorld(gateKey,
                                                racedWorld.getName(),
                                                blockId,
                                                routingName,
                                                "paired-instance-race-recover");
                                        rememberEntryTarget(playerRef, returnWorld, effectiveReturnTransform);
                                        String suffix = resolveSuffixFromWorldName(racedWorld.getName());
                                        if (suffix != null) {
                                            applyFixedGateSpawn(playerRef, racedWorld, suffix);
                                        }
                                        log(Level.WARNING,
                                                "[ELPortal] Recovered paired-instance reload race world=%s key=%s block=%s player=%s",
                                                existingInstance,
                                                gateKey,
                                                blockId,
                                                playerRef.getUsername());
                                    });
                            return null;
                        }

                        AddonLoggingManager.log(plugin,
                                Level.WARNING,
                                ex,
                                "[ELPortal] Failed to reload paired gate instance %s for key=%s",
                                existingInstance,
                                gateKey);
                        return null;
                    });
                    return true;
                }
            }
                // Keep the mapping/expectation so respawn attempts can preserve the same world ID.
                log(Level.WARNING,
                    "[ELPortal] Paired gate world not loadable right now; keeping mapping gateId=%s world=%s",
                    gateKey,
                    existingInstance);
        }

        if (attemptRouteToExpectedWorldId(playerRef,
                gateKey,
                blockId,
                routingName,
                returnWorld,
                returnTransform)) {
            return true;
        }

        long now = System.currentTimeMillis();
        Long inflightSince = GATE_SPAWN_IN_FLIGHT.get(gateKey);
        if (inflightSince != null && (now - inflightSince) <= GATE_SPAWN_STALE_MILLIS) {
            log(Level.INFO,
                    "[ELPortal] Gate spawn already in-flight key=%s block=%s player=%s",
                    gateKey,
                    blockId,
                    playerRef.getUsername());
            return false;
        }
        if (inflightSince != null) {
            GATE_SPAWN_IN_FLIGHT.remove(gateKey, inflightSince);
            log(Level.WARNING,
                    "[ELPortal] Cleared stale gate spawn lock key=%s ageMs=%d",
                    gateKey,
                    now - inflightSince);
        }

        Long claimed = GATE_SPAWN_IN_FLIGHT.putIfAbsent(gateKey, now);
        if (claimed != null && (now - claimed) <= GATE_SPAWN_STALE_MILLIS) {
            log(Level.INFO,
                    "[ELPortal] Gate spawn claim denied key=%s block=%s player=%s",
                    gateKey,
                    blockId,
                    playerRef.getUsername());
            return false;
        }
        if (claimed != null) {
            GATE_SPAWN_IN_FLIGHT.put(gateKey, now);
            log(Level.WARNING,
                    "[ELPortal] Replaced stale gate spawn claim key=%s staleAgeMs=%d",
                    gateKey,
                    now - claimed);
        }

        // No existing instance, create new one
        return routePlayerToTemplate(playerRef, sourceWorld, gateKey, blockId, routingName, returnWorld, returnTransform);
    }

    private static boolean attemptRouteToExpectedWorldId(@Nonnull PlayerRef playerRef,
                                                         @Nonnull String gateKey,
                                                         @Nonnull String blockId,
                                                         @Nonnull String routingName,
                                                         @Nonnull World returnWorld,
                                                         @Nullable Transform returnTransform) {
        GateInstanceExpectation expectation = GATE_INSTANCE_EXPECTATIONS.get(gateKey);
        if (expectation == null || expectation.expectedWorldId() == null || expectation.expectedWorldId().isBlank()) {
            return false;
        }

        String expectedWorldId = expectation.expectedWorldId();
        Universe universe = Universe.get();
        if (universe == null) {
            return false;
        }

        World loadedWorld = universe.getWorld(expectedWorldId);
        if (loadedWorld != null) {
            enforcePersistentInstanceLifecycle(loadedWorld, "expected-world-loaded");
            registerInstanceLevelOverride(loadedWorld.getName(), gateKey, routingName);
            Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
            if (teleportToInstanceSpawn(playerRef, loadedWorld, effectiveReturnTransform)) {
                clearPendingGateEntry(playerRef);
                GATE_KEY_TO_INSTANCE_NAME.put(gateKey, loadedWorld.getName());
                cacheResolvedInstanceWorld(gateKey,
                        loadedWorld.getName(),
                        blockId,
                        routingName,
                        "expected-world-loaded");
                persistGateInstanceImmediate(gateKey, loadedWorld.getName(), blockId);
                rememberEntryTarget(playerRef, returnWorld, effectiveReturnTransform);
                String suffix = resolveSuffixFromWorldName(loadedWorld.getName());
                if (suffix != null) {
                    applyFixedGateSpawn(playerRef, loadedWorld, suffix);
                }
                log(Level.WARNING,
                        "[ELPortal] Recovered gate pairing from expected cached world gateId=%s worldId=%s block=%s player=%s",
                        gateKey,
                        expectedWorldId,
                        blockId,
                        playerRef.getUsername());
                return true;
            }
        }

        if (!universe.isWorldLoadable(expectedWorldId)) {
            log(Level.WARNING,
                    "[ELPortal] Cached expected world is not loadable; allowing new spawn gateId=%s expectedWorldId=%s block=%s template=%s",
                    gateKey,
                    expectedWorldId,
                    blockId,
                    routingName);
            return false;
        }

        universe.loadWorld(expectedWorldId)
                .thenAccept(reloadedWorld -> {
                    if (reloadedWorld == null) {
                        return;
                    }

                        enforcePersistentInstanceLifecycle(reloadedWorld, "expected-world-reload");
                        registerInstanceLevelOverride(reloadedWorld.getName(), gateKey, routingName);
                    Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
                    queueTeleportToInstanceSpawn(playerRef,
                            reloadedWorld,
                            effectiveReturnTransform,
                            () -> {
                            clearPendingGateEntry(playerRef);
                            GATE_KEY_TO_INSTANCE_NAME.put(gateKey, reloadedWorld.getName());
                            cacheResolvedInstanceWorld(gateKey,
                                reloadedWorld.getName(),
                                blockId,
                                routingName,
                                "expected-world-reload");
                            persistGateInstanceImmediate(gateKey, reloadedWorld.getName(), blockId);
                                rememberEntryTarget(playerRef, returnWorld, effectiveReturnTransform);
                                String suffix = resolveSuffixFromWorldName(reloadedWorld.getName());
                                if (suffix != null) {
                                    applyFixedGateSpawn(playerRef, reloadedWorld, suffix);
                                }
                                log(Level.WARNING,
                                        "[ELPortal] Reloaded expected cached world for gate gateId=%s worldId=%s block=%s player=%s",
                                        gateKey,
                                        expectedWorldId,
                                        blockId,
                                        playerRef.getUsername());
                            });
                })
                .exceptionally(ex -> {
                    AddonLoggingManager.log(plugin,
                            Level.WARNING,
                            ex,
                            "[ELPortal] Failed loading expected cached world gateId=%s expectedWorldId=%s",
                            gateKey,
                            expectedWorldId);
                    return null;
                });
        return true;
    }

    private static boolean routePlayerToTemplate(@Nonnull PlayerRef playerRef,
                                                 @Nonnull World sourceWorld,
                                                 @Nullable String gateKey,
                                                 @Nullable String gateBlockId,
                                                 @Nonnull String routingName,
                                                 @Nonnull World returnWorld,
                                                 @Nullable Transform returnTransform) {
        String displayName = ROUTING_TO_DISPLAY.getOrDefault(routingName, routingName);

        log(Level.INFO,
                "[ELPortal] Routing player=%s source=%s template=%s",
                playerRef.getUsername(),
                sourceWorld.getName(),
                routingName);

        InstancesPlugin instances = InstancesPlugin.get();
        if (instances == null) {
            log(Level.WARNING, "[ELPortal] InstancesPlugin unavailable — level routing skipped for %s",
                    playerRef.getUsername());
            return false;
        }

        Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
        rememberEntryTarget(playerRef, returnWorld, effectiveReturnTransform);
        
        String instanceWorldName = gateKey != null ? resolvePreferredGateInstanceWorldName(routingName, gateKey) : null;
        if (gateKey == null || gateKey.isBlank()) {
            log(Level.WARNING,
                    "[ELPortal] Spawning template without gate identity player=%s source=%s template=%s worldName=auto",
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    routingName);
        } else if (!gateKey.startsWith("el_gate:")) {
            log(Level.WARNING,
                    "[ELPortal] Spawning with non-canonical gate identity gateId=%s player=%s source=%s template=%s worldName=%s",
                    gateKey,
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    routingName,
                    instanceWorldName == null ? "auto" : instanceWorldName);
        }
        log(Level.INFO,
            "[ELPortal] Spawning routed instance player=%s template=%s gateId=%s worldName=%s",
            playerRef.getUsername(),
            routingName,
            gateKey == null ? "<none>" : gateKey,
            instanceWorldName == null ? "auto" : instanceWorldName);
        instances.spawnInstance(routingName, instanceWorldName, returnWorld, effectiveReturnTransform)
                .thenAccept(spawned -> {
                    try {
                        enforcePersistentInstanceLifecycle(spawned, "spawn-instance");
                        // Hard enforcement: world name must carry el_gate_ prefix.
                        // If Hytale fell back to an auto-generated "instance-<template>-<uuid>" name,
                        // reject the teleport and return the player to their portal entry location.
                        if (!spawned.getName().startsWith("el_gate_")) {
                            log(Level.SEVERE,
                                    "[ELPortal] MISMATCH: Spawned instance '%s' lacks el_gate_ prefix" +
                                    " (template=%s gateKey=%s) — returning player to entry portal",
                                    spawned.getName(),
                                    routingName,
                                    gateKey == null ? "<none>" : gateKey);
                            UUID mismatchUuid = playerRef.getUuid();
                            ReturnTarget mismatchEntry = mismatchUuid != null ? PLAYER_ENTRY_TARGETS.get(mismatchUuid) : null;
                            if (mismatchEntry != null) {
                                teleportToReturnTarget(playerRef, mismatchEntry);
                            } else {
                                fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
                            }
                            return; // finally block removes GATE_SPAWN_IN_FLIGHT
                        }
                        if (gateKey != null) {
                            GATE_KEY_TO_INSTANCE_NAME.put(gateKey, spawned.getName());
                            cacheResolvedInstanceWorld(gateKey,
                                    spawned.getName(),
                                    gateBlockId,
                                    routingName,
                                    "spawn-instance");
                            log(Level.INFO, "[ELPortal] Registered gate->instance pairing key=%s block=%s -> %s",
                                    gateKey,
                                    gateBlockId == null ? "unknown" : gateBlockId,
                                    spawned.getName());
                        }

                        LevelRange range = registerInstanceLevelOverride(spawned.getName(), gateKey, routingName);
                        if (gateKey != null) {
                            persistGateInstanceImmediate(gateKey, spawned.getName(), gateBlockId);
                        }
                        String suffix = ROUTING_TO_SUFFIX.get(routingName);
                        queueTeleportToInstanceSpawn(playerRef,
                            spawned,
                            effectiveReturnTransform,
                            () -> {
                                clearPendingGateEntry(playerRef);
                                if (suffix != null) {
                                applyFixedGateSpawn(playerRef, spawned, suffix);
                                }
                                log(Level.INFO,
                                    "[ELPortal] Created routed instance %s for template %s bracket %d-%d",
                                    spawned.getName(),
                                    routingName,
                                    range.min(),
                                    range.max());
                            });
                    } finally {
                        if (gateKey != null) {
                            GATE_SPAWN_IN_FLIGHT.remove(gateKey);
                        }
                    }
                })
                .exceptionally(ex -> {
                    if (gateKey != null) {
                        GATE_SPAWN_IN_FLIGHT.remove(gateKey);
                    }
                    AddonLoggingManager.log(plugin,
                            Level.WARNING,
                            ex,
                            "[ELPortal] Failed to spawn routed instance from template %s",
                            routingName);
                    return null;
                });
        return true;
    }

    public static boolean returnPlayerToEntryPortal(@Nonnull PlayerRef playerRef, @Nonnull World sourceWorld) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            log(Level.WARNING,
                    "[ELPortal] Custom return portal aborted: missing player UUID source=%s player=%s",
                    sourceWorld.getName(),
                    playerRef.getUsername());
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
            return true;
        }

        ReturnTarget saved = PLAYER_ENTRY_TARGETS.get(playerUuid);
        log(Level.INFO,
                "[ELPortal] Return portal lookup player=%s source=%s savedTarget=%s" +
                " savedWorldUuid=%s savedWorldName=%s savedTransform=%s",
                playerRef.getUsername(),
                sourceWorld.getName(),
                saved != null,
                saved != null && saved.worldUuid() != null ? saved.worldUuid().toString() : "null",
                saved != null ? saved.worldName() : "null",
                saved != null ? formatTransform(saved.returnTransform()) : "null");
        if (saved != null && teleportToReturnTarget(playerRef, saved)) {
            PLAYER_ENTRY_TARGETS.remove(playerUuid);
            log(Level.INFO,
                    "[ELPortal] Custom return portal used saved entry target player=%s source=%s target=%s transform=%s",
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    saved.worldName(),
                    formatTransform(saved.returnTransform()));
            return true;
        }

        ReturnTarget metadataTarget = resolveReturnTargetFromInstanceMetadata(playerRef, sourceWorld);
        if (metadataTarget != null && teleportToReturnTarget(playerRef, metadataTarget)) {
            log(Level.INFO,
                    "[ELPortal] Custom return portal used instance metadata player=%s source=%s target=%s transform=%s",
                    playerRef.getUsername(),
                    sourceWorld.getName(),
                    metadataTarget.worldName(),
                    formatTransform(metadataTarget.returnTransform()));
            return true;
        }

        // Fallback to world spawn with warning when normal targets unavailable
        log(Level.WARNING,
            "[ELPortal] Custom return portal targets unavailable, using world spawn fallback player=%s source=%s hasSavedTarget=%s hasMetadataTarget=%s",
            playerRef.getUsername(),
            sourceWorld.getName(),
            saved != null,
            metadataTarget != null);
        fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
        return true;
    }

    public static boolean shouldSuppressImmediateReturnPortal(@Nonnull PlayerRef playerRef,
                                                              @Nonnull World sourceWorld) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return false;
        }

        String worldName = sourceWorld.getName();
        if (worldName == null || (!worldName.startsWith("instance-") && !worldName.startsWith("el_gate_"))) {
            return false;
        }

        long now = System.currentTimeMillis();
        Long until = PLAYER_RETURN_PORTAL_SUPPRESSION_UNTIL.get(playerUuid);
        if (until == null) {
            return false;
        }

        if (now >= until) {
            PLAYER_RETURN_PORTAL_SUPPRESSION_UNTIL.remove(playerUuid, until);
            return false;
        }

        log(Level.INFO,
                "[ELPortal] Suppressed immediate return-portal trigger player=%s source=%s remainingMs=%d",
                playerRef.getUsername(),
                sourceWorld.getName(),
                until - now);
        return true;
    }

    public static boolean shouldHandleCustomReturnPortal(@Nonnull World sourceWorld) {
        String worldName = sourceWorld.getName();
        if (worldName == null || worldName.isBlank()) {
            return false;
        }

        String normalized = worldName.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("el_gate_")) {
            return true;
        }

        // Keep compatibility with legacy EL direct-entry worlds while excluding vanilla
        // MajorDungeons/PortalKey instances like instance-MJ_Instance_D01-<uuid>.
        return normalized.startsWith("instance-el_")
                || normalized.contains("-el_mj_instance_")
                || normalized.contains("-el_endgame_");
    }

    /**
     * Fallback return chain:
     * 1) nearest saved bed/respawn point in default world
     * 2) default world spawn
     */
    private static void fallbackReturnPlayerToWorldSpawn(@Nonnull PlayerRef playerRef, @Nonnull World sourceWorld) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                log(Level.WARNING, "[ELPortal] Cannot fallback: Universe unavailable player=%s", playerRef.getUsername());
                return;
            }

            World targetWorld = universe.getDefaultWorld();
            if (targetWorld == null) {
                targetWorld = sourceWorld;
            }

            Transform bedTransform = resolveNearestBedRespawnTransform(playerRef, targetWorld);
            if (bedTransform != null) {
                log(Level.WARNING,
                        "[ELPortal] Fallback return using nearest bed player=%s targetWorld=%s bed=%s",
                        playerRef.getUsername(),
                        targetWorld.getName(),
                        formatTransform(bedTransform));

                if (teleportToWorld(playerRef, targetWorld, bedTransform)) {
                    log(Level.INFO,
                            "[ELPortal] Fallback return to nearest bed successful player=%s world=%s bed=%s",
                            playerRef.getUsername(),
                            targetWorld.getName(),
                            formatTransform(bedTransform));
                    return;
                }

                log(Level.INFO,
                        "[ELPortal] Fallback bed teleport deferred/failed; continuing to world spawn player=%s world=%s",
                        playerRef.getUsername(),
                        targetWorld.getName());
            }

            Transform spawnTransform = null;
            if (targetWorld.getWorldConfig() != null && targetWorld.getWorldConfig().getSpawnProvider() != null) {
                spawnTransform = targetWorld.getWorldConfig().getSpawnProvider().getSpawnPoint(targetWorld, playerRef.getUuid());
            }

            if (spawnTransform == null) {
                // Last-resort hardcoded safe-ish position
                spawnTransform = new Transform(0.0, 64.0, 0.0);
            }

            log(Level.WARNING,
                    "[ELPortal] Fallback return player=%s targetWorld=%s spawn=%s",
                    playerRef.getUsername(), targetWorld.getName(), formatTransform(spawnTransform));

            if (teleportToWorld(playerRef, targetWorld, spawnTransform)) {
                log(Level.INFO,
                    "[ELPortal] Fallback return to world spawn successful player=%s world=%s spawn=%s",
                    playerRef.getUsername(),
                    targetWorld.getName(),
                    formatTransform(spawnTransform));
            } else {
                log(Level.INFO,
                        "[ELPortal] Fallback teleport deferred retry player=%s world=%s delayMs=%d",
                        playerRef.getUsername(),
                        targetWorld.getName(),
                        FALLBACK_TELEPORT_RETRY_DELAY_MILLIS);
                queueFallbackTeleportRetry(playerRef, targetWorld, spawnTransform, 1);
            }
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Fallback teleport exception player=%s error=%s", playerRef.getUsername(), ex.getMessage());
        }
    }

    @Nullable
    private static Transform resolveNearestBedRespawnTransform(@Nonnull PlayerRef playerRef, @Nonnull World targetWorld) {
        PlayerRef livePlayerRef = resolveLivePlayerRef(playerRef);
        Ref<EntityStore> entityRef = livePlayerRef == null ? null : livePlayerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return null;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            Player playerComponent = store.getComponent(entityRef, Player.getComponentType());
            if (playerComponent == null) {
                return null;
            }

            Object playerConfigData = playerComponent.getClass().getMethod("getPlayerConfigData").invoke(playerComponent);
            if (playerConfigData == null) {
                return null;
            }

            Object perWorldData = playerConfigData.getClass()
                    .getMethod("getPerWorldData", String.class)
                    .invoke(playerConfigData, targetWorld.getName());
            if (perWorldData == null) {
                return null;
            }

            Object respawnPointsRaw = perWorldData.getClass().getMethod("getRespawnPoints").invoke(perWorldData);
            if (respawnPointsRaw == null || !respawnPointsRaw.getClass().isArray()) {
                return null;
            }

            int count = java.lang.reflect.Array.getLength(respawnPointsRaw);
            if (count <= 0) {
                return null;
            }

            Transform currentTransform = livePlayerRef.getTransform();
            Vector3d currentPosition = currentTransform != null && currentTransform.getPosition() != null
                    ? currentTransform.getPosition()
                    : new Vector3d(0.0, 0.0, 0.0);

            Vector3d best = null;
            double bestDistanceSq = Double.MAX_VALUE;

            for (int i = 0; i < count; i++) {
                Object respawnPoint = java.lang.reflect.Array.get(respawnPointsRaw, i);
                if (respawnPoint == null) {
                    continue;
                }

                Object respawnPosRaw = respawnPoint.getClass().getMethod("getRespawnPosition").invoke(respawnPoint);
                if (!(respawnPosRaw instanceof Vector3d respawnPos)) {
                    continue;
                }

                double distanceSq = currentPosition.distanceSquaredTo(respawnPos.x, currentPosition.y, respawnPos.z);
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    best = respawnPos;
                }
            }

            if (best == null) {
                return null;
            }

            return new Transform(best, Vector3f.ZERO);
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin,
                    Level.FINE,
                    ex,
                    "[ELPortal] Failed to resolve nearest bed fallback player=%s world=%s",
                    playerRef.getUsername(),
                    targetWorld.getName());
            return null;
        }
    }

    private static void queueFallbackTeleportRetry(@Nonnull PlayerRef playerRef,
                                                   @Nonnull World targetWorld,
                                                   @Nonnull Transform spawnTransform,
                                                   int attempt) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            Runnable retryTask = () -> {
                if (teleportToWorld(playerRef, targetWorld, spawnTransform)) {
                    log(Level.INFO,
                            "[ELPortal] Fallback retry teleport succeeded player=%s world=%s spawn=%s attempt=%d/%d",
                            playerRef.getUsername(),
                            targetWorld.getName(),
                            formatTransform(spawnTransform),
                            attempt,
                            FALLBACK_TELEPORT_MAX_RETRIES);
                } else {
                    if (attempt < FALLBACK_TELEPORT_MAX_RETRIES) {
                        queueFallbackTeleportRetry(playerRef, targetWorld, spawnTransform, attempt + 1);
                        log(Level.INFO,
                                "[ELPortal] Fallback retry still pending player=%s world=%s attempt=%d/%d",
                                playerRef.getUsername(),
                                targetWorld.getName(),
                                attempt,
                                FALLBACK_TELEPORT_MAX_RETRIES);
                    } else {
                        log(Level.WARNING,
                                "[ELPortal] Fallback teleport failed player=%s world=%s attempts=%d",
                                playerRef.getUsername(),
                                targetWorld.getName(),
                                attempt);
                    }
                }
            };

            if (executeOnPlayerWorldThread(playerRef, retryTask)) {
                return;
            }

            if (attempt < FALLBACK_TELEPORT_MAX_RETRIES) {
                queueFallbackTeleportRetry(playerRef, targetWorld, spawnTransform, attempt + 1);
                log(Level.INFO,
                        "[ELPortal] Fallback retry deferred: player world thread unavailable player=%s world=%s attempt=%d/%d",
                        playerRef.getUsername(),
                        targetWorld.getName(),
                        attempt,
                        FALLBACK_TELEPORT_MAX_RETRIES);
                return;
            }

            log(Level.WARNING,
                    "[ELPortal] Fallback retry aborted: player world thread unavailable player=%s world=%s attempts=%d",
                    playerRef.getUsername(),
                    targetWorld.getName(),
                    attempt);
        }, FALLBACK_TELEPORT_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Restores player entry target when they respawn after death.
     * Called from PortalInstanceDiagnostics when player returns to instance post-death.
     */
    public static void restorePlayerEntryTarget(@Nonnull UUID playerUuid,
                                                 @Nonnull String returnWorldName,
                                                 @Nonnull Transform returnTransform) {
        PLAYER_ENTRY_TARGETS.put(playerUuid, new ReturnTarget(null, returnWorldName, returnTransform));
    }

    @Nullable
    public static ReturnTargetDiagnostics resolveReturnTargetDiagnostics(@Nonnull PlayerRef playerRef,
                                                                         @Nonnull World sourceWorld) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid != null) {
            ReturnTarget saved = PLAYER_ENTRY_TARGETS.get(playerUuid);
            if (saved != null) {
                return new ReturnTargetDiagnostics("saved-entry-target", saved.worldName(), saved.returnTransform());
            }
        }

        ReturnTarget metadata = resolveReturnTargetFromInstanceMetadata(playerRef, sourceWorld);
        if (metadata != null) {
            return new ReturnTargetDiagnostics("instance-metadata", metadata.worldName(), metadata.returnTransform());
        }

        return null;
    }

    /** Minimum blocks to push players away from the gate entry when returning. */
    private static final double RETURN_OFFSET_MIN_BLOCKS = 5.0;
    /** Maximum blocks to probe away from gate entry when nearby tiles are blocked. */
    private static final double RETURN_OFFSET_MAX_BLOCKS = 15.0;
    /** Integer step size for probing offset return positions between min and max. */
    private static final int RETURN_OFFSET_STEP_BLOCKS = 1;
    /** Simple shared air-id assumption used throughout this addon for clearance checks. */
    private static final int AIR_BLOCK_ID = 0;

        /**
         * Tries to return the player near their portal entry (offset backwards) after death.
         * Falls back to configured world spawn when the entry return cannot be applied.
         */
    public static void teleportPlayerToDeathReturnEntry(
            @Nonnull PlayerRef playerRef,
            @Nullable String returnWorldName,
            @Nullable Transform returnTransform,
            @Nonnull World sourceWorld) {
        if (returnWorldName == null || returnWorldName.isBlank() || returnTransform == null) {
            log(Level.WARNING,
                    "[ELPortal] Death-return: missing entry target, using fallback spawn player=%s returnWorld=%s hasTransform=%s",
                    playerRef.getUsername(),
                    returnWorldName != null ? returnWorldName : "null",
                    returnTransform != null);
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
            return;
        }

        Universe universe = Universe.get();
        World targetReturnWorld = universe == null ? null : universe.getWorld(returnWorldName);
        Transform offsetTransform = targetReturnWorld != null
            ? computeReturnOffsetWithClearance(targetReturnWorld, returnTransform)
            : computeFallbackReturnOffset(returnTransform, RETURN_OFFSET_MIN_BLOCKS);
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            log(Level.WARNING,
                    "[ELPortal] Death-return: missing player UUID, using fallback spawn player=%s",
                    playerRef.getUsername());
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
            return;
        }

        PLAYER_PENDING_DEATH_RETURNS.put(playerUuid,
                new PendingDeathReturn(returnWorldName,
                    returnTransform,
                        sourceWorld.getName(),
                        System.currentTimeMillis()));

        log(Level.INFO,
                "[ELPortal] Death-return queued for PlayerReady player=%s world=%s base=%s offsetPlan=%s",
                playerRef.getUsername(),
                returnWorldName,
                formatTransform(returnTransform),
                formatTransform(offsetTransform));
    }

    /**
     * If the player was blocked from entering a non-gate-prefixed instance world, this fires on
     * PlayerReady (post-ClientReady) to perform the deferred return teleport with a live entity ref.
     *
     * @return true when a pending block return was found and acted upon (caller should skip
     *         applyFixedGateSpawn to avoid re-anchoring the player inside the instance)
     */
    private static boolean processPendingBlockReturnOnPlayerReady(@Nonnull PlayerRef playerRef,
                                                                   @Nonnull World readyWorld) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return false;
        }

        String blockedWorldName = PLAYER_PENDING_BLOCK_RETURNS.remove(playerUuid);
        if (blockedWorldName == null) {
            return false;
        }

        // Verify this PlayerReady fired for the same world we blocked (worlds match case-insensitively).
        if (!readyWorld.getName().equalsIgnoreCase(blockedWorldName)) {
            log(Level.WARNING,
                    "[ELPortal] Block-return: PlayerReady world mismatch player=%s expected=%s ready=%s \u2014 discarding",
                    playerRef.getUsername(), blockedWorldName, readyWorld.getName());
            return false;
        }

        log(Level.INFO,
                "[ELPortal] Block-return: executing deferred return player=%s world=%s",
                playerRef.getUsername(), readyWorld.getName());

        // Re-apply the grace period to prevent the proximity scanner from consuming the saved
        // entry target concurrently while we are in the middle of teleporting the player out.
        PortalProximityManager.markPlayerEnterInstance(playerUuid);

        boolean returned = returnPlayerToEntryPortal(playerRef, readyWorld);
        if (!returned) {
            log(Level.WARNING,
                    "[ELPortal] Block-return: returnPlayerToEntryPortal failed player=%s world=%s \u2014 using spawn fallback",
                    playerRef.getUsername(), readyWorld.getName());
            fallbackReturnPlayerToWorldSpawn(playerRef, readyWorld);
        }
        return true;
    }

    private static void processPendingDeathReturnOnPlayerReady(@Nonnull PlayerRef playerRef,
                                                                @Nonnull World readyWorld) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        PendingDeathReturn pending = PLAYER_PENDING_DEATH_RETURNS.remove(playerUuid);
        if (pending == null) {
            return;
        }

        Universe universe = Universe.get();
        World targetWorld = universe == null ? null : universe.getWorld(pending.returnWorldName());
        if (targetWorld == null) {
            log(Level.WARNING,
                    "[ELPortal] Death-return PlayerReady target unavailable player=%s target=%s; using fallback",
                    playerRef.getUsername(),
                    pending.returnWorldName());
            fallbackReturnPlayerToWorldSpawn(playerRef, readyWorld);
            return;
        }

        World sourceWorld = universe == null ? null : universe.getWorld(pending.sourceWorldName());
        if (sourceWorld == null) {
            sourceWorld = readyWorld;
        }

        log(Level.INFO,
                "[ELPortal] Death-return executing on PlayerReady player=%s readyWorld=%s target=%s ageMs=%d",
                playerRef.getUsername(),
                readyWorld.getName(),
                targetWorld.getName(),
                System.currentTimeMillis() - pending.createdAtMillis());

        Transform offsetTransform = computeReturnOffsetWithClearance(targetWorld, pending.returnTransform());

        // We are already on the world thread (dispatched via world.execute() in onPlayerReady),
        // so attempt the gate-offset teleport directly rather than round-tripping through SCHEDULED_EXECUTOR.
        if (teleportToWorld(playerRef, targetWorld, offsetTransform)) {
            log(Level.INFO,
                    "[ELPortal] Death-return: gate-offset succeeded immediately on PlayerReady player=%s world=%s",
                    playerRef.getUsername(),
                    targetWorld.getName());
            return;
        }

        // First attempt failed; queue scheduled retries before escalating to bed → spawn fallback.
        log(Level.INFO,
                "[ELPortal] Death-return: gate-offset attempt 1 failed on PlayerReady player=%s world=%s; queuing %d retries",
                playerRef.getUsername(),
                targetWorld.getName(),
                DEATH_RETURN_MAX_RETRIES - 1);
        queueDeathReturnOffsetRetry(playerRef,
                targetWorld,
            offsetTransform,
                sourceWorld,
                2,
                DEATH_RETURN_RETRY_DELAY_MILLIS);
    }

    private static void queueDeathReturnOffsetRetry(@Nonnull PlayerRef playerRef,
                                                    @Nonnull World targetWorld,
                                                    @Nonnull Transform targetTransform,
                                                    @Nonnull World sourceWorld,
                                                    int attempt,
                                                    long delayMillis) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            Runnable retryTask = () -> {
                if (teleportToWorld(playerRef, targetWorld, targetTransform)) {
                    log(Level.INFO,
                            "[ELPortal] Death-return: entry-offset retry succeeded player=%s world=%s attempt=%d/%d",
                            playerRef.getUsername(),
                            targetWorld.getName(),
                            attempt,
                            DEATH_RETURN_MAX_RETRIES);
                    return;
                }

                if (attempt < DEATH_RETURN_MAX_RETRIES) {
                    queueDeathReturnOffsetRetry(
                        playerRef,
                        targetWorld,
                        targetTransform,
                        sourceWorld,
                        attempt + 1,
                        DEATH_RETURN_RETRY_DELAY_MILLIS);
                    log(Level.INFO,
                            "[ELPortal] Death-return: entry-offset retry pending player=%s world=%s attempt=%d/%d",
                            playerRef.getUsername(),
                            targetWorld.getName(),
                            attempt,
                            DEATH_RETURN_MAX_RETRIES);
                    return;
                }

                log(Level.SEVERE,
                        "[ELPortal] Death-return: gate-offset FAILED after all retries player=%s targetWorld=%s lastAttempt=%d" +
                        " — escalating to bed then world-spawn fallback",
                        playerRef.getUsername(),
                        targetWorld.getName(),
                        attempt);
                fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
            };

            if (executeOnPlayerWorldThread(playerRef, retryTask)) {
                return;
            }

            if (attempt < DEATH_RETURN_MAX_RETRIES) {
                queueDeathReturnOffsetRetry(
                        playerRef,
                        targetWorld,
                        targetTransform,
                        sourceWorld,
                        attempt + 1,
                        DEATH_RETURN_RETRY_DELAY_MILLIS);
                log(Level.INFO,
                        "[ELPortal] Death-return deferred: player world thread unavailable player=%s world=%s attempt=%d/%d",
                        playerRef.getUsername(),
                        targetWorld.getName(),
                        attempt,
                        DEATH_RETURN_MAX_RETRIES);
                return;
            }

            log(Level.SEVERE,
                    "[ELPortal] Death-return: gate-offset ABORTED (world thread unavailable) player=%s targetWorld=%s lastAttempt=%d" +
                    " — escalating to bed then world-spawn fallback",
                    playerRef.getUsername(),
                    targetWorld.getName(),
                    attempt);
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Computes an offset return transform with world clearance checks.
     * Scans from 5 to 15 blocks backward from entry and picks the first safe 2-block-high spot.
     */
    @Nonnull
    private static Transform computeReturnOffsetWithClearance(@Nonnull World world,
                                                              @Nonnull Transform entryTransform) {
        Vector3d pos = entryTransform.getPosition();
        Vector3f rot = entryTransform.getRotation();

        double backwardX;
        double backwardZ;
        float yaw = rot.getYaw();
        if (!Float.isNaN(yaw)) {
            backwardX = Math.sin(yaw);
            backwardZ = Math.cos(yaw);
        } else {
            backwardX = 0.0;
            backwardZ = 1.0;
        }

        int baseBlockY = (int) Math.floor(pos.y);
        for (int distance = (int) RETURN_OFFSET_MIN_BLOCKS; distance <= (int) RETURN_OFFSET_MAX_BLOCKS; distance += RETURN_OFFSET_STEP_BLOCKS) {
            double candidateX = pos.x + (backwardX * distance);
            double candidateZ = pos.z + (backwardZ * distance);

            int blockX = (int) Math.floor(candidateX);
            int blockZ = (int) Math.floor(candidateZ);

            // Probe a few Y variants to handle minor terrain/portal-frame height differences.
            int[] yCandidates = new int[] {baseBlockY, baseBlockY + 1, baseBlockY - 1, baseBlockY + 2, baseBlockY - 2};
            for (int candidateY : yCandidates) {
                if (!isSafeReturnStandingSpot(world, blockX, candidateY, blockZ)) {
                    continue;
                }

                return new Transform(
                        new Vector3d(candidateX, candidateY, candidateZ),
                        rot);
            }
        }

        // No clear spot found in 5..15 range; keep old behavior and let retry/fallback chain handle it.
        return computeFallbackReturnOffset(entryTransform, RETURN_OFFSET_MIN_BLOCKS);
    }

    @Nonnull
    private static Transform computeFallbackReturnOffset(@Nonnull Transform entryTransform,
                                                         double distance) {
        Vector3d pos = entryTransform.getPosition();
        Vector3f rot = entryTransform.getRotation();

        double offsetX = 0.0;
        double offsetZ = 0.0;
        float yaw = rot.getYaw();
        if (!Float.isNaN(yaw)) {
            offsetX = Math.sin(yaw) * distance;
            offsetZ = Math.cos(yaw) * distance;
        } else {
            offsetZ = distance;
        }

        return new Transform(new Vector3d(pos.x + offsetX, pos.y, pos.z + offsetZ), rot);
    }

    private static boolean isSafeReturnStandingSpot(@Nonnull World world,
                                                    int blockX,
                                                    int blockY,
                                                    int blockZ) {
        long chunkIndex = ChunkUtil.indexChunkFromBlock(blockX, blockZ);
        WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
        if (chunk == null) {
            return false;
        }

        int footBlock = chunk.getBlock(blockX, blockY, blockZ);
        int headBlock = chunk.getBlock(blockX, blockY + 1, blockZ);
        int supportBlock = chunk.getBlock(blockX, blockY - 1, blockZ);
        return footBlock == AIR_BLOCK_ID
                && headBlock == AIR_BLOCK_ID
                && supportBlock != AIR_BLOCK_ID;
    }

    @Nonnull
    private static String formatTransform(@Nullable Transform transform) {
        if (transform == null || transform.getPosition() == null) {
            return "null";
        }
        return String.format(Locale.ROOT,
                "(%.2f, %.2f, %.2f)",
                transform.getPosition().x,
                transform.getPosition().y,
                transform.getPosition().z);
    }

    private static void rememberEntryTarget(@Nonnull PlayerRef playerRef,
                                            @Nonnull World world,
                                            @Nullable Transform transform) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        if (transform == null) {
            // playerRef.getTransform() can return null mid-transition; never overwrite a valid
            // saved target with a null-transform entry that would silently fail on use.
            ReturnTarget existing = PLAYER_ENTRY_TARGETS.get(playerUuid);
            if (existing != null && existing.returnTransform() != null) {
                log(Level.FINE,
                        "[ELPortal] rememberEntryTarget: null transform player=%s world=%s — preserving existing valid target",
                        playerRef.getUsername(), world.getName());
                return;
            }
            log(Level.WARNING,
                    "[ELPortal] rememberEntryTarget: null transform player=%s world=%s — no existing target to preserve, skipping store",
                    playerRef.getUsername(), world.getName());
            return;
        }
        PLAYER_ENTRY_TARGETS.put(playerUuid, new ReturnTarget(null, world.getName(), transform));
    }

    private static boolean teleportToReturnTarget(@Nonnull PlayerRef playerRef, @Nonnull ReturnTarget target) {
        Universe universe = Universe.get();
        if (universe == null || target.returnTransform() == null) {
            log(Level.WARNING,
                    "[ELPortal] teleportToReturnTarget: skipped player=%s universeNull=%s transformNull=%s",
                    playerRef.getUsername(),
                    universe == null,
                    target.returnTransform() == null);
            return false;
        }

        World world = target.worldUuid() != null ? universe.getWorld(target.worldUuid()) : null;
        boolean foundByUuid = world != null;
        if (world == null && !target.worldName().isBlank()) {
            world = universe.getWorld(target.worldName());
        }

        log(Level.INFO,
                "[ELPortal] teleportToReturnTarget player=%s targetWorldUuid=%s targetWorldName=%s" +
                " foundByUuid=%s foundByName=%s transform=%s",
                playerRef.getUsername(),
                target.worldUuid() != null ? target.worldUuid().toString() : "null",
                target.worldName(),
                foundByUuid,
                !foundByUuid && world != null,
                formatTransform(target.returnTransform()));

        if (world == null) {
            log(Level.WARNING,
                    "[ELPortal] teleportToReturnTarget: world not found player=%s uuid=%s name=%s",
                    playerRef.getUsername(),
                    target.worldUuid() != null ? target.worldUuid().toString() : "null",
                    target.worldName());
        }

        if (world == null) {
            return false;
        }

        Transform adjustedReturn = computeReturnOffsetWithClearance(world, target.returnTransform());
        return teleportToWorld(playerRef, world, adjustedReturn);
    }

    @Nullable
    private static ReturnTarget resolveReturnTargetFromInstanceMetadata(@Nonnull PlayerRef playerRef,
                                                                        @Nonnull World sourceWorld) {
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return null;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            InstanceEntityConfig entityConfig = store.getComponent(entityRef, InstanceEntityConfig.getComponentType());
            if (entityConfig != null) {
                ReturnTarget fromOverride = extractReturnTarget(entityConfig.getReturnPointOverride(), "override");
                if (fromOverride != null) {
                    return fromOverride;
                }
                ReturnTarget fromReturnPoint = extractReturnTarget(entityConfig.getReturnPoint(), "returnPoint");
                if (fromReturnPoint != null) {
                    return fromReturnPoint;
                }
            }
        } catch (Exception ex) {
            log(Level.FINE,
                    "[ELPortal] Failed to inspect instance entity config for %s: %s",
                    playerRef.getUsername(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }

        InstanceWorldConfig worldConfig = InstanceWorldConfig.get(sourceWorld.getWorldConfig());
        WorldReturnPoint worldReturnPoint = worldConfig != null ? worldConfig.getReturnPoint() : null;
        if (worldReturnPoint == null || worldReturnPoint.getWorld() == null || worldReturnPoint.getReturnPoint() == null) {
            return null;
        }

        World world = Universe.get() != null ? Universe.get().getWorld(worldReturnPoint.getWorld()) : null;
        String worldName = world != null ? world.getName() : "unknown";
        return new ReturnTarget(worldReturnPoint.getWorld(), worldName, worldReturnPoint.getReturnPoint());
    }

    @Nullable
    private static ReturnTarget extractReturnTarget(@Nullable Object source, @Nonnull String sourceName) {
        if (source == null) {
            return null;
        }

        if (source instanceof WorldReturnPoint worldReturnPoint) {
            UUID worldUuid = worldReturnPoint.getWorld();
            Transform transform = worldReturnPoint.getReturnPoint();
            if (worldUuid != null && transform != null) {
                Universe universe = Universe.get();
                World world = universe != null ? universe.getWorld(worldUuid) : null;
                return new ReturnTarget(worldUuid, world != null ? world.getName() : "unknown", transform);
            }
            return null;
        }

        if (source instanceof Transform transform) {
            // Transform-only payload has no world context, so it cannot be used for cross-world return.
            return null;
        }

        try {
            Object worldObj = source.getClass().getMethod("getWorld").invoke(source);
            Object transformObj = source.getClass().getMethod("getReturnPoint").invoke(source);
            if (!(worldObj instanceof UUID worldUuid) || !(transformObj instanceof Transform transform)) {
                return null;
            }
            Universe universe = Universe.get();
            World world = universe != null ? universe.getWorld(worldUuid) : null;
            return new ReturnTarget(worldUuid, world != null ? world.getName() : "unknown", transform);
        } catch (ReflectiveOperationException ignored) {
            // Unknown return-point payload shape; safely skip and continue fallbacks.
        } catch (Exception ex) {
            log(Level.FINE,
                    "[ELPortal] Failed to parse %s payload type=%s: %s",
                    sourceName,
                    source.getClass().getName(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        return null;
    }

    @Nullable
    private static PlayerRef getPlayerRef(@Nonnull Holder<EntityStore> holder) {
        return holder.getComponent(PlayerRef.getComponentType());
    }

    private static PendingLevelProfile resolveDynamicInstanceRange(@Nonnull String worldName,
                                                                   @Nullable String gateIdentity,
                                                                   @Nullable String templateHint) {
        if (gateIdentity != null && !gateIdentity.isBlank()) {
            // Keep gate-scoped profiles sticky until gate cleanup so retries/races do not
            // consume the announced range before the final routed instance is established.
            PendingLevelProfile gatePending = resolvePendingRangeByGateIdentity(gateIdentity);
            if (gatePending != null) {
                log(Level.WARNING,
                        "[ELPortal] Resolve dynamic range source=gate-pending world=%s gateId=%s range=%d-%d bossOffset=%d rank=%s",
                        worldName,
                        gateIdentity,
                        gatePending.min(),
                        gatePending.max(),
                        gatePending.bossLevelFromRangeMaxOffset(),
                        gatePending.rankLetter());
                return gatePending;
            }
        }

        // If a gate was placed for this template, use its announced range (consumed once).
        String templateName = templateHint;
        if (templateName == null || templateName.isBlank()) {
            templateName = resolveTemplateNameFromWorldName(worldName);
        }
        if (templateName != null && !templateName.isBlank()) {
            templateName = canonicalizeRoutingTemplate(templateName);
        }
        if (templateName != null) {
            PendingLevelProfile pending = PENDING_LEVEL_RANGES.remove(templateName);
            if (pending != null) {
                log(Level.WARNING,
                        "[ELPortal] Resolve dynamic range source=template-pending world=%s template=%s range=%d-%d bossOffset=%d rank=%s",
                        worldName,
                        templateName,
                        pending.min(),
                        pending.max(),
                        pending.bossLevelFromRangeMaxOffset(),
                        pending.rankLetter());
                return pending;
            }
        }

        // Fallback: when pending data is missing, derive a safe E-tier profile from
        // current dungeon gate config instead of world-name hashing.
        int start = clampDynamicLevel(DYNAMIC_MIN_LEVEL + resolveFallbackRankFloorEMinOffset());
        int end = clampDynamicLevel(start + resolveFallbackNormalMobLevelRange());
        if (end < start) {
            end = start;
        }
        int bossOffset = Math.max(0, resolveFallbackBossLevelBonus());
        log(Level.WARNING,
            "[ELPortal] Resolve dynamic range source=config-fallback world=%s gateId=%s template=%s range=%d-%d bossOffset=%d rank=E",
            worldName,
            gateIdentity == null || gateIdentity.isBlank() ? "<none>" : gateIdentity,
            templateName == null || templateName.isBlank() ? "<none>" : templateName,
            start,
            end,
            bossOffset);
        return new PendingLevelProfile(start, end, bossOffset, "E");
    }

    private static int resolveFallbackNormalMobLevelRange() {
        if (filesManager == null) {
            return DYNAMIC_RANGE_SIZE;
        }
        return Math.max(0, filesManager.getDungeonNormalMobLevelRange());
    }

    private static int resolveFallbackBossLevelBonus() {
        if (filesManager == null) {
            return 5;
        }
        return Math.max(0, filesManager.getDungeonBossLevelBonus());
    }

    private static int resolveFallbackRankFloorEMinOffset() {
        if (filesManager == null) {
            return 10;
        }
        return Math.max(0, filesManager.getDungeonRankFloorEMinOffset());
    }

    private static int clampDynamicLevel(int value) {
        return Math.max(DYNAMIC_MIN_LEVEL, Math.min(DYNAMIC_MAX_LEVEL, value));
    }

    @Nullable
    private static PendingLevelProfile resolvePendingInstanceRange(@Nonnull String worldName,
                                                                   @Nullable String gateIdentity,
                                                                   @Nullable String templateHint) {
        if (gateIdentity != null && !gateIdentity.isBlank()) {
            PendingLevelProfile gatePending = resolvePendingRangeByGateIdentity(gateIdentity);
            if (gatePending != null) {
                log(Level.WARNING,
                        "[ELPortal] Resolve pending range source=gate-id world=%s gateId=%s range=%d-%d bossOffset=%d rank=%s",
                        worldName,
                        gateIdentity,
                        gatePending.min(),
                        gatePending.max(),
                        gatePending.bossLevelFromRangeMaxOffset(),
                        gatePending.rankLetter());
                return gatePending;
            }
        }

        String templateName = templateHint;
        if (templateName == null || templateName.isBlank()) {
            templateName = resolveTemplateNameFromWorldName(worldName);
        }
        if (templateName != null && !templateName.isBlank()) {
            templateName = canonicalizeRoutingTemplate(templateName);
        }
        if (templateName != null) {
            PendingLevelProfile templatePending = PENDING_LEVEL_RANGES.remove(templateName);
            if (templatePending != null) {
                log(Level.WARNING,
                        "[ELPortal] Resolve pending range source=template world=%s template=%s range=%d-%d bossOffset=%d rank=%s",
                        worldName,
                        templateName,
                        templatePending.min(),
                        templatePending.max(),
                        templatePending.bossLevelFromRangeMaxOffset(),
                        templatePending.rankLetter());
            }
            return templatePending;
        }

        return null;
    }

    private static void putPendingRangeByGateIdentity(@Nullable String gateIdentity,
                                                      @Nonnull PendingLevelProfile profile) {
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return;
        }

        PENDING_LEVEL_RANGES_BY_GATE.put(gateIdentity, profile);

        String canonical = canonicalizeGateIdentityNullable(gateIdentity);
        if (canonical != null && !canonical.equals(gateIdentity)) {
            PENDING_LEVEL_RANGES_BY_GATE.put(canonical, profile);
        }

        String legacy = legacyGateIdentity(gateIdentity);
        if (legacy != null && !legacy.equals(gateIdentity)) {
            PENDING_LEVEL_RANGES_BY_GATE.put(legacy, profile);
        }
    }

    @Nullable
    private static PendingLevelProfile resolvePendingRangeByGateIdentity(@Nullable String gateIdentity) {
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return null;
        }

        PendingLevelProfile profile = PENDING_LEVEL_RANGES_BY_GATE.get(gateIdentity);
        if (profile != null) {
            return profile;
        }

        String canonical = canonicalizeGateIdentityNullable(gateIdentity);
        if (canonical != null && !canonical.equals(gateIdentity)) {
            profile = PENDING_LEVEL_RANGES_BY_GATE.get(canonical);
            if (profile != null) {
                return profile;
            }
        }

        String legacy = legacyGateIdentity(gateIdentity);
        if (legacy != null && !legacy.equals(gateIdentity)) {
            profile = PENDING_LEVEL_RANGES_BY_GATE.get(legacy);
            if (profile != null) {
                return profile;
            }
        }

        return null;
    }

    private static void removePendingRangeByGateIdentity(@Nullable String gateIdentity) {
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return;
        }

        PENDING_LEVEL_RANGES_BY_GATE.remove(gateIdentity);

        String canonical = canonicalizeGateIdentityNullable(gateIdentity);
        if (canonical != null && !canonical.equals(gateIdentity)) {
            PENDING_LEVEL_RANGES_BY_GATE.remove(canonical);
        }

        String legacy = legacyGateIdentity(gateIdentity);
        if (legacy != null && !legacy.equals(gateIdentity)) {
            PENDING_LEVEL_RANGES_BY_GATE.remove(legacy);
        }
    }

    @Nullable
    private static String canonicalizeGateIdentityNullable(@Nullable String gateIdentity) {
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return null;
        }
        return gateIdentity.startsWith("el_gate:") ? gateIdentity : ("el_gate:" + gateIdentity);
    }

    @Nullable
    private static String legacyGateIdentity(@Nullable String gateIdentity) {
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return null;
        }
        return gateIdentity.startsWith("el_gate:")
                ? gateIdentity.substring("el_gate:".length())
                : gateIdentity;
    }

    @Nonnull
    private static LevelRange registerInstanceLevelOverride(@Nonnull String worldName,
                                                            @Nullable String gateIdentity,
                                                            @Nullable String templateHint) {
        PendingLevelProfile pendingProfile = resolvePendingInstanceRange(worldName, gateIdentity, templateHint);
        LevelRange existingRange = ACTIVE_LEVEL_RANGES.get(worldName);
        Integer existingBossLevel = ACTIVE_BOSS_LEVELS.get(worldName);
        String existingRank = ACTIVE_RANK_LETTERS.get(worldName);

        if (pendingProfile != null) {
            int pendingBossLevel = pendingProfile.max() + pendingProfile.bossLevelFromRangeMaxOffset();
            boolean sameAsExisting = existingRange != null
                    && existingBossLevel != null
                    && existingRange.min() == pendingProfile.min()
                    && existingRange.max() == pendingProfile.max()
                    && existingBossLevel == pendingBossLevel
                    && Objects.equals(existingRank, pendingProfile.rankLetter());

            if (!sameAsExisting) {
                log(Level.INFO,
                        "[ELPortal] Refreshed level override world=%s oldRange=%s oldBoss=%s oldRank=%s newRange=%d-%d newBoss=%d newRank=%s",
                        worldName,
                        existingRange == null ? "<none>" : (existingRange.min() + "-" + existingRange.max()),
                        existingBossLevel == null ? "<none>" : String.valueOf(existingBossLevel),
                        existingRank == null ? "<none>" : existingRank,
                        pendingProfile.min(),
                        pendingProfile.max(),
                        pendingBossLevel,
                        pendingProfile.rankLetter());
            }

            EndlessLevelingAPI api = EndlessLevelingAPI.get();
            if (api != null) {
                registerGateLevelOverride(api,
                        worldName,
                        pendingProfile.min(),
                        pendingProfile.max(),
                        pendingProfile.bossLevelFromRangeMaxOffset());
                log(Level.INFO, "[ELPortal] Registered level override world=%s range=%d-%d bossOffset=%d",
                        worldName,
                        pendingProfile.min(),
                        pendingProfile.max(),
                        pendingProfile.bossLevelFromRangeMaxOffset());
            }

            LevelRange pendingRange = new LevelRange(pendingProfile.min(), pendingProfile.max());
            ACTIVE_LEVEL_RANGES.put(worldName, pendingRange);
            ACTIVE_BOSS_LEVELS.put(worldName, pendingBossLevel);
            ACTIVE_RANK_LETTERS.put(worldName, pendingProfile.rankLetter());
                log(Level.WARNING,
                    "[ELPortal] Level override applied source=pending world=%s gateId=%s template=%s range=%d-%d boss=%d rank=%s",
                    worldName,
                    gateIdentity == null || gateIdentity.isBlank() ? "<none>" : gateIdentity,
                    templateHint == null || templateHint.isBlank() ? "<none>" : templateHint,
                    pendingProfile.min(),
                    pendingProfile.max(),
                    pendingBossLevel,
                    pendingProfile.rankLetter());
            return pendingRange;
        }

        if (existingRange != null && existingBossLevel != null) {
            EndlessLevelingAPI api = EndlessLevelingAPI.get();
            if (api != null) {
                int bossOffset = Math.max(0, existingBossLevel - existingRange.max());
            registerGateLevelOverride(api,
                worldName,
                existingRange.min(),
                existingRange.max(),
                bossOffset);
                log(Level.INFO,
                        "[ELPortal] Reused level override world=%s range=%d-%d bossOffset=%d",
                        worldName,
                        existingRange.min(),
                        existingRange.max(),
                        bossOffset);
            }
            if (existingRank != null) {
                ACTIVE_RANK_LETTERS.put(worldName, existingRank);
            }
            log(Level.WARNING,
                    "[ELPortal] Level override applied source=active world=%s gateId=%s template=%s range=%d-%d boss=%d rank=%s",
                    worldName,
                    gateIdentity == null || gateIdentity.isBlank() ? "<none>" : gateIdentity,
                    templateHint == null || templateHint.isBlank() ? "<none>" : templateHint,
                    existingRange.min(),
                    existingRange.max(),
                    existingBossLevel,
                    existingRank == null ? "<none>" : existingRank);
            return existingRange;
        }

        PendingLevelProfile profile = resolveDynamicInstanceRange(worldName, gateIdentity, templateHint);
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api != null) {
            registerGateLevelOverride(api,
                worldName,
                profile.min(),
                profile.max(),
                profile.bossLevelFromRangeMaxOffset());
            log(Level.INFO, "[ELPortal] Registered level override world=%s range=%d-%d bossOffset=%d",
                    worldName, profile.min(), profile.max(), profile.bossLevelFromRangeMaxOffset());
        }
        LevelRange range = new LevelRange(profile.min(), profile.max());
        ACTIVE_LEVEL_RANGES.put(worldName, range);
        ACTIVE_BOSS_LEVELS.put(worldName, profile.max() + profile.bossLevelFromRangeMaxOffset());
        ACTIVE_RANK_LETTERS.put(worldName, profile.rankLetter());
        log(Level.WARNING,
            "[ELPortal] Level override applied source=dynamic world=%s gateId=%s template=%s range=%d-%d boss=%d rank=%s",
            worldName,
            gateIdentity == null || gateIdentity.isBlank() ? "<none>" : gateIdentity,
            templateHint == null || templateHint.isBlank() ? "<none>" : templateHint,
            profile.min(),
            profile.max(),
            profile.max() + profile.bossLevelFromRangeMaxOffset(),
            profile.rankLetter());
        return range;
    }

    /** Returns the resolved [min, max] level range for a gate instance world, or null if unknown. */
    @Nullable
    public static int[] getActiveInstanceRange(@Nonnull String worldName) {
        LevelRange r = ACTIVE_LEVEL_RANGES.get(worldName);
        return r != null ? new int[]{r.min(), r.max()} : null;
    }

    @Nullable
    public static Integer getActiveInstanceBossLevel(@Nonnull String worldName) {
        return ACTIVE_BOSS_LEVELS.get(worldName);
    }

    @Nullable
    public static String getActiveInstanceRankLetter(@Nonnull String worldName) {
        return ACTIVE_RANK_LETTERS.get(worldName);
    }

    /** Removes the stored level range for a world (called on world removal to avoid memory leaks). */
    public static void clearActiveInstanceRange(@Nonnull String worldName) {
        ACTIVE_LEVEL_RANGES.remove(worldName);
        ACTIVE_BOSS_LEVELS.remove(worldName);
        ACTIVE_RANK_LETTERS.remove(worldName);
        removeGateLevelOverride(worldName);
    }

    public static void removeGateLevelOverride(@Nonnull String worldName) {
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null || worldName.isBlank()) {
            return;
        }

        String namespacedId = gateLevelOverrideId(worldName);
        boolean removedNamespaced = removeGateOverrideCompat(api, namespacedId);
        boolean removedLegacyGate = removeGateOverrideCompat(api, worldName);
        boolean removedLegacyFixed = api.removeMobWorldFixedLevelOverride(worldName);
        if (removedNamespaced || removedLegacyGate || removedLegacyFixed) {
            log(Level.INFO,
                    "[ELPortal] Removed gate level override world=%s namespaced=%s legacyGate=%s legacyFixed=%s",
                    worldName,
                    removedNamespaced,
                    removedLegacyGate,
                    removedLegacyFixed);
        }
    }

    private static void registerGateLevelOverride(@Nonnull EndlessLevelingAPI api,
                                                 @Nonnull String worldName,
                                                 int minLevel,
                                                 int maxLevel,
                                                 int bossOffset) {
        String namespacedId = gateLevelOverrideId(worldName);
        registerGateOverrideCompat(api, namespacedId, worldName, minLevel, maxLevel, bossOffset);

        // Cleanup legacy keys to avoid stale collisions from older builds.
        removeGateOverrideCompat(api, worldName);
        api.removeMobWorldFixedLevelOverride(worldName);
    }

    private static boolean registerGateOverrideCompat(@Nonnull EndlessLevelingAPI api,
                                                      @Nonnull String id,
                                                      @Nonnull String worldName,
                                                      int minLevel,
                                                      int maxLevel,
                                                      int bossOffset) {
        try {
            Method method = api.getClass().getMethod(
                    "registerMobWorldGateLevelOverride",
                    String.class,
                    String.class,
                    int.class,
                    int.class,
                    int.class);
            Object result = method.invoke(api, id, worldName, minLevel, maxLevel, bossOffset);
            return result instanceof Boolean b ? b : true;
        } catch (Exception ignored) {
            return api.registerMobWorldFixedLevelOverride(id, worldName, minLevel, maxLevel, bossOffset);
        }
    }

    private static boolean removeGateOverrideCompat(@Nonnull EndlessLevelingAPI api,
                                                    @Nonnull String id) {
        try {
            Method method = api.getClass().getMethod("removeMobGateLevelOverride", String.class);
            Object result = method.invoke(api, id);
            return result instanceof Boolean b ? b : true;
        } catch (Exception ignored) {
            return api.removeMobWorldFixedLevelOverride(id);
        }
    }

    @Nonnull
    private static String gateLevelOverrideId(@Nonnull String worldName) {
        return GATE_OVERRIDE_ID_PREFIX + worldName;
    }

    public static boolean isInstancePairedToActiveGate(@Nonnull String worldName) {
        return GATE_KEY_TO_INSTANCE_NAME.containsValue(worldName);
    }

    public static void unpairInstanceWorldPreservingExpectation(@Nonnull String worldName,
                                                                 @Nonnull String reason) {
        if (worldName.isBlank()) {
            return;
        }

        List<String> removedGateIds = new ArrayList<>();
        for (Map.Entry<String, String> entry : GATE_KEY_TO_INSTANCE_NAME.entrySet()) {
            String gateIdentity = entry.getKey();
            if (!worldName.equals(entry.getValue())) {
                continue;
            }
            if (GATE_KEY_TO_INSTANCE_NAME.remove(gateIdentity, worldName)) {
                GATE_SPAWN_IN_FLIGHT.remove(gateIdentity);
                removedGateIds.add(gateIdentity);
            }
        }

        if (!removedGateIds.isEmpty()) {
            log(Level.WARNING,
                    "[ELPortal] Unpaired world from active gate mapping world=%s reason=%s gates=%s",
                    worldName,
                    reason,
                    String.join(",", removedGateIds));
        }
    }

    public static void reloadPairedInstanceIfPossible(@Nonnull String worldName, @Nonnull String reason) {
        if (!isInstancePairedToActiveGate(worldName)) {
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        if (!universe.isWorldLoadable(worldName)) {
            log(Level.WARNING,
                    "[ELPortal] Paired instance unload observed but world is not loadable world=%s reason=%s",
                    worldName,
                    reason);
            return;
        }

        if (!PAIRED_INSTANCE_RELOADS_IN_FLIGHT.add(worldName)) {
            return;
        }

        universe.loadWorld(worldName)
                .thenAccept(reloaded -> {
                    try {
                        if (reloaded == null) {
                            log(Level.WARNING,
                                    "[ELPortal] Paired instance reload returned null world=%s reason=%s",
                                    worldName,
                                    reason);
                            return;
                        }

                        enforcePersistentInstanceLifecycle(reloaded, "paired-reload");
                        restoreActiveInstanceOverride(worldName);
                        log(Level.INFO,
                                "[ELPortal] Reloaded paired instance world=%s reason=%s",
                                worldName,
                                reason);
                    } finally {
                        PAIRED_INSTANCE_RELOADS_IN_FLIGHT.remove(worldName);
                    }
                })
                .exceptionally(ex -> {
                    PAIRED_INSTANCE_RELOADS_IN_FLIGHT.remove(worldName);
                    AddonLoggingManager.log(plugin,
                            Level.WARNING,
                            ex,
                            "[ELPortal] Failed to reload paired instance world=%s reason=%s",
                            worldName,
                            reason);
                    return null;
                });
    }

    private static void restoreActiveInstanceOverride(@Nonnull String worldName) {
        LevelRange range = ACTIVE_LEVEL_RANGES.get(worldName);
        Integer bossLevel = ACTIVE_BOSS_LEVELS.get(worldName);
        if (range == null || bossLevel == null) {
            return;
        }

        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api == null) {
            return;
        }

        int bossOffset = Math.max(0, bossLevel - range.max());
        registerGateLevelOverride(api, worldName, range.min(), range.max(), bossOffset);
    }

    private static void enforcePersistentInstanceLifecycle(@Nonnull World world,
                                                           @Nonnull String source) {
        try {
            InstanceWorldConfig instanceConfig = InstanceWorldConfig.get(world.getWorldConfig());
            if (instanceConfig == null) {
                return;
            }

            instanceConfig.setRemovalConditions();
            world.getWorldConfig().markChanged();
            log(Level.INFO,
                    "[ELPortal] Enforced persistent instance lifecycle world=%s source=%s",
                    world.getName(),
                    source);
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin,
                    Level.WARNING,
                    ex,
                    "[ELPortal] Failed to enforce persistent lifecycle world=%s source=%s",
                    world.getName(),
                    source);
        }
    }

    /**
     * Returns the human-readable display name for a gate instance world (e.g. "Major Dungeon I"),
     * or null if the world is not a gate instance.
     */
    @Nullable
    public static String resolveGateDisplayName(@Nonnull String worldName) {
        String normalizedWorldName = worldName.trim().toLowerCase(Locale.ROOT);
        boolean isCanonicalGateWorld = normalizedWorldName.startsWith("el_gate_");
        boolean isActiveGatePair = isInstancePairedToActiveGate(worldName);
        if (!isCanonicalGateWorld && !isActiveGatePair) {
            return null;
        }

        String templateName = resolveTemplateNameFromWorldName(worldName);
        if (templateName != null) {
            return ROUTING_TO_DISPLAY.get(templateName);
        }

        // el_gate_* world IDs do not include template name; recover display from
        // gate pairing/expectation metadata.
        for (Map.Entry<String, String> entry : GATE_KEY_TO_INSTANCE_NAME.entrySet()) {
            if (!worldName.equals(entry.getValue())) {
                continue;
            }

            GateInstanceExpectation expectation = GATE_INSTANCE_EXPECTATIONS.get(entry.getKey());
            if (expectation == null || expectation.routingName() == null || expectation.routingName().isBlank()) {
                continue;
            }

            String displayName = ROUTING_TO_DISPLAY.get(expectation.routingName());
            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        }

        return null;
    }

    /**
     * Resolves the routing name for a placed block ID, stripping any rank suffix
     * (e.g. "_RankS", "_RankA", …) before the lookup so ranked portal variants
     * route to the same instance template as the base block.
     */
    @Nullable
    private static String resolveRoutingName(@Nonnull String blockId) {
        String direct = BLOCK_ID_TO_ROUTING_NAME.get(blockId);
        if (direct != null) {
            return direct;
        }
        // Strip _Rank{S,A,B,C,D,E} suffix and retry
        String stripped = blockId.replaceAll("_Rank[SABCDE]$", "");
        return BLOCK_ID_TO_ROUTING_NAME.get(stripped);
    }

    @Nonnull
    private static String canonicalizeRoutingTemplate(@Nonnull String templateName) {
        String suffix = INSTANCE_TEMPLATE_TO_SUFFIX.get(templateName);
        if (suffix == null) {
            return templateName;
        }

        for (Map.Entry<String, String> entry : ROUTING_TO_SUFFIX.entrySet()) {
            if (entry.getValue().equals(suffix)) {
                return entry.getKey();
            }
        }
        return templateName;
    }

    private static void backfillGatePairingFromDirectEntry(@Nonnull PlayerRef playerRef,
                                                           @Nonnull World instanceWorld,
                                                           @Nonnull String instanceWorldName,
                                                           @Nullable String templateName,
                                                           @Nullable Transform addEventTransform) {
        String canonicalTemplate = templateName == null ? null : canonicalizeRoutingTemplate(templateName);
        if (canonicalTemplate == null) {
            return;
        }

        String blockId = ROUTING_NAME_TO_BLOCK_ID.get(canonicalTemplate);
        if (blockId == null) {
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        World returnWorld = resolveReturnWorld(instanceWorld, universe);
        Transform returnTransform = resolveReturnTransform(instanceWorld, playerRef);
        Transform anchorTransform = returnTransform != null ? returnTransform : addEventTransform;
        if (anchorTransform == null || anchorTransform.getPosition() == null) {
            return;
        }

        int x = MathUtil.floor(anchorTransform.getPosition().x);
        int y = MathUtil.floor(anchorTransform.getPosition().y);
        int z = MathUtil.floor(anchorTransform.getPosition().z);
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid != null) {
            PortalProximityManager.markPlayerEnterInstance(playerUuid);
        }
        var anchor = NaturalPortalGateManager.resolveTrackedGateAnchor(
                returnWorld,
                blockId,
                x,
                y,
                z);

        String gateKey = anchor.gateId();
        if (gateKey == null || gateKey.isBlank()) {
            String worldNameGateId = deriveStableGateIdFromWorldName(returnWorld);
            if (worldNameGateId != null && !worldNameGateId.isBlank()) {
                gateKey = worldNameGateId;
                log(Level.WARNING,
                        "[ELPortal] Direct-entry backfill recovered canonical gate key=%s player=%s returnWorld=%s block=%s",
                        gateKey,
                        playerRef.getUsername(),
                        returnWorld.getName(),
                        blockId);
            }
        }
        if (gateKey == null || gateKey.isBlank()) {
            log(Level.WARNING,
                    "[ELPortal] Skipping direct-entry backfill: no tracked gate identity for player=%s instance=%s returnWorld=%s block=%s anchor=%d %d %d",
                    playerRef.getUsername(),
                    instanceWorldName,
                    returnWorld.getName(),
                    blockId,
                    anchor.x(),
                    anchor.y(),
                    anchor.z());
            return;
        }

        if (!gateKey.startsWith("el_gate:")) {
            log(Level.WARNING,
                    "[ELPortal] Direct-entry backfill using legacy gate key=%s player=%s returnWorld=%s block=%s anchor=%d %d %d",
                    gateKey,
                    playerRef.getUsername(),
                    returnWorld.getName(),
                    blockId,
                    anchor.x(),
                    anchor.y(),
                    anchor.z());
        }

        gateKey = canonicalizeGateIdentity(gateKey);
        String existingGateIdentity = findGateIdentityForInstanceWorld(instanceWorldName);
        if (existingGateIdentity != null && !existingGateIdentity.isBlank()) {
            gateKey = existingGateIdentity;
        }
        String existing = GATE_KEY_TO_INSTANCE_NAME.get(gateKey);
        if (Objects.equals(existing, instanceWorldName)) {
            return;
        }

        if (existing != null && !existing.isBlank() && isKnownLiveOrLoadableWorld(existing, universe)) {
            return;
        }

        GATE_KEY_TO_INSTANCE_NAME.put(gateKey, instanceWorldName);
        cacheResolvedInstanceWorld(gateKey,
                instanceWorldName,
                blockId,
                canonicalTemplate,
                "direct-entry-backfill");
        persistGateInstanceImmediate(gateKey, instanceWorldName, blockId);
        log(Level.INFO,
                "[ELPortal] Backfilled gate pairing key=%s block=%s -> %s (template=%s player=%s)",
                gateKey,
                blockId,
                instanceWorldName,
                canonicalTemplate,
                playerRef.getUsername());
    }

    private static boolean isRoutingTemplateWorldName(@Nonnull String worldName) {
        for (String template : ROUTING_TO_SUFFIX.keySet()) {
            if (template.equalsIgnoreCase(worldName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isKnownLiveOrLoadableWorld(@Nonnull String worldName, @Nonnull Universe universe) {
        if (universe.getWorld(worldName) != null) {
            return true;
        }
        return universe.isWorldLoadable(worldName);
    }

    @Nonnull
    private static String buildExpectedGroupId(@Nullable String gateIdentity,
                                               @Nullable String routingName) {
        if (routingName == null || routingName.isBlank()) {
            return "el_gate";
        }
        String originalTemplateName = resolveOriginalDungeonTemplateName(routingName);
        String templateToken = sanitizeInstanceToken(originalTemplateName, "dungeon");
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return "el_gate_" + templateToken;
        }
        String gateToken = sanitizeInstanceToken(gateIdentity, "gate");
        return "el_gate_" + templateToken + "_" + gateToken;
    }

    @Nonnull
    private static String buildGateInstanceWorldName(@Nonnull String routingName,
                                                     @Nullable String gateIdentity) {
        String originalTemplateName = resolveOriginalDungeonTemplateName(routingName);
        String templateToken = originalTemplateName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return "el_gate_" + templateToken + "_" + UUID.randomUUID();
        }
        String gateToken = sanitizeInstanceToken(gateIdentity, "gate");
        if (gateToken.startsWith("el_gate_")) {
            gateToken = gateToken.substring("el_gate_".length());
        }
        gateToken = gateToken.replaceAll("_+", "_").replaceAll("^_+", "").replaceAll("_+$", "");
        if (gateToken.isBlank()) {
            gateToken = UUID.randomUUID().toString();
        }
        // Deterministic world IDs keep gate pairing/saving stable across retries and restarts.
        return "el_gate_" + templateToken + "_" + gateToken;
    }

    @Nonnull
    private static String resolvePreferredGateInstanceWorldName(@Nonnull String routingName,
                                                                @Nonnull String gateIdentity) {
        String mappedWorld = GATE_KEY_TO_INSTANCE_NAME.get(gateIdentity);
        if (mappedWorld != null && !mappedWorld.isBlank()) {
            return mappedWorld;
        }

        GateInstanceExpectation expectation = GATE_INSTANCE_EXPECTATIONS.get(gateIdentity);
        if (expectation != null
                && expectation.expectedWorldId() != null
                && !expectation.expectedWorldId().isBlank()) {
            return expectation.expectedWorldId();
        }

        return buildGateInstanceWorldName(routingName, gateIdentity);
    }
    
    @Nonnull
    private static String resolveOriginalDungeonTemplateName(@Nonnull String routingName) {
        String canonicalRoutingName = canonicalizeRoutingTemplate(routingName);
        String suffix = ROUTING_TO_SUFFIX.get(canonicalRoutingName);
        if (suffix == null) {
            suffix = INSTANCE_TEMPLATE_TO_SUFFIX.get(routingName);
        }
        if (suffix == null) {
            return routingName;
        }
        return SUFFIX_TO_ORIGINAL_TEMPLATE.getOrDefault(suffix, routingName);
    }

    @Nullable
    private static String deriveStableGateIdFromWorldName(@Nonnull World world) {
        String worldName = world.getName();
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        // New world IDs are template-based (el_gate_<template>_<gateToken>); resolve from pairing first.
        String pairedGateIdentity = findGateIdentityForInstanceWorld(worldName);
        if (pairedGateIdentity != null) {
            return pairedGateIdentity;
        }

        String prefix = "el_gate_";
        if (!worldName.startsWith(prefix)) {
            return null;
        }

        int zSep = worldName.lastIndexOf('_');
        if (zSep <= prefix.length()) {
            return null;
        }
        int ySep = worldName.lastIndexOf('_', zSep - 1);
        if (ySep <= prefix.length()) {
            return null;
        }
        int xSep = worldName.lastIndexOf('_', ySep - 1);
        if (xSep <= prefix.length()) {
            return null;
        }

        String uuidPart = worldName.substring(prefix.length(), xSep);
        String xPart = worldName.substring(xSep + 1, ySep);
        String yPart = worldName.substring(ySep + 1, zSep);
        String zPart = worldName.substring(zSep + 1);

        try {
            UUID uuid = UUID.fromString(uuidPart);
            int x = Integer.parseInt(xPart);
            int y = Integer.parseInt(yPart);
            int z = Integer.parseInt(zPart);
            return "el_gate:" + uuid + ":" + x + ":" + y + ":" + z;
        } catch (Exception ignored) {
            // Not a legacy gate-id-shaped world name; treat as non-derivable.
            return null;
        }
    }

    @Nullable
    private static String findGateIdentityForInstanceWorld(@Nonnull String instanceWorldName) {
        String canonical = null;
        for (Map.Entry<String, String> entry : GATE_KEY_TO_INSTANCE_NAME.entrySet()) {
            if (!instanceWorldName.equals(entry.getValue())) {
                continue;
            }

            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                continue;
            }

            if (key.startsWith("el_gate:")) {
                return key;
            }

            if (canonical == null) {
                canonical = "el_gate:" + key;
            }
        }
        return canonical;
    }

    @Nonnull
    private static String sanitizeInstanceToken(@Nullable String rawValue,
                                                @Nonnull String fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }

        String lowered = rawValue.toLowerCase(Locale.ROOT);
        String sanitized = lowered.replaceAll("[^a-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return sanitized.isBlank() ? fallback : sanitized;
    }

    @Nonnull
    private static String canonicalizeGateIdentity(@Nonnull String gateIdentity) {
        if (gateIdentity.startsWith("el_gate:")) {
            return gateIdentity;
        }
        return "el_gate:" + gateIdentity;
    }

    @Nullable
    private static String resolveNearbyCanonicalGateIdentity(@Nonnull World sourceWorld,
                                                             @Nonnull String blockId,
                                                             int x,
                                                             int y,
                                                             int z,
                                                             @Nonnull String fallbackGateIdentity) {
        UUID worldUuid = sourceWorld.getWorldConfig() == null ? null : sourceWorld.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return null;
        }

        String blockBase = stripRankSuffix(blockId);
        String bestGateIdentity = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Map.Entry<String, String> entry : GATE_KEY_TO_INSTANCE_NAME.entrySet()) {
            String rawGateId = entry.getKey();
            if (rawGateId == null || rawGateId.isBlank()) {
                continue;
            }

            String gateId = canonicalizeGateIdentity(rawGateId);
            String[] parts = gateId.split(":");
            if (parts.length != 5 || !"el_gate".equals(parts[0])) {
                continue;
            }

            UUID gateWorldUuid;
            int gx;
            int gy;
            int gz;
            try {
                gateWorldUuid = UUID.fromString(parts[1]);
                gx = Integer.parseInt(parts[2]);
                gy = Integer.parseInt(parts[3]);
                gz = Integer.parseInt(parts[4]);
            } catch (Exception ignored) {
                continue;
            }

            if (!worldUuid.equals(gateWorldUuid)) {
                continue;
            }

            GateInstanceExpectation expectation = GATE_INSTANCE_EXPECTATIONS.get(gateId);
            if (expectation != null) {
                String expectedBlockBase = stripRankSuffix(expectation.blockId());
                if (!expectedBlockBase.equals(blockBase)) {
                    continue;
                }
            }

            int dx = gx - x;
            int dy = gy - y;
            int dz = gz - z;
            int distance = dx * dx + dy * dy + dz * dz;
            if (distance > 4) {
                continue;
            }

            if (distance < bestDistance) {
                bestDistance = distance;
                bestGateIdentity = gateId;
            }
        }

        if (bestGateIdentity != null) {
            return bestGateIdentity;
        }
        return fallbackGateIdentity;
    }

    @Nullable
    private static String recoverCanonicalGateIdentity(@Nonnull World sourceWorld,
                                                       @Nonnull String blockId,
                                                       int x,
                                                       int y,
                                                       int z) {
        UUID worldUuid = sourceWorld.getWorldConfig() == null ? null : sourceWorld.getWorldConfig().getUuid();
        if (worldUuid == null) {
            return null;
        }

        String blockBase = stripRankSuffix(blockId);
        String worldPrefix = "el_gate:" + worldUuid + ":";
        String bestGateId = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Map.Entry<String, GateInstanceExpectation> entry : GATE_INSTANCE_EXPECTATIONS.entrySet()) {
            String gateId = entry.getKey();
            if (gateId == null || !gateId.startsWith(worldPrefix)) {
                continue;
            }

            GateInstanceExpectation expectation = entry.getValue();
            if (expectation == null) {
                continue;
            }

            String expectedBlockBase = stripRankSuffix(expectation.blockId());
            if (!expectedBlockBase.equals(blockBase)) {
                continue;
            }

            String[] parts = gateId.split(":");
            if (parts.length != 5) {
                continue;
            }

            try {
                int gx = Integer.parseInt(parts[2]);
                int gy = Integer.parseInt(parts[3]);
                int gz = Integer.parseInt(parts[4]);
                int dx = gx - x;
                int dy = gy - y;
                int dz = gz - z;
                int distance = dx * dx + dy * dy + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestGateId = gateId;
                }
            } catch (NumberFormatException ignored) {
                // Skip malformed keys and continue scanning valid canonical IDs.
            }
        }

        if (bestGateId != null) {
            log(Level.WARNING,
                    "[ELPortal] Recovered canonical gate identity from expectations gateId=%s world=%s block=%s at %d %d %d distanceSq=%d",
                    bestGateId,
                    sourceWorld.getName(),
                    blockId,
                    x,
                    y,
                    z,
                    bestDistance);
        }
        return bestGateId;
    }

    private static void rememberPendingGateEntry(@Nonnull PlayerRef playerRef,
                                                 @Nonnull String gateIdentity,
                                                 @Nonnull String blockId,
                                                 @Nonnull String routingName) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        PLAYER_PENDING_GATE_ENTRIES.put(playerUuid,
                new PendingGateEntry(gateIdentity, blockId, routingName, System.currentTimeMillis()));
    }

    @Nullable
    private static PendingGateEntry resolvePendingGateEntry(@Nonnull PlayerRef playerRef,
                                                            @Nullable String templateName) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return null;
        }

        PendingGateEntry pending = PLAYER_PENDING_GATE_ENTRIES.get(playerUuid);
        if (pending == null) {
            return null;
        }

        long ageMs = System.currentTimeMillis() - pending.createdAtMillis();
        if (ageMs > PENDING_GATE_ENTRY_TTL_MILLIS) {
            PLAYER_PENDING_GATE_ENTRIES.remove(playerUuid, pending);
            return null;
        }

        if (templateName == null || templateName.isBlank()) {
            return pending;
        }

        String expectedTemplate = canonicalizeRoutingTemplate(templateName);
        String pendingTemplate = canonicalizeRoutingTemplate(pending.routingName());
        if (!expectedTemplate.equalsIgnoreCase(pendingTemplate)) {
            return null;
        }

        return pending;
    }

    private static void clearPendingGateEntry(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid != null) {
            PLAYER_PENDING_GATE_ENTRIES.remove(playerUuid);
        }
    }

    @Nullable
    private static PendingGateEntry resolveFallbackGateEntryForDirectWorld(@Nullable String templateName) {
        if (templateName == null || templateName.isBlank()) {
            return null;
        }

        String expectedTemplate = canonicalizeRoutingTemplate(templateName);
        long now = System.currentTimeMillis();
        String bestGateId = null;
        GateInstanceExpectation bestExpectation = null;
        long newest = Long.MIN_VALUE;

        for (Map.Entry<String, GateInstanceExpectation> entry : GATE_INSTANCE_EXPECTATIONS.entrySet()) {
            String gateId = entry.getKey();
            GateInstanceExpectation expectation = entry.getValue();
            if (gateId == null || expectation == null || !gateId.startsWith("el_gate:")) {
                continue;
            }

            String expectationTemplate = canonicalizeRoutingTemplate(expectation.routingName());
            if (!expectedTemplate.equalsIgnoreCase(expectationTemplate)) {
                continue;
            }

            long ageMs = now - expectation.createdAtMillis();
            if (ageMs > DIRECT_ENTRY_FALLBACK_GATE_TTL_MILLIS) {
                continue;
            }

            if (expectation.createdAtMillis() > newest) {
                newest = expectation.createdAtMillis();
                bestGateId = gateId;
                bestExpectation = expectation;
            }
        }

        if (bestGateId == null || bestExpectation == null) {
            return null;
        }

        return new PendingGateEntry(
                bestGateId,
                bestExpectation.blockId(),
                bestExpectation.routingName(),
                bestExpectation.createdAtMillis());
    }

    private static void logGateEntryExpectation(@Nonnull String gateIdentity,
                                                @Nonnull String blockId,
                                                @Nonnull String routingName,
                                                @Nonnull String playerName) {
        GateInstanceExpectation expectation = GATE_INSTANCE_EXPECTATIONS.get(gateIdentity);
        if (expectation == null) {
            log(Level.WARNING,
                    "[ELPortal] Gate expectation miss on entry gateId=%s player=%s block=%s template=%s",
                    gateIdentity,
                    playerName,
                    blockId,
                    routingName);
            return;
        }

        boolean blockMatches = stripRankSuffix(expectation.blockId()).equals(stripRankSuffix(blockId));
        boolean templateMatches = expectation.routingName().equals(routingName);
        log(Level.INFO,
                "[ELPortal] Gate entry expectation check gateId=%s player=%s blockMatch=%s templateMatch=%s expectedTemplate=%s actualTemplate=%s expectedWorldId=%s",
                gateIdentity,
                playerName,
                blockMatches,
                templateMatches,
                expectation.routingName(),
                routingName,
                expectation.expectedWorldId() == null ? "<pending>" : expectation.expectedWorldId());
    }

    private static void cacheResolvedInstanceWorld(@Nonnull String gateIdentity,
                                                   @Nonnull String resolvedWorldId,
                                                   @Nullable String blockId,
                                                   @Nullable String routingName,
                                                   @Nonnull String source) {
        GateInstanceExpectation existing = GATE_INSTANCE_EXPECTATIONS.get(gateIdentity);
        if (existing == null) {
            String fallbackBlock = blockId == null ? "unknown" : blockId;
            String fallbackTemplate = routingName == null ? "unknown" : routingName;
            GATE_INSTANCE_EXPECTATIONS.put(gateIdentity,
                    new GateInstanceExpectation(
                            fallbackBlock,
                            fallbackTemplate,
                        buildExpectedGroupId(gateIdentity, fallbackTemplate),
                            resolvedWorldId,
                            System.currentTimeMillis()));
            log(Level.INFO,
                    "[ELPortal] Gate expectation backfilled gateId=%s source=%s worldId=%s",
                    gateIdentity,
                    source,
                    resolvedWorldId);
            return;
        }

        if (existing.expectedWorldId() == null) {
            GATE_INSTANCE_EXPECTATIONS.put(gateIdentity,
                    new GateInstanceExpectation(
                            existing.blockId(),
                            existing.routingName(),
                            existing.expectedGroupId(),
                            resolvedWorldId,
                            existing.createdAtMillis()));
                log(Level.INFO,
                    "[ELPortal] Gate expected world ID initialized gateId=%s source=%s worldId=%s",
                    gateIdentity,
                    source,
                    resolvedWorldId);
            return;
        }

        if (existing.expectedWorldId().equals(resolvedWorldId)) {
            log(Level.INFO,
                    "[ELPortal] Gate expected world ID match gateId=%s source=%s worldId=%s",
                    gateIdentity,
                    source,
                    resolvedWorldId);
            return;
        }

        Universe universe = Universe.get();
        boolean oldLoadable = universe != null && universe.isWorldLoadable(existing.expectedWorldId());
        if (!oldLoadable) {
            GATE_INSTANCE_EXPECTATIONS.put(gateIdentity,
                new GateInstanceExpectation(
                    existing.blockId(),
                    existing.routingName(),
                    existing.expectedGroupId(),
                    resolvedWorldId,
                    existing.createdAtMillis()));
            log(Level.SEVERE,
                "[ELPortal] Gate expected world ID rotated (old world missing) gateId=%s source=%s old=%s new=%s",
                gateIdentity,
                source,
                existing.expectedWorldId(),
                resolvedWorldId);
            return;
        }

        log(Level.SEVERE,
                "[ELPortal] Gate expected world ID mismatch gateId=%s source=%s expected=%s actual=%s",
                gateIdentity,
                source,
                existing.expectedWorldId(),
                resolvedWorldId);
    }

    @Nonnull
    private static String stripRankSuffix(@Nonnull String blockId) {
        int rankIndex = blockId.indexOf("_Rank");
        if (rankIndex <= 0) {
            return blockId;
        }
        return blockId.substring(0, rankIndex);
    }

    @Nonnull
    private static String resolveRankLetterFromBlockId(@Nonnull String blockId) {
        if (blockId.endsWith("_RankS")) {
            return "S";
        }
        if (blockId.endsWith("_RankA")) {
            return "A";
        }
        if (blockId.endsWith("_RankB")) {
            return "B";
        }
        if (blockId.endsWith("_RankC")) {
            return "C";
        }
        if (blockId.endsWith("_RankD")) {
            return "D";
        }
        if (blockId.endsWith("_RankE")) {
            return "E";
        }
        return "E";
    }

    @Nullable
    private static String resolveSuffixFromWorldName(@Nonnull String worldName) {
        String normalizedWorldName = worldName.toLowerCase(Locale.ROOT);
        String bestTemplate = null;
        String bestSuffix = null;
        for (Map.Entry<String, String> entry : INSTANCE_TEMPLATE_TO_SUFFIX.entrySet()) {
            String template = entry.getKey();
            if (!normalizedWorldName.contains(template.toLowerCase(Locale.ROOT))) {
                continue;
            }

            if (bestTemplate == null || template.length() > bestTemplate.length()) {
                bestTemplate = template;
                bestSuffix = entry.getValue();
            }
        }
        return bestSuffix;
    }

    @Nullable
    private static String resolveTemplateNameFromWorldName(@Nonnull String worldName) {
        String normalizedWorldName = worldName.toLowerCase(Locale.ROOT);

        // Check routing/template names first (the worldName may BE the template name for routed path)
        for (String routingTemplate : ROUTING_TO_SUFFIX.keySet()) {
            if (routingTemplate.equalsIgnoreCase(worldName)) {
                return routingTemplate;
            }
        }

        // Check instance world names (e.g. "instance-EL_MJ_Instance_D02-<uuid>")
        String bestTemplate = null;
        for (String templateName : INSTANCE_TEMPLATE_TO_SUFFIX.keySet()) {
            if (!normalizedWorldName.contains(templateName.toLowerCase(Locale.ROOT))) {
                continue;
            }

            if (bestTemplate == null || templateName.length() > bestTemplate.length()) {
                bestTemplate = templateName;
            }
        }

        return bestTemplate == null ? null : canonicalizeRoutingTemplate(bestTemplate);
    }

    private static void applyFixedGateSpawn(@Nonnull PlayerRef playerRef,
                                            @Nonnull World targetWorld,
                                            @Nonnull String suffix) {
        markReturnPortalSuppression(playerRef);

        Transform spawnTransform = resolveWorldSpawnTransform(targetWorld, playerRef.getUuid());
        if (spawnTransform == null) {
            log(Level.INFO,
                    "[ELPortal] Fixed-spawn unresolved player=%s world=%s suffix=%s",
                    playerRef.getUsername(),
                    targetWorld.getName(),
                    suffix);
            return;
        }

        log(Level.INFO,
                "[ELPortal] Fixed-spawn player=%s world=%s suffix=%s spawn=(%.2f, %.2f, %.2f)",
                playerRef.getUsername(),
                targetWorld.getName(),
                suffix,
                spawnTransform.getPosition().x,
                spawnTransform.getPosition().y,
                spawnTransform.getPosition().z);
        if (shouldSkipFixedSpawnTeleport(playerRef, targetWorld, spawnTransform)) {
            log(Level.INFO,
                    "[ELPortal] Fixed-spawn teleport suppressed player=%s world=%s suffix=%s",
                    playerRef.getUsername(),
                    targetWorld.getName(),
                    suffix);
        } else {
            teleportToWorld(playerRef, targetWorld, spawnTransform);
        }
        if (suffix.startsWith("EG_") || suffix.startsWith("MJ_")) {
            UUID playerUuid = playerRef.getUuid();
            if (playerUuid != null) {
                PortalProximityManager.markPlayerEnterInstance(playerUuid);
            }
        }

        if (suffix.startsWith("EG_")) {
            // Original Endgame templates already spawn their own return portal; avoid duplicates.
            String templateName = resolveTemplateNameFromWorldName(targetWorld.getName());
            if (!isOriginalTemplateName(templateName)) {
                queueReturnPortalPlacement(targetWorld, spawnTransform);
            }
        }
    }

    private static void markReturnPortalSuppression(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        PLAYER_RETURN_PORTAL_SUPPRESSION_UNTIL.put(
                playerUuid,
                System.currentTimeMillis() + RETURN_PORTAL_ENTRY_SUPPRESSION_MILLIS);
    }

    private static boolean isOriginalTemplateName(@Nullable String templateName) {
        return templateName != null
                && (templateName.startsWith("Endgame_") || templateName.startsWith("MJ_Instance_"));
    }

    @Nullable
    private static Transform resolveWorldSpawnTransform(@Nonnull World world, @Nonnull UUID playerUuid) {
        if (world.getWorldConfig() == null || world.getWorldConfig().getSpawnProvider() == null) {
            return null;
        }
        return world.getWorldConfig().getSpawnProvider().getSpawnPoint(world, playerUuid);
    }

    private static void queueReturnPortalPlacement(@Nonnull World world, @Nonnull Transform spawnTransform) {
        if (spawnTransform.getPosition() == null) {
            return;
        }

        int x = (int) Math.floor(spawnTransform.getPosition().x);
        int y = (int) Math.floor(spawnTransform.getPosition().y);
        int z = (int) Math.floor(spawnTransform.getPosition().z);
        String placementKey = world.getName() + ":" + x + ":" + y + ":" + z;
        if (!RETURN_PORTAL_PLACEMENT_IN_FLIGHT.add(placementKey)) {
            return;
        }

        Runnable tryPlace = () -> placeReturnPortalAtSpawnIfAbsent(world, spawnTransform, placementKey, 1);
        try {
            world.execute(tryPlace);
        } catch (Exception ex) {
            RETURN_PORTAL_PLACEMENT_IN_FLIGHT.remove(placementKey);
            AddonLoggingManager.log(plugin,
                    Level.WARNING,
                    ex,
                    "[ELPortal] Failed to queue return portal placement world=%s key=%s",
                    world.getName(),
                    placementKey);
        }
    }

    private static void placeReturnPortalAtSpawnIfAbsent(@Nonnull World world,
                                                          @Nonnull Transform spawnTransform,
                                                          @Nonnull String placementKey,
                                                          int attempt) {
        if (spawnTransform.getPosition() == null) {
            RETURN_PORTAL_PLACEMENT_IN_FLIGHT.remove(placementKey);
            return;
        }

        int x = (int) Math.floor(spawnTransform.getPosition().x);
        int y = (int) Math.floor(spawnTransform.getPosition().y);
        int z = (int) Math.floor(spawnTransform.getPosition().z);

        int returnBlockIntId = BlockType.getAssetMap().getIndex("Portal_Return");
        if (returnBlockIntId == Integer.MIN_VALUE) {
            returnBlockIntId = BlockType.getAssetMap().getIndex("Return_Portal");
        }
        if (returnBlockIntId == Integer.MIN_VALUE) {
            log(Level.WARNING, "[ELPortal] Portal_Return not in asset map — cannot place return portal at (%d %d %d) world=%s", x, y, z, world.getName());
            RETURN_PORTAL_PLACEMENT_IN_FLIGHT.remove(placementKey);
            return;
        }

        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            if (attempt >= RETURN_PORTAL_MAX_RETRIES) {
                RETURN_PORTAL_PLACEMENT_IN_FLIGHT.remove(placementKey);
                log(Level.WARNING,
                        "[ELPortal] Chunk not in memory at (%d, %d) after %d attempts - cannot place return portal world=%s",
                        x,
                        z,
                        attempt,
                        world.getName());
                return;
            }

            int nextAttempt = attempt + 1;
            HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                try {
                    world.execute(() -> placeReturnPortalAtSpawnIfAbsent(world, spawnTransform, placementKey, nextAttempt));
                } catch (Exception ex) {
                    RETURN_PORTAL_PLACEMENT_IN_FLIGHT.remove(placementKey);
                    AddonLoggingManager.log(plugin,
                            Level.WARNING,
                            ex,
                            "[ELPortal] Failed return portal retry world=%s key=%s attempt=%d",
                            world.getName(),
                            placementKey,
                            nextAttempt);
                }
            }, RETURN_PORTAL_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            return;
        }

        if (chunk.getBlock(x, y, z) == returnBlockIntId) {
            RETURN_PORTAL_PLACEMENT_IN_FLIGHT.remove(placementKey);
            return;
        }

        chunk.setBlock(x, y, z, returnBlockIntId);
        RETURN_PORTAL_PLACEMENT_IN_FLIGHT.remove(placementKey);
        log(Level.INFO, "[ELPortal] Placed Portal_Return at (%d, %d, %d) world=%s", x, y, z, world.getName());
    }

    private static boolean teleportToWorld(@Nonnull PlayerRef playerRef,
                                           @Nonnull World targetWorld,
                                           @Nonnull Transform targetTransform) {
        PlayerRef livePlayerRef = resolveLivePlayerRef(playerRef);
        Ref<EntityStore> entityRef = livePlayerRef == null ? null : livePlayerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            if (!canQueueTeleport(livePlayerRef, entityRef, store, "teleportToWorld")) {
                return false;
            }
            store.addComponent(entityRef, Teleport.getComponentType(), Teleport.createForPlayer(targetWorld, targetTransform));
            noteTeleportQueued(livePlayerRef);
            return true;
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin,
                    Level.WARNING,
                    ex,
                    "[ELPortal] Failed to snap %s to fixed spawn in %s",
                    playerRef.getUsername(),
                    targetWorld.getName());
            return false;
        }
    }







    private static void queueTeleportToInstanceSpawn(@Nonnull PlayerRef playerRef,
                                                     @Nonnull World targetWorld,
                                                     @Nullable Transform overrideReturn,
                                                     @Nonnull Runnable onSuccess) {
        Runnable task = () -> {
            if (teleportToInstanceSpawn(playerRef, targetWorld, overrideReturn)) {
                onSuccess.run();
            }
        };

        if (executeOnPlayerWorldThread(playerRef, task)) {
            return;
        }
        task.run();
    }

    private static boolean executeOnPlayerWorldThread(@Nonnull PlayerRef playerRef, @Nonnull Runnable task) {
        Universe universe = Universe.get();
        if (universe == null) {
            return false;
        }

        UUID playerWorldUuid = playerRef.getWorldUuid();
        if (playerWorldUuid == null) {
            return false;
        }

        World playerWorld = universe.getWorld(playerWorldUuid);
        if (playerWorld == null) {
            return false;
        }

        try {
            playerWorld.execute(task);
            return true;
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin,
                    Level.FINE,
                    ex,
                    "[ELPortal] Failed to queue task on player world thread player=%s world=%s",
                    playerRef.getUsername(),
                    playerWorld.getName());
            return false;
        }
    }

    private static boolean teleportToInstanceSpawn(@Nonnull PlayerRef playerRef,
                                                   @Nonnull World targetWorld,
                                                   @Nullable Transform overrideReturn) {
        if (isDebugPreventEnterEnabled()) {
            playerRef.sendMessage(Message.raw("[Gate Debug] Entry blocked (prevententer=true)").color("#ff6666"));
            log(Level.WARNING,
                    "[ELPortal] Debug prevented teleport to instance player=%s targetWorld=%s",
                    playerRef.getUsername(),
                    targetWorld.getName());
            return false;
        }

        DeathReentryDecision deathReentryDecision = evaluateDeathReentry(playerRef, targetWorld);
        if (deathReentryDecision.blocked()) {
            playerRef.sendMessage(Message.raw(ENDLESS_LEVELING_PREFIX + "You cannot re-enter this gate after dying.").color("#ff6666"));
            log(Level.WARNING,
                "[ELPortal] Death re-entry denied player=%s targetWorld=%s",
                playerRef.getUsername(),
                targetWorld.getName());
            return false;
        }

        if (deathReentryDecision.allowedByConfig()) {
            playerRef.sendMessage(Message.raw(
                    ENDLESS_LEVELING_PREFIX + "Re-entry after death is enabled (allow_reentry_after_death: true).")
                    .color("#6cff78"));
        }

        PlayerRef livePlayerRef = resolveLivePlayerRef(playerRef);
        Ref<EntityStore> entityRef = livePlayerRef == null ? null : livePlayerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            log(Level.WARNING, "[ELPortal] Missing/invalid player reference for %s", playerRef.getUsername());
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            if (!canQueueTeleport(livePlayerRef, entityRef, store, "teleportToInstanceSpawn")) {
                return false;
            }
            InstancesPlugin.teleportPlayerToInstance(entityRef, store, targetWorld, overrideReturn);
            noteTeleportQueued(livePlayerRef);
            return true;
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin,
                    Level.WARNING,
                    ex,
                    "[ELPortal] Failed to teleport %s into %s",
                    playerRef.getUsername(),
                    targetWorld.getName());
            return false;
        }
    }

    @Nullable
    private static PlayerRef resolveLivePlayerRef(@Nonnull PlayerRef playerRef) {
        Ref<EntityStore> reference = playerRef.getReference();
        if (reference != null && reference.isValid()) {
            return playerRef;
        }

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return null;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }

        PlayerRef refreshed = universe.getPlayer(playerUuid);
        if (refreshed == null) {
            return null;
        }

        Ref<EntityStore> refreshedRef = refreshed.getReference();
        return refreshedRef != null && refreshedRef.isValid() ? refreshed : null;
    }

    private static boolean canQueueTeleport(@Nullable PlayerRef playerRef,
                                            @Nonnull Ref<EntityStore> entityRef,
                                            @Nonnull Store<EntityStore> store,
                                            @Nonnull String source) {
        try {
            Archetype<EntityStore> archetype = store.getArchetype(entityRef);
            if (archetype != null) {
                if (archetype.contains(Teleport.getComponentType())) {
                    log(Level.FINE,
                            "[ELPortal] Teleport queue blocked: existing Teleport component source=%s player=%s",
                            source,
                            playerRef == null ? "unknown" : playerRef.getUsername());
                    return false;
                }

                if (archetype.contains(PendingTeleport.getComponentType())) {
                    log(Level.FINE,
                            "[ELPortal] Teleport queue blocked: pending teleport in-flight source=%s player=%s",
                            source,
                            playerRef == null ? "unknown" : playerRef.getUsername());
                    return false;
                }
            }

            Player playerComponent = store.getComponent(entityRef, Player.getComponentType());
            if (playerComponent != null && playerComponent.isWaitingForClientReady()) {
                log(Level.FINE,
                        "[ELPortal] Teleport queue blocked: waiting for ClientReady source=%s player=%s",
                        source,
                        playerRef == null ? "unknown" : playerRef.getUsername());
                return false;
            }

            return true;
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin,
                    Level.FINE,
                    ex,
                    "[ELPortal] Teleport queue guard failed open source=%s player=%s",
                    source,
                    playerRef == null ? "unknown" : playerRef.getUsername());
            return true;
        }
    }

    private static void noteTeleportQueued(@Nullable PlayerRef playerRef) {
        if (playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid != null) {
            PLAYER_LAST_TELEPORT_REQUEST_MILLIS.put(playerUuid, System.currentTimeMillis());
        }
    }

    private static void queueBlockedDirectEntryReturn(@Nonnull PlayerRef playerRef,
                                                      @Nonnull String blockedWorldName) {
        UUID blockPlayerUuid = playerRef.getUuid();
        if (blockPlayerUuid == null) {
            return;
        }

        PLAYER_PENDING_BLOCK_RETURNS.put(blockPlayerUuid, blockedWorldName);
        // Apply a short grace period so the proximity scanner cannot race
        // returnPlayerToEntryPortal before onPlayerReady fires.
        PortalProximityManager.markPlayerEnterInstance(blockPlayerUuid);
    }

    private static boolean hasRecentManagedEntryContext(@Nonnull PlayerRef playerRef) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return false;
        }

        Long lastQueued = PLAYER_LAST_TELEPORT_REQUEST_MILLIS.get(playerUuid);
        if (lastQueued == null) {
            return false;
        }

        return (System.currentTimeMillis() - lastQueued) <= MANAGED_ENTRY_CONTEXT_TTL_MILLIS;
    }

    @Nonnull
    private static DeathReentryDecision evaluateDeathReentry(@Nonnull PlayerRef playerRef,
                                                             @Nonnull World targetWorld) {
        if (filesManager == null) {
            return DeathReentryDecision.allow();
        }

        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return DeathReentryDecision.allow();
        }

        String targetWorldName = targetWorld.getName();
        if (targetWorldName == null) {
            return DeathReentryDecision.allow();
        }

        String targetGateIdentity = findGateIdentityForInstanceWorld(targetWorldName);
        String canonicalTargetGateIdentity = targetGateIdentity == null || targetGateIdentity.isBlank()
                ? null
                : canonicalizeGateIdentity(targetGateIdentity);

        boolean gateLocked = false;
        Set<String> gateLocks = PLAYER_DEATH_REENTRY_GATE_LOCKS.get(playerUuid);
        if (gateLocks != null && !gateLocks.isEmpty()
                && canonicalTargetGateIdentity != null
                && gateLocks.contains(canonicalTargetGateIdentity)) {
            gateLocked = true;
        }

        boolean worldLocked = false;
        Set<String> worldLocks = PLAYER_DEATH_REENTRY_LOCKS.get(playerUuid);
        if (worldLocks != null && !worldLocks.isEmpty() && worldLocks.contains(targetWorldName)) {
            worldLocked = true;
        }

        if (!gateLocked && !worldLocked) {
            return DeathReentryDecision.allow();
        }

        if (filesManager.allowDungeonReentryAfterDeath()) {
            return DeathReentryDecision.allowByConfig();
        }

        return DeathReentryDecision.block();
    }

    private static boolean shouldSkipFixedSpawnTeleport(@Nonnull PlayerRef playerRef,
                                                        @Nonnull World targetWorld,
                                                        @Nonnull Transform spawnTransform) {
        UUID playerUuid = playerRef.getUuid();
        long now = System.currentTimeMillis();
        if (playerUuid != null) {
            Long lastTeleport = PLAYER_LAST_TELEPORT_REQUEST_MILLIS.get(playerUuid);
            if (lastTeleport != null && (now - lastTeleport) < FIXED_SPAWN_TELEPORT_COOLDOWN_MILLIS) {
                return true;
            }
        }

        Transform current = playerRef.getTransform();
        if (current == null || current.getPosition() == null || spawnTransform.getPosition() == null) {
            return false;
        }

        UUID currentWorldUuid = playerRef.getWorldUuid();
        UUID targetWorldUuid = targetWorld.getWorldConfig() == null ? null : targetWorld.getWorldConfig().getUuid();
        if (currentWorldUuid == null || targetWorldUuid == null || !targetWorldUuid.equals(currentWorldUuid)) {
            return false;
        }

        double dx = current.getPosition().x - spawnTransform.getPosition().x;
        double dy = current.getPosition().y - spawnTransform.getPosition().y;
        double dz = current.getPosition().z - spawnTransform.getPosition().z;
        double distanceSq = (dx * dx) + (dy * dy) + (dz * dz);
        return distanceSq <= 9.0;
    }

    private static void broadcastEntry(@Nonnull PlayerRef playerRef,
                                       @Nonnull String displayName,
                                       int levelMin,
                                       int levelMax) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        universe.sendMessage(
                Message.raw(playerRef.getUsername() + " has entered " + displayName
                        + " [Level " + levelMin + "-" + levelMax + "]").color("#ffcc66")
        );
    }

    private static void log(@Nonnull Level level, @Nonnull String message, Object... args) {
        AddonLoggingManager.log(plugin, level, message, args);
    }

    @Nonnull
    private static List<String> getDeathLockedPlayerUuidsForGate(@Nonnull String gateIdentity,
                                                                  @Nonnull String instanceWorldName) {
        if (gateIdentity.isBlank() && instanceWorldName.isBlank()) {
            return List.of();
        }

        String canonicalGateIdentity = gateIdentity.isBlank()
                ? null
                : canonicalizeGateIdentity(gateIdentity);

        Set<String> playerUuidSet = new HashSet<>();
        if (canonicalGateIdentity != null) {
            for (Map.Entry<UUID, Set<String>> entry : PLAYER_DEATH_REENTRY_GATE_LOCKS.entrySet()) {
                Set<String> gateLocks = entry.getValue();
                if (gateLocks == null || gateLocks.isEmpty() || !gateLocks.contains(canonicalGateIdentity)) {
                    continue;
                }
                playerUuidSet.add(entry.getKey().toString());
            }
        }

        if (!instanceWorldName.isBlank()) {
            for (Map.Entry<UUID, Set<String>> entry : PLAYER_DEATH_REENTRY_LOCKS.entrySet()) {
                Set<String> lockedInstances = entry.getValue();
                if (lockedInstances == null || !lockedInstances.contains(instanceWorldName)) {
                    continue;
                }
                playerUuidSet.add(entry.getKey().toString());
            }
        }

        List<String> playerUuids = new ArrayList<>(playerUuidSet);

        Collections.sort(playerUuids);
        return playerUuids;
    }

    private record DeathReentryDecision(boolean blocked,
                                        boolean allowedByConfig) {
        @Nonnull
        private static DeathReentryDecision allow() {
            return new DeathReentryDecision(false, false);
        }

        @Nonnull
        private static DeathReentryDecision allowByConfig() {
            return new DeathReentryDecision(false, true);
        }

        @Nonnull
        private static DeathReentryDecision block() {
            return new DeathReentryDecision(true, false);
        }
    }

    private record LevelRange(int min, int max) {
    }

    private record PendingLevelProfile(int min,
                                       int max,
                                       int bossLevelFromRangeMaxOffset,
                                       @Nonnull String rankLetter) {
        private PendingLevelProfile {
            Objects.requireNonNull(rankLetter, "rankLetter");
        }
    }

    private record GateInstanceExpectation(@Nonnull String blockId,
                                           @Nonnull String routingName,
                                           @Nonnull String expectedGroupId,
                                           @Nullable String expectedWorldId,
                                           long createdAtMillis) {
    }

    public record ReturnTargetDiagnostics(@Nonnull String source,
                                          @Nonnull String worldName,
                                          @Nullable Transform returnTransform) {
        public ReturnTargetDiagnostics {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(worldName, "worldName");
        }
    }

    private record ReturnTarget(@Nullable UUID worldUuid,
                                @Nonnull String worldName,
                                @Nullable Transform returnTransform) {
        private ReturnTarget {
            Objects.requireNonNull(worldName, "worldName");
        }
    }


    private record PendingGateEntry(@Nonnull String gateIdentity,
                                    @Nonnull String blockId,
                                    @Nonnull String routingName,
                                    long createdAtMillis) {
        private PendingGateEntry {
            Objects.requireNonNull(gateIdentity, "gateIdentity");
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(routingName, "routingName");
        }
    }

    private record PendingDeathReturn(@Nonnull String returnWorldName,
                                      @Nonnull Transform returnTransform,
                                      @Nonnull String sourceWorldName,
                                      long createdAtMillis) {
        private PendingDeathReturn {
            Objects.requireNonNull(returnWorldName, "returnWorldName");
            Objects.requireNonNull(returnTransform, "returnTransform");
            Objects.requireNonNull(sourceWorldName, "sourceWorldName");
        }
    }
}
