package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.gates.GateInstanceRoutingBridge;
import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class AddonGateInstanceRoutingManager implements GateInstanceRoutingBridge {

    public static final AddonGateInstanceRoutingManager INSTANCE = new AddonGateInstanceRoutingManager();

    private AddonGateInstanceRoutingManager() {
    }

    @Override
    public void saveGateInstances() {
        PortalLeveledInstanceRouter.saveGateInstances();
    }

    @Override
    public void restoreSavedGateInstances() {
        PortalLeveledInstanceRouter.restoreSavedGateInstances();
    }

    @Override
    public void cleanupGateInstance(@Nonnull World world, int x, int y, int z, @Nonnull String blockId) {
        PortalLeveledInstanceRouter.cleanupGateInstance(world, x, y, z, blockId);
    }

    @Override
    public void cleanupGateInstanceByIdentity(@Nonnull String gateIdentity, @Nonnull String blockId) {
        PortalLeveledInstanceRouter.cleanupGateInstanceByIdentity(gateIdentity, blockId);
    }

    @Override
    public String resolveInstanceNameForGate(@Nonnull String gateIdentity) {
        return PortalLeveledInstanceRouter.resolveInstanceNameForGate(gateIdentity);
    }

    @Override
    public void kickPlayersFromGateInstance(@Nonnull String instanceWorldName) {
        PortalLeveledInstanceRouter.kickPlayersFromGateInstance(instanceWorldName);
    }

    @Override
    public boolean enterPortalFromBlock(@Nonnull PlayerRef playerRef,
            @Nonnull World sourceWorld,
            int x,
            int y,
            int z,
            @Nonnull String blockId,
            boolean removeSourcePortalOnSuccess,
            @Nullable String stableGateIdOverride) {
        if (stableGateIdOverride != null && !stableGateIdOverride.isBlank()) {
            return PortalLeveledInstanceRouter.enterPortalFromBlock(
                playerRef,
                sourceWorld,
                blockId,
                x,
                y,
                z,
                stableGateIdOverride);
        }
        return PortalLeveledInstanceRouter.enterPortalFromBlock(playerRef, sourceWorld, blockId, x, y, z);
    }

    @Override
    public boolean returnPlayerToEntryPortal(@Nonnull PlayerRef playerRef, @Nonnull World sourceWorld) {
        return PortalLeveledInstanceRouter.returnPlayerToEntryPortal(playerRef, sourceWorld);
    }

    @Override
    public int[] getActiveInstanceRange(@Nonnull String worldName) {
        return PortalLeveledInstanceRouter.getActiveInstanceRange(worldName);
    }

    @Override
    public Integer getActiveInstanceBossLevel(@Nonnull String worldName) {
        return PortalLeveledInstanceRouter.getActiveInstanceBossLevel(worldName);
    }

    @Override
    public String getActiveInstanceRankLetter(@Nonnull String worldName) {
        return PortalLeveledInstanceRouter.getActiveInstanceRankLetter(worldName);
    }

    @Override
    public boolean isInstancePairedToActiveGate(@Nonnull String worldName) {
        return PortalLeveledInstanceRouter.isInstancePairedToActiveGate(worldName);
    }
}
