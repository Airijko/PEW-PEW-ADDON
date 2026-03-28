package com.airijko.endlessleveling.events;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.managers.AddonLoggingManager;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceEntityConfig;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.config.WorldReturnPoint;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Map<String, String> INSTANCE_TEMPLATE_TO_SUFFIX = Map.of(
        "EL_MJ_Instance_D01", "MJ_D01",
        "EL_MJ_Instance_D02", "MJ_D02",
        "EL_MJ_Instance_D03", "MJ_D03",
        "EL_Endgame_Frozen_Dungeon", "EG_Frozen",
        "EL_Endgame_Golem_Void", "EG_Golem",
        "EL_Endgame_Swamp_Dungeon", "EG_Swamp"
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

    /** Routing world template name → level profile announced when the portal gate was placed. */
    private static final Map<String, PendingLevelProfile> PENDING_LEVEL_RANGES = new ConcurrentHashMap<>();

    /** Instance world name → resolved level range, kept until the world is removed. */
    private static final Map<String, LevelRange> ACTIVE_LEVEL_RANGES = new ConcurrentHashMap<>();

    /** Player UUID → latest known entry target used for custom return portal fallback. */
    private static final Map<UUID, ReturnTarget> PLAYER_ENTRY_TARGETS = new ConcurrentHashMap<>();

    private static JavaPlugin plugin;

    private PortalLeveledInstanceRouter() {
    }

    public static void initialize(@Nonnull JavaPlugin owner) {
        plugin = owner;
    }

    public static void shutdown() {
        PENDING_LEVEL_RANGES.clear();
        ACTIVE_LEVEL_RANGES.clear();
        PLAYER_ENTRY_TARGETS.clear();
        plugin = null;
    }

    /**
     * Called by the gate manager when a portal block is placed so the announced
     * level range is preserved and used when a player enters that instance.
     */
    public static void setPendingLevelRange(@Nonnull String blockId, int min, int max, int bossLevel) {
        String routingName = resolveRoutingName(blockId);
        if (routingName != null) {
            int bossOffset = Math.max(0, bossLevel - Math.max(min, max));
            PENDING_LEVEL_RANGES.put(routingName, new PendingLevelProfile(min, max, bossOffset));
        }
    }

    public static void setPendingLevelRange(@Nonnull String blockId, int min, int max) {
        setPendingLevelRange(blockId, min, max, Math.max(min, max));
    }

    public static boolean enterPortalFromBlockId(@Nonnull PlayerRef playerRef,
                                                 @Nonnull World sourceWorld,
                                                 @Nonnull String blockId) {
        String routingName = resolveRoutingName(blockId);
        if (routingName == null) {
            log(Level.WARNING,
                    "[ELPortal] No routing template found for placed portal block=%s world=%s",
                    blockId,
                    sourceWorld.getName());
            return false;
        }

        return routePlayerToTemplate(playerRef, sourceWorld, routingName, sourceWorld, playerRef.getTransform());
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
            // Register the level range immediately so mobs are leveled correctly
            // before the player sends ClientReady (which fires ~4s later).
            registerInstanceLevelOverride(routingName);
            applyFixedGateSpawn(playerRef, routingWorld, directWorldSuffix);
            log(Level.INFO,
                    "[ELPortal] Applied direct instance spawn correction player=%s world=%s suffix=%s",
                    playerRef.getUsername(),
                    routingName,
                    directWorldSuffix);
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
        routePlayerToTemplate(playerRef, routingWorld, routingName, returnWorld, returnTransform);
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

    private static boolean routePlayerToTemplate(@Nonnull PlayerRef playerRef,
                                                 @Nonnull World sourceWorld,
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
        instances.spawnInstance(routingName, null, returnWorld, effectiveReturnTransform)
                .thenAccept(spawned -> {
                    if (teleportToInstanceSpawn(playerRef, spawned, effectiveReturnTransform)) {
                        LevelRange range = registerInstanceLevelOverride(spawned.getName());
                        String suffix = ROUTING_TO_SUFFIX.get(routingName);
                        if (suffix != null) {
                            applyFixedGateSpawn(playerRef, spawned, suffix);
                        }
                        broadcastEntry(playerRef, displayName, range.min(), range.max());
                        log(Level.INFO, "[ELPortal] Created routed instance %s for template %s bracket %d-%d",
                                spawned.getName(), routingName, range.min(), range.max());
                    }
                })
                .exceptionally(ex -> {
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
            return false;
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

            log(Level.WARNING,
                "[ELPortal] Custom return portal fallback unavailable player=%s source=%s hasSavedTarget=%s hasMetadataTarget=%s",
                playerRef.getUsername(),
                sourceWorld.getName(),
                saved != null,
                metadataTarget != null);
        return false;
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

    private static PendingLevelProfile resolveDynamicInstanceRange(@Nonnull String worldName) {
        // If a gate was placed for this template, use its announced range (consumed once).
        String templateName = resolveTemplateNameFromWorldName(worldName);
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
        return new PendingLevelProfile(start, end, 5);
    }

    @Nonnull
    private static LevelRange registerInstanceLevelOverride(@Nonnull String worldName) {
        PendingLevelProfile profile = resolveDynamicInstanceRange(worldName);
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
        return range;
    }

    /** Returns the resolved [min, max] level range for a gate instance world, or null if unknown. */
    @Nullable
    public static int[] getActiveInstanceRange(@Nonnull String worldName) {
        LevelRange r = ACTIVE_LEVEL_RANGES.get(worldName);
        return r != null ? new int[]{r.min(), r.max()} : null;
    }

    /** Removes the stored level range for a world (called on world removal to avoid memory leaks). */
    public static void clearActiveInstanceRange(@Nonnull String worldName) {
        ACTIVE_LEVEL_RANGES.remove(worldName);
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

    @Nullable
    private static String resolveSuffixFromWorldName(@Nonnull String worldName) {
        if (worldName.startsWith("instance-")) {
            for (Map.Entry<String, String> entry : INSTANCE_TEMPLATE_TO_SUFFIX.entrySet()) {
                if (worldName.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    @Nullable
    private static String resolveTemplateNameFromWorldName(@Nonnull String worldName) {
        // Check routing/template names first (the worldName may BE the template name for routed path)
        if (ROUTING_TO_SUFFIX.containsKey(worldName)) {
            return worldName;
        }
        // Check instance world names (e.g. "instance-EL_MJ_Instance_D02-<uuid>")
        for (String templateName : INSTANCE_TEMPLATE_TO_SUFFIX.keySet()) {
            if (worldName.contains(templateName)) {
                return templateName;
            }
        }
        return null;
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
    }

    @Nullable
    private static Transform resolveWorldSpawnTransform(@Nonnull World world, @Nonnull UUID playerUuid) {
        if (world.getWorldConfig() == null || world.getWorldConfig().getSpawnProvider() == null) {
            return null;
        }
        return world.getWorldConfig().getSpawnProvider().getSpawnPoint(world, playerUuid);
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

    private record PendingLevelProfile(int min, int max, int bossLevelFromRangeMaxOffset) {
    }

    private record ReturnTarget(@Nullable UUID worldUuid,
                                @Nonnull String worldName,
                                @Nullable Transform returnTransform) {
        private ReturnTarget {
            Objects.requireNonNull(worldName, "worldName");
        }
    }
}
