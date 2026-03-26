package com.airijko.endlessleveling.commands;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class MajorDungeonsPortalCommand extends AbstractCommand {

    private static final String PORTAL_BLOCK_D01 = "EL_MajorDungeonPortal_D01";
    private static final String PORTAL_BLOCK_D02 = "EL_MajorDungeonPortal_D02";
    private static final String PORTAL_BLOCK_D03 = "EL_MajorDungeonPortal_D03";

    public MajorDungeonsPortalCommand() {
        super("mdportal", "Give Major Dungeons portal items (requires MajorDungeons)");
        this.addAliases("majorportal", "dungeonportal");

        this.addSubCommand(new SpawnSinglePortalSubCommand("d1", PORTAL_BLOCK_D01, "Spawn Major Dungeon I portal"));
        this.addSubCommand(new SpawnSinglePortalSubCommand("d2", PORTAL_BLOCK_D02, "Spawn Major Dungeon II portal"));
        this.addSubCommand(new SpawnSinglePortalSubCommand("d3", PORTAL_BLOCK_D03, "Spawn Major Dungeon III portal"));
        this.addSubCommand(new GiveAllPortalsSubCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!(context.sender() instanceof Player)) {
            context.sendMessage(Message.raw("This command is player-only. Use /mdportal d1|d2|d3|all").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        context.sendMessage(Message.raw("Usage: /mdportal d1|d2|d3|all").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<Void> givePortal(@Nonnull CommandContext context,
                                                      @Nonnull String blockId) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        CombinedItemContainer container = player.getInventory() != null
                ? player.getInventory().getCombinedHotbarFirst()
                : null;
        if (container == null) {
            context.sendMessage(Message.raw("Could not access your inventory right now.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        try {
            ItemStack stack = new ItemStack(blockId, 1);
            ItemStackTransaction transaction = container.addItemStack(stack);
            ItemStack remainder = transaction.getRemainder();

            if (remainder != null && !ItemStack.isEmpty(remainder)) {
                context.sendMessage(Message.raw("Inventory full. Could not add portal block: " + blockId)
                        .color("#ff6666"));
            } else {
                context.sendMessage(Message.raw("Gave portal block: " + blockId)
                        .color("#6cff78"));
            }
        } catch (Exception ex) {
            context.sendMessage(Message.raw("Failed to give portal right now.").color("#ff6666"));
        }

        return CompletableFuture.completedFuture(null);
    }

    private static final class SpawnSinglePortalSubCommand extends AbstractCommand {
        private final String blockId;

        private SpawnSinglePortalSubCommand(@Nonnull String name, @Nonnull String blockId, @Nonnull String description) {
            super(name, description);
            this.blockId = blockId;
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return givePortal(context, this.blockId);
        }
    }

    private static final class GiveAllPortalsSubCommand extends AbstractCommand {

        private GiveAllPortalsSubCommand() {
            super("all", "Give all Major Dungeons portal items");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return givePortal(context, PORTAL_BLOCK_D01)
                    .thenCompose(ignored -> givePortal(context, PORTAL_BLOCK_D02))
                    .thenCompose(ignored -> givePortal(context, PORTAL_BLOCK_D03));
        }
    }
}
