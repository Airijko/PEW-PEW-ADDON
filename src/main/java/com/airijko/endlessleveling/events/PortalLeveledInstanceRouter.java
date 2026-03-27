package com.airijko.endlessleveling.events;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
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

    /** Routing world template name → level range announced when the portal gate was placed. */
    private static final Map<String, LevelRange> PENDING_LEVEL_RANGES = new ConcurrentHashMap<>();

    /** Instance world name → resolved level range, kept until the world is removed. */
    private static final Map<String, LevelRange> ACTIVE_LEVEL_RANGES = new ConcurrentHashMap<>();
    private static JavaPlugin plugin;

    private PortalLeveledInstanceRouter() {
    }

    public static void initialize(@Nonnull JavaPlugin owner) {
        plugin = owner;
    }

    /**
     * Called by the gate manager when a portal block is placed so the announced
     * level range is preserved and used when a player enters that instance.
     */
    public static void setPendingLevelRange(@Nonnull String blockId, int min, int max) {
        String routingName = BLOCK_ID_TO_ROUTING_NAME.get(blockId);
        if (routingName != null) {
            PENDING_LEVEL_RANGES.put(routingName, new LevelRange(min, max));
        }
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

        String displayName = ROUTING_TO_DISPLAY.getOrDefault(routingName, routingName);

        log(Level.INFO, "[ELPortal] Routing player=%s from=%s template=%s",
            playerRef.getUsername(), routingName, routingName);

        InstancesPlugin instances = InstancesPlugin.get();
        if (instances == null) {
            log(Level.WARNING, "[ELPortal] InstancesPlugin unavailable — level routing skipped for %s",
                    playerRef.getUsername());
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        World returnWorld = resolveReturnWorld(routingWorld, universe);
        Transform returnTransform = resolveReturnTransform(routingWorld, playerRef);
        instances.spawnInstance(routingName, null, returnWorld, returnTransform)
                .thenAccept(spawned -> {
                    if (teleportToInstanceSpawn(playerRef, spawned, returnTransform)) {
                        LevelRange range = registerInstanceLevelOverride(spawned.getName());
                        applyFixedGateSpawn(playerRef, spawned, suffix);
                        broadcastEntry(playerRef, displayName, range.min(), range.max());
                        log(Level.INFO, "[ELPortal] Created routed instance %s for template %s bracket %d-%d",
                                spawned.getName(), routingName, range.min(), range.max());
                    }
                })
                .exceptionally(ex -> {
                    if (plugin != null) {
                        plugin.getLogger().at(Level.WARNING).withCause(ex)
                                .log("[ELPortal] Failed to spawn routed instance from template %s", routingName);
                    }
                    return null;
                });
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

    @Nullable
    private static PlayerRef getPlayerRef(@Nonnull Holder<EntityStore> holder) {
        return holder.getComponent(PlayerRef.getComponentType());
    }

    private static LevelRange resolveDynamicInstanceRange(@Nonnull String worldName) {
        // If a gate was placed for this template, use its announced range (consumed once).
        String templateName = resolveTemplateNameFromWorldName(worldName);
        if (templateName != null) {
            LevelRange pending = PENDING_LEVEL_RANGES.remove(templateName);
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
        return new LevelRange(start, end);
    }

    @Nonnull
    private static LevelRange registerInstanceLevelOverride(@Nonnull String worldName) {
        LevelRange range = resolveDynamicInstanceRange(worldName);
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api != null) {
            api.registerMobWorldFixedLevelOverride(worldName, worldName, range.min(), range.max());
            log(Level.INFO, "[ELPortal] Registered level override world=%s range=%d-%d",
                    worldName, range.min(), range.max());
        }
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
            if (plugin != null) {
                plugin.getLogger().at(Level.WARNING).withCause(ex)
                        .log("[ELPortal] Failed to snap %s to fixed spawn in %s", playerRef.getUsername(), targetWorld.getName());
            }
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
            if (plugin != null) {
                plugin.getLogger().at(Level.WARNING).withCause(ex)
                        .log("[ELPortal] Failed to teleport %s into %s", playerRef.getUsername(), targetWorld.getName());
            }
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
        if (plugin != null) {
            if (args == null || args.length == 0) {
                plugin.getLogger().at(level).log(message);
            } else {
                plugin.getLogger().at(level).log(String.format(Locale.ROOT, message, args));
            }
        }
    }

    private record LevelRange(int min, int max) {
    }
}
