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
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.logging.Level;

public final class PortalLeveledInstanceRouter {

    private static final Map<String, String> ROUTING_TO_SUFFIX = Map.of(
            "EL_MJ_Instance_D01", "MJ_D01",
            "EL_MJ_Instance_D02", "MJ_D02",
            "EL_MJ_Instance_D03", "MJ_D03",
            "EL_Endgame_Frozen_Dungeon", "EG_Frozen",
            "EL_Endgame_Golem_Void", "EG_Golem",
            "EL_Endgame_Swamp_Dungeon", "EG_Swamp"
    );

    private static final Map<String, String> INSTANCE_TEMPLATE_TO_SUFFIX = Map.ofEntries(
            Map.entry("EL_MJ_Instance_D01", "MJ_D01"),
            Map.entry("EL_MJ_Instance_D02", "MJ_D02"),
            Map.entry("EL_MJ_Instance_D03", "MJ_D03"),
            Map.entry("MJ_Instance_D01", "MJ_D01"),
            Map.entry("MJ_Instance_D02", "MJ_D02"),
            Map.entry("MJ_Instance_D03", "MJ_D03"),
            Map.entry("EL_Endgame_Frozen_Dungeon", "EG_Frozen"),
            Map.entry("EL_Endgame_Golem_Void", "EG_Golem"),
            Map.entry("EL_Endgame_Swamp_Dungeon", "EG_Swamp"),
            Map.entry("Endgame_Frozen_Dungeon", "EG_Frozen"),
            Map.entry("Endgame_Golem_Void", "EG_Golem"),
            Map.entry("Endgame_Swamp_Dungeon", "EG_Swamp")
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

    /** Player UUID -> routing template -> throttle-until millis to suppress duplicate spawn races. */
    private static final Map<UUID, Map<String, Long>> PLAYER_ROUTING_THROTTLES = new ConcurrentHashMap<>();

    /** Gate key (world+xyz) -> instance world name (e.g., "uuid:120:65:-40" -> "instance-EL_MJ_Instance_D01-abc123") */
    private static final Map<String, String> GATE_KEY_TO_INSTANCE_NAME = new ConcurrentHashMap<>();
    /** Gate identity -> expected instance metadata captured at gate spawn time. */
    private static final Map<String, GateInstanceExpectation> GATE_INSTANCE_EXPECTATIONS = new ConcurrentHashMap<>();
    /** Gate key -> spawn lock start millis to prevent concurrent duplicate spawns per gate. */
    private static final Map<String, Long> GATE_SPAWN_IN_FLIGHT = new ConcurrentHashMap<>();
    /** Instance world names currently being reloaded after unload while still paired to an active gate. */
    private static final Set<String> PAIRED_INSTANCE_RELOADS_IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private static final long ROUTING_DEDUPE_WINDOW_MILLIS = 5000L;
    private static final long GATE_SPAWN_STALE_MILLIS = 120000L;

    /** Player UUID → latest known entry target used for custom return portal fallback. */
    private static final Map<UUID, ReturnTarget> PLAYER_ENTRY_TARGETS = new ConcurrentHashMap<>();

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

    public static void shutdown() {
        PENDING_LEVEL_RANGES.clear();
        PENDING_LEVEL_RANGES_BY_GATE.clear();
        ACTIVE_LEVEL_RANGES.clear();
        ACTIVE_BOSS_LEVELS.clear();
        ACTIVE_RANK_LETTERS.clear();
        PLAYER_ENTRY_TARGETS.clear();
        PLAYER_ROUTING_THROTTLES.clear();
        GATE_KEY_TO_INSTANCE_NAME.clear();
        GATE_INSTANCE_EXPECTATIONS.clear();
        GATE_SPAWN_IN_FLIGHT.clear();
        PAIRED_INSTANCE_RELOADS_IN_FLIGHT.clear();
        filesManager = null;
        plugin = null;
    }

    /**
     * Saves current gate-to-instance mappings and level profiles to persistent storage.
     * Called during server shutdown to preserve dungeons across restarts.
     */
    public static void saveGateInstances() {
        try {
            GateInstancePersistenceManager.saveGateInstances(GATE_KEY_TO_INSTANCE_NAME, ACTIVE_LEVEL_RANGES);
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Failed to save gate instances: %s", ex.getMessage());
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

            int restored = 0;
            for (GateInstancePersistenceManager.StoredGateInstance stored : savedInstances.values()) {
                try {
                    // Check if the saved instance world still exists
                    World savedWorld = universe.getWorld(stored.instanceWorldName);
                    if (savedWorld != null) {
                        // Restore the gate-to-instance pairing
                        GATE_KEY_TO_INSTANCE_NAME.put(stored.gateKey, stored.instanceWorldName);
                        
                        // Restore the level range as a LevelRange record
                        ACTIVE_LEVEL_RANGES.put(
                            stored.instanceWorldName,
                            new LevelRange(stored.minLevel, stored.maxLevel)
                        );
                        restored++;
                        
                        log(Level.INFO,
                            "[ELPortal] Restored gate %s → instance %s (levels %d-%d)",
                            stored.gateKey, stored.instanceWorldName, stored.minLevel, stored.maxLevel);
                    }
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

        String routingName = resolveRoutingName(blockId);
        if (routingName == null) {
            return;
        }

        String expectedGroupId = toInstanceGroupId(gateIdentity, routingName);
        GATE_INSTANCE_EXPECTATIONS.put(
                gateIdentity,
                new GateInstanceExpectation(
                        blockId,
                        routingName,
                expectedGroupId,
                expectedGroupId,
                        System.currentTimeMillis()));
        log(Level.INFO,
                "[ELPortal] Gate expectation cached gateId=%s block=%s template=%s expectedGroupId=%s expectedWorldId=%s",
                gateIdentity,
                blockId,
                routingName,
            expectedGroupId,
            expectedGroupId);

        // Emit a warning-level twin log so this is visible even when addon INFO logs are suppressed.
        log(Level.WARNING,
                "[ELPortal] Gate expectation cached gateId=%s block=%s template=%s expectedGroupId=%s expectedWorldId=%s",
                gateIdentity,
                blockId,
                routingName,
                expectedGroupId,
                expectedGroupId);
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
        String instanceName = GATE_KEY_TO_INSTANCE_NAME.remove(gateIdentity);
        PENDING_LEVEL_RANGES_BY_GATE.remove(gateIdentity);
        GATE_INSTANCE_EXPECTATIONS.remove(gateIdentity);
        GATE_SPAWN_IN_FLIGHT.remove(gateIdentity);
        if (instanceName == null || instanceName.isBlank()) {
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
            int bossOffset = Math.max(0, bossLevel - Math.max(min, max));
            PendingLevelProfile profile = new PendingLevelProfile(
                    min,
                    max,
                    bossOffset,
                    resolveRankLetterFromBlockId(blockId));
            PENDING_LEVEL_RANGES.put(routingName, profile);
            if (gateIdentity != null && !gateIdentity.isBlank()) {
                PENDING_LEVEL_RANGES_BY_GATE.put(gateIdentity, profile);
            }
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

        String gateKey = stableGateId != null && !stableGateId.isBlank()
            ? stableGateId
            : buildGateKey(sourceWorld, x, y, z);
        logGateEntryExpectation(gateKey, blockId, routingName, playerRef.getUsername());
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
        if (directWorldSuffix != null) {
            enforcePersistentInstanceLifecycle(routingWorld, "direct-entry");
            String directWorldTemplate = resolveTemplateNameFromWorldName(routingName);
            boolean originalTemplate = isOriginalTemplateName(directWorldTemplate);

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

        boolean started = routePlayerToTemplate(playerRef, routingWorld, null, null, routingName, returnWorld, returnTransform);
        if (!started) {
            clearRoutingThrottle(playerRef, routingName);
        }
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

        String suffix = resolveSuffixFromWorldName(world.getName());
        if (suffix == null) {
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
        // Check if this gate already has a paired instance
        String existingInstance = GATE_KEY_TO_INSTANCE_NAME.get(gateKey);
        if (existingInstance != null) {
            cacheResolvedInstanceWorld(gateKey, existingInstance, blockId, routingName, "existing-pairing");
            Universe universe = Universe.get();
            if (universe != null) {
                World targetInstance = null;
                Object worldObject = universe.getWorlds().get(existingInstance);
                if (worldObject instanceof World castWorld) {
                    targetInstance = castWorld;
                }
                if (targetInstance != null) {
                    // Reuse existing instance
                    if (teleportToInstanceSpawn(playerRef, targetInstance, playerRef.getTransform())) {
                        rememberEntryTarget(playerRef, returnWorld, playerRef.getTransform());
                        log(Level.INFO, "[ELPortal] Reused existing gate instance %s for key=%s block=%s player=%s",
                                existingInstance, gateKey, blockId, playerRef.getUsername());
                        return true;
                    }
                }

                // Instance may be unloaded while still paired to an active gate; reopen it instead of spawning a new one.
                if (universe.isWorldLoadable(existingInstance)) {
                    CompletableFuture<World> loadFuture = universe.loadWorld(existingInstance);
                    loadFuture.thenAccept(loadedWorld -> {
                        if (loadedWorld == null) {
                            GATE_KEY_TO_INSTANCE_NAME.remove(gateKey, existingInstance);
                            return;
                        }

                        enforcePersistentInstanceLifecycle(loadedWorld, "paired-instance-load");
                        restoreActiveInstanceOverride(existingInstance);

                        Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
            queueTeleportToInstanceSpawn(playerRef,
                loadedWorld,
                effectiveReturnTransform,
                () -> {
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
                    }).exceptionally(ex -> {
                        GATE_KEY_TO_INSTANCE_NAME.remove(gateKey, existingInstance);
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
            // Mapping became stale if world no longer exists.
            GATE_KEY_TO_INSTANCE_NAME.remove(gateKey, existingInstance);
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

        Object loaded = universe.getWorlds().get(expectedWorldId);
        if (loaded instanceof World loadedWorld) {
            enforcePersistentInstanceLifecycle(loadedWorld, "expected-world-loaded");
            Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
            if (teleportToInstanceSpawn(playerRef, loadedWorld, effectiveReturnTransform)) {
                GATE_KEY_TO_INSTANCE_NAME.put(gateKey, expectedWorldId);
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
                    restoreActiveInstanceOverride(expectedWorldId);
                    Transform effectiveReturnTransform = returnTransform != null ? returnTransform : playerRef.getTransform();
                    queueTeleportToInstanceSpawn(playerRef,
                            reloadedWorld,
                            effectiveReturnTransform,
                            () -> {
                                GATE_KEY_TO_INSTANCE_NAME.put(gateKey, expectedWorldId);
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
        
        // Use filesystem-safe deterministic group ID so InstancesPlugin can reuse on all OSes.
        String instanceGroupId = gateKey != null ? toInstanceGroupId(gateKey, routingName) : routingName;
        log(Level.INFO,
            "[ELPortal] Spawning routed instance player=%s template=%s gateId=%s instanceGroupId=%s",
            playerRef.getUsername(),
            routingName,
            gateKey == null ? "<none>" : gateKey,
            instanceGroupId);
        instances.spawnInstance(routingName, instanceGroupId, returnWorld, effectiveReturnTransform)
                .thenAccept(spawned -> {
                    try {
                        enforcePersistentInstanceLifecycle(spawned, "spawn-instance");
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

                        queueTeleportToInstanceSpawn(playerRef,
                            spawned,
                            effectiveReturnTransform,
                            () -> {
                                LevelRange range = registerInstanceLevelOverride(spawned.getName(), gateKey, routingName);
                                String suffix = ROUTING_TO_SUFFIX.get(routingName);
                                if (suffix != null) {
                                applyFixedGateSpawn(playerRef, spawned, suffix);
                                }
                                broadcastEntry(playerRef, displayName, range.min(), range.max());
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

    /**
     * Fallback teleportation: Returns player to the default world spawn when
     * normal return targets are unavailable after death. Logs a warning.
     */
    private static void fallbackReturnPlayerToWorldSpawn(@Nonnull PlayerRef playerRef, @Nonnull World sourceWorld) {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                log(Level.WARNING, "[ELPortal] Cannot fallback: Universe unavailable player=%s", playerRef.getUsername());
                return;
            }

            World defaultWorld = universe.getDefaultWorld();
            if (defaultWorld == null) {
                log(Level.WARNING, "[ELPortal] Cannot fallback: No default world available player=%s", playerRef.getUsername());
                return;
            }

            // Use spawn provider to get default spawn point
            Transform spawnTransform = null;
            if (defaultWorld.getWorldConfig() != null && defaultWorld.getWorldConfig().getSpawnProvider() != null) {
                spawnTransform = defaultWorld.getWorldConfig().getSpawnProvider().getSpawnPoint(defaultWorld, playerRef.getUuid());
            }

            if (spawnTransform == null) {
                // Fallback spawn location (0, 64, 0)
                spawnTransform = new Transform(0.0, 64.0, 0.0);
            }

            if (teleportToReturnTarget(playerRef, new ReturnTarget(null, defaultWorld.getName(), spawnTransform))) {
                log(Level.WARNING,
                    "[ELPortal] Fallback return to world spawn successful player=%s world=%s spawn=%s",
                    playerRef.getUsername(),
                    defaultWorld.getName(),
                    formatTransform(spawnTransform));
            } else {
                log(Level.WARNING, "[ELPortal] Fallback teleport failed player=%s world=%s", playerRef.getUsername(), defaultWorld.getName());
            }
        } catch (Exception ex) {
            log(Level.WARNING, "[ELPortal] Fallback teleport exception player=%s error=%s", playerRef.getUsername(), ex.getMessage());
        }
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

    /** Blocks to push the player backward from the portal entrance after a death return teleport. */
    private static final double DEATH_RETURN_OFFSET_BLOCKS = 3.0;

    /**
     * Teleports the player to their original portal entry location after death, offset away from the portal
     * so they do not immediately re-enter it. Falls back to the default world {@code /spawn} if the return
     * target is unavailable or the teleport fails.
     */
    public static void teleportPlayerToDeathReturnEntry(
            @Nonnull PlayerRef playerRef,
            @Nullable String returnWorldName,
            @Nullable Transform returnTransform,
            @Nonnull World sourceWorld) {
        if (returnWorldName == null || returnTransform == null) {
            log(Level.WARNING,
                    "[ELPortal] Death-return: no entry target for player=%s — falling back to world spawn",
                    playerRef.getUsername());
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            log(Level.WARNING,
                    "[ELPortal] Death-return: universe unavailable player=%s — falling back to world spawn",
                    playerRef.getUsername());
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
            return;
        }

        World returnWorld = null;
        Object worldObj = universe.getWorlds().get(returnWorldName);
        if (worldObj instanceof World w) {
            returnWorld = w;
        }

        if (returnWorld == null) {
            log(Level.WARNING,
                    "[ELPortal] Death-return: world not found player=%s returnWorld=%s — falling back to world spawn",
                    playerRef.getUsername(), returnWorldName);
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
            return;
        }

        Transform offsetTransform = computeDeathReturnOffset(returnTransform);
        boolean success = teleportToWorld(playerRef, returnWorld, offsetTransform);

        if (success) {
            log(Level.INFO,
                    "[ELPortal] Death-return: teleported player=%s to world=%s at %s",
                    playerRef.getUsername(), returnWorldName, formatTransform(offsetTransform));
        } else {
            log(Level.WARNING,
                    "[ELPortal] Death-return: teleport failed player=%s — falling back to world spawn",
                    playerRef.getUsername());
            fallbackReturnPlayerToWorldSpawn(playerRef, sourceWorld);
        }
    }

    /**
     * Computes a Transform offset 3 blocks behind the player's facing direction at the time they entered
     * the portal, so they do not re-enter it on respawn.
     */
    @Nonnull
    private static Transform computeDeathReturnOffset(@Nonnull Transform entryTransform) {
        Vector3d pos = entryTransform.getPosition();
        Vector3f rot = entryTransform.getRotation();

        double offsetX = 0.0;
        double offsetZ = 0.0;

        float yaw = rot.getYaw();
        if (!Float.isNaN(yaw)) {
            // The player was facing TOWARD the portal; move them in the opposite direction.
            // Hytale forward: dx = -sin(yaw), dz = -cos(yaw) → backward: +sin(yaw), +cos(yaw)
            offsetX = Math.sin(yaw) * DEATH_RETURN_OFFSET_BLOCKS;
            offsetZ = Math.cos(yaw) * DEATH_RETURN_OFFSET_BLOCKS;
        } else {
            // No valid yaw — apply a fixed +Z offset as a safe fallback
            offsetZ = DEATH_RETURN_OFFSET_BLOCKS;
        }

        return new Transform(new Vector3d(pos.x + offsetX, pos.y, pos.z + offsetZ), rot);
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
                                            @Nonnull Transform transform) {
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }
        PLAYER_ENTRY_TARGETS.put(playerUuid, new ReturnTarget(null, world.getName(), transform));
    }

    private static boolean teleportToReturnTarget(@Nonnull PlayerRef playerRef, @Nonnull ReturnTarget target) {
        Universe universe = Universe.get();
        if (universe == null || target.returnTransform() == null) {
            return false;
        }

        World world = target.worldUuid() != null ? universe.getWorld(target.worldUuid()) : null;
        if (world == null && !target.worldName().isBlank()) {
            Object worldObject = universe.getWorlds().get(target.worldName());
            if (worldObject instanceof World byName) {
                world = byName;
            }
        }
        return world != null && teleportToWorld(playerRef, world, target.returnTransform());
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
            return new ReturnTarget(null, "unknown", transform);
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
            PendingLevelProfile gatePending = PENDING_LEVEL_RANGES_BY_GATE.remove(gateIdentity);
            if (gatePending != null) {
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
                return pending;
            }
        }

        // Fallback: deterministic hash so the range is at least stable for the world's lifetime.
        int minLevel = DYNAMIC_MIN_LEVEL;
        int maxLevel = Math.max(minLevel, DYNAMIC_MAX_LEVEL);
        int rangeSize = Math.max(0, DYNAMIC_RANGE_SIZE);
        int maxStart = Math.max(minLevel, maxLevel - rangeSize);
        int span = Math.max(1, (maxStart - minLevel) + 1);

        String normalizedWorld = worldName.toLowerCase(Locale.ROOT);
        int start = minLevel + Math.floorMod(normalizedWorld.hashCode(), span);
        int end = Math.min(maxLevel, start + rangeSize);
        return new PendingLevelProfile(start, end, 5, "E");
    }

    @Nonnull
    private static LevelRange registerInstanceLevelOverride(@Nonnull String worldName,
                                                            @Nullable String gateIdentity,
                                                            @Nullable String templateHint) {
        PendingLevelProfile profile = resolveDynamicInstanceRange(worldName, gateIdentity, templateHint);
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api != null) {
            api.registerMobWorldFixedLevelOverride(
                    worldName,
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
        api.registerMobWorldFixedLevelOverride(worldName, worldName, range.min(), range.max(), bossOffset);
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
        String templateName = resolveTemplateNameFromWorldName(worldName);
        return templateName != null ? ROUTING_TO_DISPLAY.get(templateName) : null;
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

        String gateKey = anchor.gateId() != null && !anchor.gateId().isBlank()
            ? anchor.gateId()
            : buildGateKey(returnWorld, anchor.x(), anchor.y(), anchor.z());
        String existing = GATE_KEY_TO_INSTANCE_NAME.get(gateKey);
        if (Objects.equals(existing, instanceWorldName)) {
            return;
        }

        if (existing != null && !existing.isBlank() && isKnownLiveOrLoadableWorld(existing, universe)) {
            return;
        }

        GATE_KEY_TO_INSTANCE_NAME.put(gateKey, instanceWorldName);
        log(Level.INFO,
                "[ELPortal] Backfilled gate pairing key=%s block=%s -> %s (template=%s player=%s)",
                gateKey,
                blockId,
                instanceWorldName,
                canonicalTemplate,
                playerRef.getUsername());
    }

    private static boolean isKnownLiveOrLoadableWorld(@Nonnull String worldName, @Nonnull Universe universe) {
        Object loaded = universe.getWorlds().get(worldName);
        if (loaded instanceof World) {
            return true;
        }
        return universe.isWorldLoadable(worldName);
    }

    @Nonnull
    private static String toInstanceGroupId(@Nonnull String gateIdentity,
                                            @Nullable String routingName) {
        String templateToken = sanitizeInstanceToken(routingName, "el_dungeon");
        String gateToken = sanitizeInstanceToken(gateIdentity, "gate");
        gateToken = stripGatePrefix(gateToken);
        return templateToken + "_gate_" + gateToken;
    }

    @Nonnull
    private static String stripGatePrefix(@Nonnull String gateToken) {
        String normalized = gateToken;
        if (normalized.startsWith("el_gate_")) {
            normalized = normalized.substring("el_gate_".length());
        } else if (normalized.startsWith("gate_")) {
            normalized = normalized.substring("gate_".length());
        }

        return normalized.isBlank() ? "gate" : normalized;
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
                            toInstanceGroupId(gateIdentity, fallbackTemplate),
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
            log(Level.WARNING,
                    "[ELPortal] Gate expected world ID resolved gateId=%s source=%s worldId=%s",
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
            log(Level.WARNING,
                "[ELPortal] Gate expected world ID rotated (old world missing) gateId=%s source=%s old=%s new=%s",
                gateIdentity,
                source,
                existing.expectedWorldId(),
                resolvedWorldId);
            return;
        }

        log(Level.WARNING,
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
        teleportToWorld(playerRef, targetWorld, spawnTransform);
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
                targetWorld.execute(() -> placeReturnPortalAtSpawnIfAbsent(targetWorld, spawnTransform));
            }
        }
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

    private static void placeReturnPortalAtSpawnIfAbsent(@Nonnull World world, @Nonnull Transform spawnTransform) {
        if (spawnTransform.getPosition() == null) {
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
            return;
        }

        WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            log(Level.WARNING, "[ELPortal] Chunk not in memory at (%d, %d) \u2014 cannot place return portal world=%s", x, z, world.getName());
            return;
        }

        if (chunk.getBlock(x, y, z) == returnBlockIntId) {
            return;
        }

        chunk.setBlock(x, y, z, returnBlockIntId);
        log(Level.INFO, "[ELPortal] Placed Portal_Return at (%d, %d, %d) world=%s", x, y, z, world.getName());
    }

    private static boolean teleportToWorld(@Nonnull PlayerRef playerRef,
                                           @Nonnull World targetWorld,
                                           @Nonnull Transform targetTransform) {
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            store.addComponent(entityRef, Teleport.getComponentType(), Teleport.createForPlayer(targetWorld, targetTransform));
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
        Ref<EntityStore> entityRef = playerRef.getReference();
        if (entityRef == null || !entityRef.isValid()) {
            log(Level.WARNING, "[ELPortal] Missing/invalid player reference for %s", playerRef.getUsername());
            return false;
        }

        try {
            Store<EntityStore> store = entityRef.getStore();
            InstancesPlugin.teleportPlayerToInstance(entityRef, store, targetWorld, overrideReturn);
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
}
