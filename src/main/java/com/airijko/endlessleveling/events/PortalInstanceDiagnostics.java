package com.airijko.endlessleveling.events;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PortalInstanceDiagnostics {

    private static final Map<String, InstanceDebugDefinition> INSTANCE_DEFINITIONS = Map.of(
            "EL_MJ_Instance_D01", new InstanceDebugDefinition("Major Dungeons I", List.of(spawn(0.0, 130.0, 0.0))),
            "EL_MJ_Instance_D02", new InstanceDebugDefinition("Major Dungeons II", List.of(spawn(500.0, 130.0, 0.0))),
            "EL_MJ_Instance_D03", new InstanceDebugDefinition("Major Dungeons III", List.of(spawn(500.0, 130.0, 0.0))),
            "EL_Endgame_Frozen_Dungeon", new InstanceDebugDefinition("Endgame Frozen Dungeon", List.of(spawn(-17.0, 104.0, 65.0))),
            "EL_Endgame_Swamp_Dungeon", new InstanceDebugDefinition("Endgame Swamp Dungeon", List.of(spawn(4.0, 115.0, 145.0))),
            "EL_Endgame_Golem_Void", new InstanceDebugDefinition(
                    "Endgame Golem Void",
                    List.of(
                            spawn(300.0, 130.0, 0.0),
                            spawn(212.0, 130.0, 212.0),
                            spawn(0.0, 130.0, 300.0),
                            spawn(-212.0, 130.0, 212.0),
                            spawn(-300.0, 130.0, 0.0),
                            spawn(-212.0, 130.0, -212.0),
                            spawn(0.0, 130.0, -300.0),
                            spawn(212.0, 130.0, -212.0)
                    )
            )
    );

    private static final Map<String, PendingDeath> PENDING_DEATHS = new ConcurrentHashMap<>();

    private static JavaPlugin plugin;

    private PortalInstanceDiagnostics() {
    }

    public static void initialize(@Nonnull JavaPlugin owner) {
        plugin = owner;
    }

    public static void onAddPlayerToWorld(@Nonnull AddPlayerToWorldEvent event) {
        PlayerRef playerRef = getPlayerRef(event.getHolder());
        if (playerRef == null) {
            return;
        }

        World world = event.getWorld();
        String worldName = world.getName();
        InstanceDebugDefinition definition = INSTANCE_DEFINITIONS.get(worldName);
        PendingDeath pendingDeath = PENDING_DEATHS.remove(playerRef.getUsername());
        if (definition == null && pendingDeath == null) {
            return;
        }

        world.execute(() -> {
            Vector3d actualPosition = resolvePosition(playerRef, event.getHolder());
            if (pendingDeath != null) {
                boolean removedFromDungeon = !INSTANCE_DEFINITIONS.containsKey(worldName) || !pendingDeath.worldName.equals(worldName);
                log(Level.INFO,
                        "player-return-after-death player=%s deathWorld=%s currentWorld=%s removedFromDungeon=%s actual=%s",
                        playerRef.getUsername(),
                        pendingDeath.worldName,
                        worldName,
                        removedFromDungeon,
                        format(actualPosition));
            }

            if (definition != null) {
                SpawnMatch nearestSpawn = definition.findNearest(actualPosition);
                log(Level.INFO,
                        "player-enter player=%s instance=%s label=%s actual=%s expected=%s distance=%.3f",
                        playerRef.getUsername(),
                        worldName,
                        definition.label,
                        format(actualPosition),
                        format(nearestSpawn.expected),
                        nearestSpawn.distance);
            }
        });
    }

    public static void onDrainPlayerFromWorld(@Nonnull DrainPlayerFromWorldEvent event) {
        PlayerRef playerRef = getPlayerRef(event.getHolder());
        if (playerRef == null) {
            return;
        }

        String worldName = event.getWorld().getName();
        PendingDeath pendingDeath = PENDING_DEATHS.get(playerRef.getUsername());
        if (!INSTANCE_DEFINITIONS.containsKey(worldName) && (pendingDeath == null || !pendingDeath.worldName.equals(worldName))) {
            return;
        }

        log(Level.INFO,
                "player-drain player=%s sourceWorld=%s pendingDeath=%s drainTransform=%s",
                playerRef.getUsername(),
                worldName,
                pendingDeath != null,
                format(event.getTransform() == null ? null : event.getTransform().getPosition()));
    }

    public static void onWorldRemoved(@Nonnull RemoveWorldEvent event) {
        String worldName = event.getWorld().getName();
        if (!INSTANCE_DEFINITIONS.containsKey(worldName)) {
            return;
        }

        log(Level.INFO,
                "world-remove world=%s reason=%s cancelled=%s",
                worldName,
                event.getRemovalReason(),
                event.isCancelled());
    }

    public static void onPlayerDeath(@Nonnull Player player, @Nullable Vector3d actualPosition) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }

        String worldName = world.getName();
        InstanceDebugDefinition definition = INSTANCE_DEFINITIONS.get(worldName);
        if (definition == null) {
            return;
        }

        SpawnMatch nearestSpawn = definition.findNearest(actualPosition);
        PENDING_DEATHS.put(player.getDisplayName(), new PendingDeath(worldName));
        log(Level.INFO,
                "player-death player=%s instance=%s label=%s actual=%s expected=%s distance=%.3f awaitingDrain=true",
                player.getDisplayName(),
                worldName,
                definition.label,
                format(actualPosition),
                format(nearestSpawn.expected),
                nearestSpawn.distance);
    }

    @Nullable
    private static PlayerRef getPlayerRef(@Nonnull Holder<EntityStore> holder) {
        return holder.getComponent(PlayerRef.getComponentType());
    }

    @Nullable
    private static Vector3d resolvePosition(@Nonnull PlayerRef playerRef, @Nonnull Holder<EntityStore> holder) {
        Ref<EntityStore> reference = playerRef.getReference();
        if (reference != null && reference.isValid()) {
            Store<EntityStore> store = reference.getStore();
            TransformComponent transform = store.getComponent(reference, TransformComponent.getComponentType());
            if (transform != null) {
                return transform.getPosition();
            }
        }

        TransformComponent transform = holder.getComponent(TransformComponent.getComponentType());
        return transform == null ? null : transform.getPosition();
    }

    @Nonnull
    private static Vector3d spawn(double x, double y, double z) {
        return new Vector3d(x, y, z);
    }

    @Nonnull
    private static String format(@Nullable Vector3d position) {
        if (position == null) {
            return "<unknown>";
        }
        return String.format("(%.2f, %.2f, %.2f)", position.x, position.y, position.z);
    }

    private static void log(@Nonnull Level level, @Nonnull String message, Object... args) {
        if (plugin == null) {
            return;
        }
        plugin.getLogger().at(level).log("[ELPortal] " + message, args);
    }

    private record PendingDeath(@Nonnull String worldName) {
    }

    private record InstanceDebugDefinition(@Nonnull String label, @Nonnull List<Vector3d> expectedSpawns) {
        @Nonnull
        private SpawnMatch findNearest(@Nullable Vector3d actual) {
            if (expectedSpawns.isEmpty()) {
                return new SpawnMatch(null, Double.NaN);
            }
            if (actual == null) {
                return new SpawnMatch(expectedSpawns.get(0), Double.NaN);
            }

            Vector3d nearest = expectedSpawns.get(0);
            double nearestDistance = squaredDistance(actual, nearest);
            for (int index = 1; index < expectedSpawns.size(); index++) {
                Vector3d candidate = expectedSpawns.get(index);
                double candidateDistance = squaredDistance(actual, candidate);
                if (candidateDistance < nearestDistance) {
                    nearest = candidate;
                    nearestDistance = candidateDistance;
                }
            }
            return new SpawnMatch(nearest, Math.sqrt(nearestDistance));
        }
    }

    private record SpawnMatch(@Nullable Vector3d expected, double distance) {
    }

    private static double squaredDistance(@Nonnull Vector3d left, @Nonnull Vector3d right) {
        double deltaX = left.x - right.x;
        double deltaY = left.y - right.y;
        double deltaZ = left.z - right.z;
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }
}