package com.airijko.endlessleveling.events;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.managers.AddonFilesManager;
import com.airijko.endlessleveling.managers.AddonLoggingManager;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.builtin.instances.config.InstanceEntityConfig;
import com.hypixel.hytale.builtin.instances.config.WorldReturnPoint;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PortalInstanceDiagnostics {

        private static final Map<String, InstanceDebugDefinition> INSTANCE_DEFINITIONS = Map.ofEntries(
            Map.entry("EL_MJ_Instance_D01", new InstanceDebugDefinition("Major Dungeons I", List.of(spawn(0.0, 130.0, 0.0)))),
            Map.entry("EL_MJ_Instance_D02", new InstanceDebugDefinition("Major Dungeons II", List.of(spawn(500.0, 130.0, 0.0)))),
            Map.entry("EL_MJ_Instance_D03", new InstanceDebugDefinition("Major Dungeons III", List.of(spawn(500.0, 130.0, 0.0)))),
            Map.entry("MJ_Instance_D01", new InstanceDebugDefinition("Major Dungeons I", List.of(spawn(0.0, 130.0, 0.0)))),
            Map.entry("MJ_Instance_D02", new InstanceDebugDefinition("Major Dungeons II", List.of(spawn(500.0, 130.0, 0.0)))),
            Map.entry("MJ_Instance_D03", new InstanceDebugDefinition("Major Dungeons III", List.of(spawn(500.0, 130.0, 0.0)))),
            Map.entry("Endgame_Frozen_Dungeon", new InstanceDebugDefinition("Endgame Frozen Dungeon", List.of(spawn(-17.0, 104.0, 65.0)))),
            Map.entry("Endgame_Swamp_Dungeon", new InstanceDebugDefinition("Endgame Swamp Dungeon", List.of(spawn(4.0, 115.0, 145.0)))),
            Map.entry("Endgame_Golem_Void", new InstanceDebugDefinition(
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
            )),
            Map.entry("EL_Endgame_Frozen_Dungeon", new InstanceDebugDefinition("Endgame Frozen Dungeon", List.of(spawn(-17.0, 104.0, 65.0)))),
            Map.entry("EL_Endgame_Swamp_Dungeon", new InstanceDebugDefinition("Endgame Swamp Dungeon", List.of(spawn(4.0, 115.0, 145.0)))),
            Map.entry("EL_Endgame_Golem_Void", new InstanceDebugDefinition(
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
            ))
        );

    private static final Map<UUID, PendingDeath> PENDING_DEATHS = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_INSTANCE_REMOVALS = ConcurrentHashMap.newKeySet();
    private static final Set<String> EXPLICIT_DEATH_WIPE_REMOVALS = ConcurrentHashMap.newKeySet();
    private static final boolean DEATH_WIPE_ON_EMPTY_ENABLED = false;
    private static final Map<String, InstanceDebugDefinition> TEMPLATE_NAME_INDEX;

    static {
        Map<String, InstanceDebugDefinition> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, InstanceDebugDefinition> entry : INSTANCE_DEFINITIONS.entrySet()) {
            normalized.put(normalize(entry.getKey()), entry.getValue());
        }
        TEMPLATE_NAME_INDEX = Map.copyOf(normalized);
    }

    private static JavaPlugin plugin;
    private static AddonFilesManager filesManager;

    private PortalInstanceDiagnostics() {
    }

    public static void initialize(@Nonnull JavaPlugin owner, @Nullable AddonFilesManager addonFilesManager) {
        plugin = owner;
        filesManager = addonFilesManager;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(PortalInstanceDiagnostics::sweepStaleTrackedInstances,
                1L,
                TimeUnit.SECONDS);
    }

    public static void onAddPlayerToWorld(@Nonnull AddPlayerToWorldEvent event) {
        PlayerRef playerRef = getPlayerRef(event.getHolder());
        if (playerRef == null) {
            return;
        }
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        World world = event.getWorld();
        String worldName = world.getName();
        InstanceDebugDefinition definition = resolveDefinitionByWorldName(worldName);
        PendingDeath pendingDeath = PENDING_DEATHS.remove(playerUuid);
        if (definition == null && pendingDeath == null) {
            return;
        }

        // If player died in a dungeon, teleport them back to their portal entry location (with offset).
        // Falls through to world spawn if the entry target is unavailable or the teleport fails.
        if (pendingDeath != null) {
            PortalLeveledInstanceRouter.teleportPlayerToDeathReturnEntry(
                    playerRef,
                    pendingDeath.returnTargetWorldName,
                    pendingDeath.returnTargetTransform,
                    world);
            log(Level.INFO,
                    "player-death-return player=%s returnWorld=%s source=%s",
                    playerRef.getUsername(),
                    pendingDeath.returnTargetWorldName != null ? pendingDeath.returnTargetWorldName : "none",
                    pendingDeath.returnTargetSource);
        }

        world.execute(() -> {
            Vector3d actualPosition = resolvePosition(playerRef, event.getHolder());
            if (pendingDeath != null) {
                boolean removedFromDungeon = !INSTANCE_DEFINITIONS.containsKey(worldName) || !pendingDeath.worldName.equals(worldName);
                log(Level.INFO,
                        "player-return-after-death player=%s deathWorld=%s currentWorld=%s removedFromDungeon=%s actual=%s hasReturnTarget=%s",
                        playerRef.getUsername(),
                        pendingDeath.worldName,
                        worldName,
                        removedFromDungeon,
                        format(actualPosition),
                        pendingDeath.returnTargetWorldName != null);
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
        UUID playerUuid = playerRef.getUuid();
        if (playerUuid == null) {
            return;
        }

        String worldName = event.getWorld().getName();
        PendingDeath pendingDeath = PENDING_DEATHS.get(playerUuid);
        if (!isTrackedInstanceWorld(worldName) && (pendingDeath == null || !pendingDeath.worldName.equals(worldName))) {
            return;
        }

        // Read InstanceEntityConfig before Hytale's onPlayerDrainFromWorld fires (which calls removeAndGet).
        // This reveals where ExitInstance / Hytale will redirect the player after they leave the instance.
        InstanceEntityConfig entityConfig = event.getHolder().getComponent(InstanceEntityConfig.getComponentType());
        WorldReturnPoint entityReturnPoint = entityConfig != null ? entityConfig.getReturnPoint() : null;
        WorldReturnPoint entityReturnOverride = entityConfig != null ? entityConfig.getReturnPointOverride() : null;
        String drainReturnWorldUuid = entityReturnPoint != null && entityReturnPoint.getWorld() != null
                ? entityReturnPoint.getWorld().toString() : "null";
        String drainReturnTransform = entityReturnPoint != null
                ? formatPos(entityReturnPoint.getReturnPoint()) : "null";
        String drainReturnOverrideUuid = entityReturnOverride != null && entityReturnOverride.getWorld() != null
                ? entityReturnOverride.getWorld().toString() : "null";

        log(Level.INFO,
                "player-drain player=%s sourceWorld=%s pendingDeath=%s drainTransform=%s" +
                " entityReturnWorldUuid=%s entityReturnTransform=%s entityReturnOverrideUuid=%s",
                playerRef.getUsername(),
                worldName,
                pendingDeath != null,
                format(event.getTransform() == null ? null : event.getTransform().getPosition()),
                drainReturnWorldUuid,
                drainReturnTransform,
                drainReturnOverrideUuid);

        if (DEATH_WIPE_ON_EMPTY_ENABLED && pendingDeath != null && pendingDeath.worldName.equals(worldName)) {
            attemptDeathWipeWhenWorldEmpties(worldName, playerRef.getUsername());
        }
    }

    public static void onWorldRemoved(@Nonnull RemoveWorldEvent event) {
        String worldName = event.getWorld().getName();
        if (!isTrackedInstanceWorld(worldName)) {
            return;
        }

        PENDING_INSTANCE_REMOVALS.remove(worldName);

        // Clean up the runtime level override registered at spawn time.
        EndlessLevelingAPI api = EndlessLevelingAPI.get();
        if (api != null) {
            api.removeMobWorldFixedLevelOverride(worldName);
        }

        boolean explicitDeathWipe = EXPLICIT_DEATH_WIPE_REMOVALS.remove(worldName);
        if (explicitDeathWipe) {
            PortalLeveledInstanceRouter.unpairInstanceWorldPreservingExpectation(worldName, "all-players-dead");
            PortalLeveledInstanceRouter.clearActiveInstanceRange(worldName);
        }

        // Keep level metadata if this world is still paired to an active gate.
        // Do not auto-reload here: RemoveWorldEvent fires while world teardown/delete is still
        // in progress, and immediate reload can race deletion on Windows (AccessDenied) and
        // create duplicate instance lifecycle events.
        if (!explicitDeathWipe && !PortalLeveledInstanceRouter.isInstancePairedToActiveGate(worldName)) {
            PortalLeveledInstanceRouter.clearActiveInstanceRange(worldName);
        } else if (!explicitDeathWipe) {
            log(Level.INFO,
                    "world-remove world=%s preserved paired gate mapping/levels; reload deferred until next gate entry",
                    worldName);
        }

        log(Level.INFO,
                "world-remove world=%s reason=%s cancelled=%s",
                worldName,
                event.getRemovalReason(),
                event.isCancelled());
    }

    public static int purgeTrackedInstancesOnShutdown() {
        Set<String> targets = new LinkedHashSet<>();
        Universe universe = Universe.get();
        if (universe != null) {
            for (String worldName : universe.getWorlds().keySet()) {
                if (isTrackedInstanceWorld(worldName)) {
                    targets.add(worldName);
                }
            }
        }

        // Include template identifiers so persisted EL instances are also purged on shutdown.
        targets.addAll(INSTANCE_DEFINITIONS.keySet());

        int purgedCount = 0;

        for (String worldName : targets) {
            if (worldName == null || worldName.isBlank()) {
                continue;
            }

            try {
                EndlessLevelingAPI api = EndlessLevelingAPI.get();
                if (api != null) {
                    api.removeMobWorldFixedLevelOverride(worldName);
                }
                PortalLeveledInstanceRouter.clearActiveInstanceRange(worldName);
                PENDING_INSTANCE_REMOVALS.remove(worldName);
                InstancesPlugin.safeRemoveInstance(worldName);
                purgedCount++;
                log(Level.INFO, "shutdown-instance-purge world=%s", worldName);
            } catch (Exception ex) {
                log(Level.WARNING,
                        "shutdown-instance-purge-failed world=%s error=%s",
                        worldName,
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            }
        }

        return purgedCount;
    }

    public static void onPlayerDeath(@Nonnull Player player, @Nullable Vector3d actualPosition) {
        World world = player.getWorld();
        if (world == null) {
            return;
        }

        String worldName = world.getName();
        InstanceDebugDefinition definition = resolveDefinitionByWorldName(worldName);
        if (definition == null) {
            return;
        }

        SpawnMatch nearestSpawn = definition.findNearest(actualPosition);
        
        // Store return target information so player can be returned to portal entrance on respawn
        PlayerRef playerRef = null;
        UUID playerUuid = player.getUuid();
        Universe universe = Universe.get();
        if (universe != null && playerUuid != null) {
            playerRef = universe.getPlayer(playerUuid);
        }
        PortalLeveledInstanceRouter.ReturnTargetDiagnostics returnDiag = null;
        if (playerRef != null) {
            returnDiag = PortalLeveledInstanceRouter.resolveReturnTargetDiagnostics(playerRef, world);
        }
        
        PendingDeath deathInfo = new PendingDeath(
            worldName,
            returnDiag != null ? returnDiag.worldName() : null,
            returnDiag != null ? returnDiag.returnTransform() : null,
            returnDiag != null ? returnDiag.source() : "unknown"
        );
        if (playerUuid != null) {
            PENDING_DEATHS.put(playerUuid, deathInfo);
        }
        
        log(Level.INFO,
                "player-death player=%s instance=%s label=%s actual=%s expected=%s distance=%.3f" +
                " returnTarget=%s returnWorld=%s returnTransform=%s awaitingDrain=true",
                player.getDisplayName(),
                worldName,
                definition.label,
                format(actualPosition),
                format(nearestSpawn.expected),
                nearestSpawn.distance,
                deathInfo.returnTargetSource,
                deathInfo.returnTargetWorldName != null ? deathInfo.returnTargetWorldName : "null",
                formatPos(deathInfo.returnTargetTransform));

        // Instance persists for its entire gate duration; not removed on death.
        // Player will respawn in the same instance and can return to entry point consistently.
    }

    private static boolean allowDungeonReentryAfterDeath() {
        return filesManager != null && filesManager.allowDungeonReentryAfterDeath();
    }

    private static boolean isTrackedInstanceWorld(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return resolveDefinitionByWorldName(worldName) != null;
    }

    @Nullable
    private static InstanceDebugDefinition resolveDefinitionByWorldName(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return null;
        }

        InstanceDebugDefinition exact = INSTANCE_DEFINITIONS.get(worldName);
        if (exact != null) {
            return exact;
        }

        String normalizedWorldName = normalize(worldName);
        if (normalizedWorldName.isEmpty()) {
            return null;
        }

        for (Map.Entry<String, InstanceDebugDefinition> entry : TEMPLATE_NAME_INDEX.entrySet()) {
            if (normalizedWorldName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    @Nonnull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static void sweepStaleTrackedInstances() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            for (String worldName : universe.getWorlds().keySet()) {
                if (!isTrackedInstanceWorld(worldName)) {
                    continue;
                }
                scheduleInstanceRemoval(worldName, "startup-stale-cleanup", false);
            }
        } catch (Exception ex) {
            log(Level.WARNING, "startup-stale-cleanup-failed error=%s", ex.getMessage());
        }
    }

    private static void attemptDeathWipeWhenWorldEmpties(@Nonnull String worldName,
                                                         @Nonnull String playerName) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            int occupants = countPlayersInWorld(universe, worldName);
            if (occupants > 0) {
                log(Level.INFO,
                        "instance-death-wipe-skip world=%s player=%s occupants=%d",
                        worldName,
                        playerName,
                        occupants);
                return;
            }

            scheduleInstanceRemoval(worldName, "all-players-dead", true);
        }, 100L, TimeUnit.MILLISECONDS);
    }

    private static int countPlayersInWorld(@Nonnull Universe universe, @Nonnull String worldName) {
        int count = 0;
        for (PlayerRef online : universe.getPlayers()) {
            UUID worldUuid = online.getWorldUuid();
            if (worldUuid == null) {
                continue;
            }

            World onlineWorld = universe.getWorld(worldUuid);
            if (onlineWorld != null && worldName.equals(onlineWorld.getName())) {
                count++;
            }
        }
        return count;
    }

    private static void scheduleInstanceRemoval(@Nullable String worldName,
                                                @Nonnull String reason,
                                                boolean allowPairedGateRemoval) {
        if (worldName == null || worldName.isBlank() || !isTrackedInstanceWorld(worldName)) {
            return;
        }
        if (!allowPairedGateRemoval && PortalLeveledInstanceRouter.isInstancePairedToActiveGate(worldName)) {
            log(Level.INFO,
                    "instance-remove-skip world=%s reason=%s pairedActiveGate=true",
                    worldName,
                    reason);
            return;
        }
        if (!PENDING_INSTANCE_REMOVALS.add(worldName)) {
            return;
        }

        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            try {
                if (!allowPairedGateRemoval && PortalLeveledInstanceRouter.isInstancePairedToActiveGate(worldName)) {
                    PENDING_INSTANCE_REMOVALS.remove(worldName);
                    log(Level.INFO,
                            "instance-remove-skip world=%s reason=%s pairedActiveGate=true",
                            worldName,
                            reason);
                    return;
                }

                if (allowPairedGateRemoval) {
                    EXPLICIT_DEATH_WIPE_REMOVALS.add(worldName);
                }

                InstancesPlugin.safeRemoveInstance(worldName);
                log(Level.INFO, "instance-remove-request world=%s reason=%s", worldName, reason);
            } catch (Exception ex) {
                PENDING_INSTANCE_REMOVALS.remove(worldName);
                if (allowPairedGateRemoval) {
                    EXPLICIT_DEATH_WIPE_REMOVALS.remove(worldName);
                }
                log(Level.WARNING,
                        "instance-remove-request-failed world=%s reason=%s error=%s",
                        worldName,
                        reason,
                        ex.getMessage());
            }
        }, 100L, TimeUnit.MILLISECONDS);
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

    @Nonnull
    private static String formatPos(@Nullable Transform transform) {
        if (transform == null || transform.getPosition() == null) {
            return "null";
        }
        return String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)",
                transform.getPosition().x, transform.getPosition().y, transform.getPosition().z);
    }

    private static void log(@Nonnull Level level, @Nonnull String message, Object... args) {
        String formatted = args == null || args.length == 0
                ? message
                : String.format(Locale.ROOT, message, args);
        AddonLoggingManager.log(plugin, level, "[ELPortal] " + formatted);
    }

    private record PendingDeath(
        @Nonnull String worldName,
        @Nullable String returnTargetWorldName,
        @Nullable com.hypixel.hytale.math.vector.Transform returnTargetTransform,
        @Nonnull String returnTargetSource
    ) {
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