package com.airijko.endlessleveling.events;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.api.PlayerSnapshot;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.instances.config.WorldReturnPoint;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
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

    private static final Map<String, String> ROUTING_TO_DISPLAY = Map.of(
            "EL_MJ_Instance_D01", "Major Dungeon I",
            "EL_MJ_Instance_D02", "Major Dungeon II",
            "EL_MJ_Instance_D03", "Major Dungeon III",
            "EL_Endgame_Frozen_Dungeon", "Endgame Frozen Dungeon",
            "EL_Endgame_Golem_Void", "Endgame Golem Void",
            "EL_Endgame_Swamp_Dungeon", "Endgame Swamp Dungeon"
    );

    private static JavaPlugin plugin;

    private PortalLeveledInstanceRouter() {
    }

    public static void initialize(@Nonnull JavaPlugin owner) {
        plugin = owner;
    }

    public static void onAddPlayerToWorld(@Nonnull AddPlayerToWorldEvent event) {
        World routingWorld = event.getWorld();
        String routingName = routingWorld.getName();

        String suffix = ROUTING_TO_SUFFIX.get(routingName);
        if (suffix == null) {
            return;
        }

        PlayerRef playerRef = getPlayerRef(event.getHolder());
        if (playerRef == null) {
            return;
        }

        UUID playerUuid = playerRef.getUuid();
        int level = resolvePlayerLevel(playerUuid);
        int levelMin = (level / 15) * 15;
        int levelMax = levelMin + 15;
        String leveledWorldName = "EL_LVL_" + levelMin + "-" + levelMax + "_" + suffix;
        String displayName = ROUTING_TO_DISPLAY.getOrDefault(routingName, routingName);

        log(Level.INFO, "[ELPortal] Routing player=%s from=%s to=%s level=%d bracket=%d-%d",
                playerRef.getUsername(), routingName, leveledWorldName, level, levelMin, levelMax);

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

        World existing = universe.getWorld(leveledWorldName);
        if (existing != null) {
            if (teleportToInstanceSpawn(playerRef, existing, returnTransform)) {
                broadcastEntry(playerRef, displayName, levelMin, levelMax);
            }
        } else {
            instances.spawnInstance(routingName, leveledWorldName, returnWorld, returnTransform)
                    .thenAccept(leveled -> {
                        if (teleportToInstanceSpawn(playerRef, leveled, returnTransform)) {
                            broadcastEntry(playerRef, displayName, levelMin, levelMax);
                            log(Level.INFO, "[ELPortal] Created leveled instance %s for bracket %d-%d",
                                    leveledWorldName, levelMin, levelMax);
                        }
                    })
                    .exceptionally(ex -> {
                        if (plugin != null) {
                            plugin.getLogger().at(Level.WARNING).withCause(ex)
                                    .log("[ELPortal] Failed to spawn leveled instance %s", leveledWorldName);
                        }
                        return null;
                    });
        }
    }

    @Nonnull
    public static String buildLeveledWorldName(@Nonnull String routingName, int playerLevel) {
        String suffix = ROUTING_TO_SUFFIX.getOrDefault(routingName, routingName);
        int levelMin = (playerLevel / 15) * 15;
        int levelMax = levelMin + 15;
        return "EL_LVL_" + levelMin + "-" + levelMax + "_" + suffix;
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

    private static int resolvePlayerLevel(@Nonnull UUID uuid) {
        PlayerSnapshot snapshot = EndlessLevelingAPI.get().getPlayerSnapshot(uuid);
        return snapshot != null ? Math.max(1, snapshot.level()) : 1;
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
            plugin.getLogger().at(level).log(message, args);
        }
    }
}
