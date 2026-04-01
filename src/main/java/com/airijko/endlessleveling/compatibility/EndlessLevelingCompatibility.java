package com.airijko.endlessleveling.compatibility;

import com.airijko.endlessleveling.api.gates.DungeonWaveGateBridge;
import com.airijko.endlessleveling.api.gates.DungeonGateLifecycleBridge;
import com.airijko.endlessleveling.api.gates.GateInstanceRoutingBridge;
import com.airijko.endlessleveling.api.gates.InstanceDungeonDefinition;
import com.airijko.endlessleveling.api.gates.DungeonGateContentProvider;
import com.airijko.endlessleveling.api.gates.WaveGateRuntimeBridge;
import com.airijko.endlessleveling.api.gates.WaveGateSessionBridge;
import com.airijko.endlessleveling.api.gates.WaveGateSessionExecutorBridge;
import com.airijko.endlessleveling.api.gates.WaveSessionResults;
import com.airijko.endlessleveling.api.gates.WaveGateContentProvider;
import com.airijko.endlessleveling.managers.AddonGatesManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import com.airijko.endlessleveling.api.gates.TrackedDungeonGateSnapshot;
import com.airijko.endlessleveling.api.gates.TrackedWaveGateSnapshot;

/**
 * Optional bridge for Endless Leveling API.
 */
public final class EndlessLevelingCompatibility {

	private static final String API_CLASS = "com.airijko.endlessleveling.api.EndlessLevelingAPI";
    private static final String DUNGEON_GATES_MANAGER_KEY = "dungeon-gates";
    private static final String DUNGEON_GATES_LIFECYCLE_MANAGER_KEY = "dungeon-gates.lifecycle";
    private static final String WAVE_GATES_RUNTIME_MANAGER_KEY = "wave-gates.runtime";
    private static final String WAVE_GATES_SESSION_MANAGER_KEY = "wave-gates.session";
    private static final String WAVE_GATES_SESSION_EXECUTOR_MANAGER_KEY = "wave-gates.session.executor";
    private static final String GATE_INSTANCE_ROUTING_MANAGER_KEY = "gates.instance-routing";
    private static final String INSTANCE_DUNGEON_DEFINITION_MANAGER_KEY = "dungeons.instance";
    private static final String LEGACY_GATES_MANAGER_KEY = "gates";

	private EndlessLevelingCompatibility() {
	}

    public static boolean isAvailable() {
        return getApiInstance() != null;
    }

    public static Object getApiInstance() {
        try {
            return Class.forName(API_CLASS).getMethod("get").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean registerGatesManager(DungeonWaveGateBridge manager) {
        if (manager == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerDungeonWaveGateBridge", DungeonWaveGateBridge.class, boolean.class)
                    .invoke(api, manager, true);
        } catch (Throwable ignored) {
            // Fallback for older API variants that only expose generic manager registration.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, DUNGEON_GATES_MANAGER_KEY, manager, true);
        } catch (Throwable ignored) {
            // Legacy fallback.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, LEGACY_GATES_MANAGER_KEY, manager, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterGatesManager(DungeonWaveGateBridge manager) {
        if (manager == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterDungeonWaveGateBridge", DungeonWaveGateBridge.class)
                    .invoke(api, manager);
        } catch (Throwable ignored) {
            // Fallback for older API variants that only expose generic manager registration.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, DUNGEON_GATES_MANAGER_KEY, manager);
        } catch (Throwable ignored) {
            // Legacy fallback.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, LEGACY_GATES_MANAGER_KEY, manager);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerWaveGateRuntimeBridge(WaveGateRuntimeBridge bridge) {
        if (bridge == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerWaveGateRuntimeBridge", WaveGateRuntimeBridge.class, boolean.class)
                    .invoke(api, bridge, true);
        } catch (Throwable ignored) {
            // Fallback for API variants without typed runtime bridge registration.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, WAVE_GATES_RUNTIME_MANAGER_KEY, bridge, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterWaveGateRuntimeBridge(WaveGateRuntimeBridge bridge) {
        if (bridge == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterWaveGateRuntimeBridge", WaveGateRuntimeBridge.class)
                    .invoke(api, bridge);
        } catch (Throwable ignored) {
            // Fallback for API variants without typed runtime bridge registration.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, WAVE_GATES_RUNTIME_MANAGER_KEY, bridge);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerDungeonGateLifecycleBridge(DungeonGateLifecycleBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerDungeonGateLifecycleBridge", DungeonGateLifecycleBridge.class, boolean.class)
                    .invoke(api, bridge, true);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, DUNGEON_GATES_LIFECYCLE_MANAGER_KEY, bridge, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterDungeonGateLifecycleBridge(DungeonGateLifecycleBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterDungeonGateLifecycleBridge", DungeonGateLifecycleBridge.class)
                    .invoke(api, bridge);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, DUNGEON_GATES_LIFECYCLE_MANAGER_KEY, bridge);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerWaveGateSessionBridge(WaveGateSessionBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerWaveGateSessionBridge", WaveGateSessionBridge.class, boolean.class)
                    .invoke(api, bridge, true);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, WAVE_GATES_SESSION_MANAGER_KEY, bridge, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterWaveGateSessionBridge(WaveGateSessionBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterWaveGateSessionBridge", WaveGateSessionBridge.class)
                    .invoke(api, bridge);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, WAVE_GATES_SESSION_MANAGER_KEY, bridge);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerWaveGateSessionExecutorBridge(WaveGateSessionExecutorBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerWaveGateSessionExecutorBridge", WaveGateSessionExecutorBridge.class, boolean.class)
                    .invoke(api, bridge, true);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, WAVE_GATES_SESSION_EXECUTOR_MANAGER_KEY, bridge, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterWaveGateSessionExecutorBridge(WaveGateSessionExecutorBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterWaveGateSessionExecutorBridge", WaveGateSessionExecutorBridge.class)
                    .invoke(api, bridge);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, WAVE_GATES_SESSION_EXECUTOR_MANAGER_KEY, bridge);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerGateInstanceRoutingBridge(GateInstanceRoutingBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerGateInstanceRoutingBridge", GateInstanceRoutingBridge.class, boolean.class)
                    .invoke(api, bridge, true);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, GATE_INSTANCE_ROUTING_MANAGER_KEY, bridge, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterGateInstanceRoutingBridge(GateInstanceRoutingBridge bridge) {
        if (bridge == null) {
            return false;
        }
        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterGateInstanceRoutingBridge", GateInstanceRoutingBridge.class)
                    .invoke(api, bridge);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, GATE_INSTANCE_ROUTING_MANAGER_KEY, bridge);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerInstanceDungeon(InstanceDungeonDefinition definition) {
        if (definition == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerInstanceDungeon", InstanceDungeonDefinition.class, boolean.class)
                    .invoke(api, definition, true);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, INSTANCE_DUNGEON_DEFINITION_MANAGER_KEY + ":" + definition.dungeonId(),
                            definition,
                            true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterInstanceDungeon(InstanceDungeonDefinition definition) {
        if (definition == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterInstanceDungeon", InstanceDungeonDefinition.class)
                    .invoke(api, definition);
        } catch (Throwable ignored) {
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, INSTANCE_DUNGEON_DEFINITION_MANAGER_KEY + ":" + definition.dungeonId(), definition);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nonnull
    public static CompletableFuture<Boolean> spawnRandomDungeonGate(@Nonnull Player player, boolean isTestSpawn) {
        DungeonGateLifecycleBridge bridge = resolveDungeonGateLifecycleBridge();
        if (bridge != null) {
            return bridge.spawnRandomGateNearPlayer(player, isTestSpawn);
        }
        return AddonGatesManager.INSTANCE.spawnRandomGateNearPlayer(player, isTestSpawn);
    }

    @Nonnull
    public static CompletableFuture<Boolean> spawnDungeonGateWithRank(@Nonnull Player player,
            @Nonnull String rankTierId,
            boolean isTestSpawn) {
        DungeonGateLifecycleBridge bridge = resolveDungeonGateLifecycleBridge();
        if (bridge != null) {
            return bridge.spawnGateNearPlayerWithRank(player, rankTierId, isTestSpawn);
        }
        return AddonGatesManager.INSTANCE.spawnGateNearPlayerWithRank(player, rankTierId, isTestSpawn);
    }

    @Nonnull
    public static CompletableFuture<Boolean> spawnDungeonGateWithForcedLinkedWave(@Nonnull Player player,
            @Nonnull String rankTierId,
            boolean isTestSpawn) {
        DungeonGateLifecycleBridge bridge = resolveDungeonGateLifecycleBridge();
        if (bridge != null) {
            return bridge.spawnGateNearPlayerWithRankAndForcedLinkedWave(player, rankTierId, isTestSpawn);
        }
        return AddonGatesManager.INSTANCE.spawnGateNearPlayerWithRankAndForcedLinkedWave(player, rankTierId,
                isTestSpawn);
    }

    @Nullable
    public static String rollDungeonGateRankTierForPlayer(@Nonnull PlayerRef playerRef) {
        DungeonGateLifecycleBridge bridge = resolveDungeonGateLifecycleBridge();
        if (bridge != null) {
            return bridge.rollGateRankTierForPlayer(playerRef);
        }
        return AddonGatesManager.INSTANCE.rollGateRankTierForPlayer(playerRef);
    }

    @Nonnull
    public static WaveSessionResults.NaturalStartResult startNaturalWaveForPlayer(@Nonnull Player player,
            @Nullable String preferredRankTierId) {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            boolean started = bridge.startNaturalWaveForPlayer(player, preferredRankTierId).join();
            return new WaveSessionResults.NaturalStartResult(
                    started,
                    started ? "Wave start requested." : "Unable to schedule wave right now.",
                    preferredRankTierId,
                    0);
        }

        boolean started = AddonGatesManager.INSTANCE.startNaturalWaveForPlayer(player, preferredRankTierId).join();
        return new WaveSessionResults.NaturalStartResult(
                started,
                started ? "Wave start requested." : "Unable to schedule wave right now.",
                preferredRankTierId,
                0);
    }

    @Nonnull
    public static WaveSessionResults.StartResult startWaveForPlayer(@Nonnull PlayerRef playerRef,
            @Nonnull String rankTierId) {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.startWaveForPlayer(playerRef, rankTierId);
        }
        return AddonGatesManager.INSTANCE.startWaveForPlayer(playerRef, rankTierId);
    }

    @Nonnull
    public static WaveSessionResults.StopResult stopWaveForPlayer(@Nonnull PlayerRef playerRef) {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.stopWaveForPlayer(playerRef);
        }
        return AddonGatesManager.INSTANCE.stopWaveForPlayer(playerRef);
    }

    @Nullable
    public static WaveSessionResults.StatusResult getWaveStatus(@Nonnull PlayerRef playerRef) {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.getStatus(playerRef);
        }
        return AddonGatesManager.INSTANCE.getStatus(playerRef);
    }

    @Nonnull
    public static WaveSessionResults.SkipResult skipWaveForPlayer(@Nonnull PlayerRef playerRef) {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.skipWaveForPlayer(playerRef);
        }
        return AddonGatesManager.INSTANCE.skipWaveForPlayer(playerRef);
    }

    public static int clearLinkedGateCombosForPlayer(@Nonnull PlayerRef playerRef) {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.clearLinkedGateCombosForPlayer(playerRef);
        }
        return AddonGatesManager.INSTANCE.clearLinkedGateCombosForPlayer(playerRef);
    }

    public static int clearWavePortalVisualsInPlayerWorld(@Nonnull PlayerRef playerRef) {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.clearWavePortalVisualsInPlayerWorld(playerRef);
        }
        return AddonGatesManager.INSTANCE.clearWavePortalVisualsInPlayerWorld(playerRef);
    }

    @Nonnull
    public static List<TrackedDungeonGateSnapshot> listTrackedDungeonGates() {
        DungeonGateLifecycleBridge bridge = resolveDungeonGateLifecycleBridge();
        if (bridge != null) {
            return bridge.listTrackedGates();
        }
        return AddonGatesManager.INSTANCE.listTrackedGates();
    }

    @Nonnull
    public static List<TrackedWaveGateSnapshot> listTrackedStandaloneWaves() {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.listTrackedStandaloneWaves();
        }
        return AddonGatesManager.INSTANCE.listTrackedStandaloneWaves();
    }

    @Nonnull
    public static List<TrackedWaveGateSnapshot> listTrackedGateWaveCombos() {
        WaveGateSessionBridge bridge = resolveWaveGateSessionBridge();
        if (bridge != null) {
            return bridge.listTrackedGateWaveCombos();
        }
        return AddonGatesManager.INSTANCE.listTrackedGateWaveCombos();
    }

    public static void cleanupGateInstanceByIdentity(@Nonnull String gateIdentity, @Nonnull String blockId) {
        GateInstanceRoutingBridge bridge = resolveGateInstanceRoutingBridge();
        if (bridge != null) {
            bridge.cleanupGateInstanceByIdentity(gateIdentity, blockId);
            return;
        }
    }

    @Nullable
    private static GateInstanceRoutingBridge resolveGateInstanceRoutingBridge() {
        Object api = getApiInstance();
        if (api == null) {
            return null;
        }

        try {
            Object bridge = api.getClass().getMethod("getGateInstanceRoutingBridge").invoke(api);
            if (bridge instanceof GateInstanceRoutingBridge routingBridge) {
                return routingBridge;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static DungeonGateLifecycleBridge resolveDungeonGateLifecycleBridge() {
        Object api = getApiInstance();
        if (api == null) {
            return null;
        }

        try {
            Object bridge = api.getClass().getMethod("getDungeonGateLifecycleBridge").invoke(api);
            if (bridge instanceof DungeonGateLifecycleBridge lifecycleBridge) {
                return lifecycleBridge;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Nullable
    private static WaveGateSessionBridge resolveWaveGateSessionBridge() {
        Object api = getApiInstance();
        if (api == null) {
            return null;
        }

        try {
            Object bridge = api.getClass().getMethod("getWaveGateSessionBridge").invoke(api);
            if (bridge instanceof WaveGateSessionBridge sessionBridge) {
                return sessionBridge;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static boolean registerDungeonGateContentProvider(DungeonGateContentProvider provider) {
        if (provider == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerDungeonGateContentProvider", DungeonGateContentProvider.class, boolean.class)
                    .invoke(api, provider, true);
        } catch (Throwable ignored) {
            // Phase 1 compatibility fallback.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerGateContentProvider", DungeonGateContentProvider.class, boolean.class)
                    .invoke(api, provider, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterDungeonGateContentProvider(DungeonGateContentProvider provider) {
        if (provider == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterDungeonGateContentProvider", DungeonGateContentProvider.class)
                    .invoke(api, provider);
        } catch (Throwable ignored) {
            // Phase 1 compatibility fallback.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterGateContentProvider", DungeonGateContentProvider.class)
                    .invoke(api, provider);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean registerWaveGateContentProvider(WaveGateContentProvider provider) {
        if (provider == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerWaveGateContentProvider", WaveGateContentProvider.class, boolean.class)
                    .invoke(api, provider, true);
        } catch (Throwable ignored) {
            // Phase 1 compatibility fallback.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerWaveContentProvider", WaveGateContentProvider.class, boolean.class)
                    .invoke(api, provider, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterWaveGateContentProvider(WaveGateContentProvider provider) {
        if (provider == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterWaveGateContentProvider", WaveGateContentProvider.class)
                    .invoke(api, provider);
        } catch (Throwable ignored) {
            // Phase 1 compatibility fallback.
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterWaveContentProvider", WaveGateContentProvider.class)
                    .invoke(api, provider);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
