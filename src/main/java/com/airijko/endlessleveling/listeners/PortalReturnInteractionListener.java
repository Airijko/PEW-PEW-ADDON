package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

public final class PortalReturnInteractionListener {

    private static final List<String> RETURN_PORTAL_BLOCK_IDS = List.of(
            "Portal_Return",
            "Return_Portal"
    );

    public void onPlayerInteract(@Nonnull PlayerInteractEvent event) {
        if (event.isCancelled() || !"Use".equals(String.valueOf(event.getActionType()))) {
            return;
        }

        Vector3i targetBlock = event.getTargetBlock();
        Ref<EntityStore> playerEntityRef = event.getPlayerRef();
        if (targetBlock == null || playerEntityRef == null || !playerEntityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = playerEntityRef.getStore();
        if (store == null || store.getExternalData() == null) {
            return;
        }

        World world = store.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        if (!PortalLeveledInstanceRouter.shouldHandleCustomReturnPortal(world)) {
            return;
        }

        String blockId = resolveBlockId(world, targetBlock);
        if (!isReturnPortalBlockId(blockId)) {
            return;
        }

        PlayerRef playerRef = store.getComponent(playerEntityRef, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }

        if (PortalLeveledInstanceRouter.shouldSuppressImmediateReturnPortal(playerRef, world)) {
            return;
        }

        boolean handled = PortalLeveledInstanceRouter.returnPlayerToEntryPortal(playerRef, world);
        if (!handled) {
            return;
        }

        event.setCancelled(true);
    }

    @Nonnull
    private static String resolveBlockId(@Nonnull World world, @Nonnull Vector3i position) {
        BlockType blockType = world.getBlockType(position.x, position.y, position.z);
        if (blockType == null) {
            return "";
        }
        String blockId = blockType.getId();
        return blockId == null ? "" : blockId;
    }

    private static boolean isReturnPortalBlockId(@Nonnull String blockId) {
        for (String exactId : RETURN_PORTAL_BLOCK_IDS) {
            if (exactId.equals(blockId)) {
                return true;
            }
        }

        String normalized = blockId.toLowerCase(Locale.ROOT);
        return normalized.contains("portal_return") || normalized.contains("return_portal");
    }
}