package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class PortalGiveCommand extends AbstractCommand {

    private static final String PORTAL_D01   = "EL_MajorDungeonPortal_D01";
    private static final String PORTAL_D02   = "EL_MajorDungeonPortal_D02";
    private static final String PORTAL_D03   = "EL_MajorDungeonPortal_D03";
    private static final String PORTAL_SWAMP  = "EL_EndgamePortal_Swamp_Dungeon";
    private static final String PORTAL_FROZEN = "EL_EndgamePortal_Frozen_Dungeon";
    private static final String PORTAL_VOID   = "EL_EndgamePortal_Golem_Void";

    public PortalGiveCommand() {
        super("give", "Give dungeon portal items by type and rank");
        this.addAliases("portal");

        this.addSubCommand(new GivePortalSubCommand("d1",    PORTAL_D01,    "Give Major Dungeon I portal"));
        this.addSubCommand(new GivePortalSubCommand("d2",    PORTAL_D02,    "Give Major Dungeon II portal"));
        this.addSubCommand(new GivePortalSubCommand("d3",    PORTAL_D03,    "Give Major Dungeon III portal"));
        this.addSubCommand(new GivePortalSubCommand("swamp",  PORTAL_SWAMP,  "Give Endgame Swamp Dungeon portal"));
        this.addSubCommand(new GivePortalSubCommand("frozen", PORTAL_FROZEN, "Give Endgame Frozen Dungeon portal"));
        this.addSubCommand(new GivePortalSubCommand("void",   PORTAL_VOID,   "Give Endgame Void Golem portal"));
        this.addSubCommand(new GiveAllPortalsSubCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw(
                "Usage: /gate give <d1|d2|d3|swamp|frozen|void|all> <rank: E|D|C|B|A|S>")
                .color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }

    @Nullable
    private static GateRankTier parseRank(@Nonnull String input) {
        try {
            return GateRankTier.valueOf(input.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static CompletableFuture<Void> givePortal(@Nonnull CommandContext context,
                                                       @Nonnull String baseBlockId,
                                                       @Nonnull GateRankTier tier) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        World world = player.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("You are not in a world right now.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        String blockId = baseBlockId + tier.blockIdSuffix();
        CompletableFuture<Void> future = new CompletableFuture<>();

        world.execute(() -> {
            try {
                CombinedItemContainer container = player.getInventory() != null
                        ? player.getInventory().getCombinedHotbarFirst()
                        : null;
                if (container == null) {
                    context.sendMessage(Message.raw("Could not access your inventory right now.").color("#ff6666"));
                    return;
                }

                ItemStack stack = new ItemStack(blockId, 1);
                ItemStackTransaction transaction = container.addItemStack(stack);
                ItemStack remainder = transaction.getRemainder();

                if (remainder != null && !ItemStack.isEmpty(remainder)) {
                    context.sendMessage(Message.raw("Inventory full — could not add: " + blockId).color("#ff6666"));
                } else {
                    context.sendMessage(Message.raw("Gave " + blockId).color("#6cff78"));
                }
            } catch (Exception ex) {
                context.sendMessage(Message.raw("Failed to give portal right now.").color("#ff6666"));
            } finally {
                future.complete(null);
            }
        });

        return future;
    }

    // -------------------------------------------------------------------------

    private static final class GivePortalSubCommand extends AbstractCommand {

        private final String baseBlockId;
        private final RequiredArg<String> rankArg =
            this.withRequiredArg("rank", "Rank tier (E/D/C/B/A/S)", ArgTypes.STRING);

        private GivePortalSubCommand(@Nonnull String name,
                                     @Nonnull String baseBlockId,
                                     @Nonnull String description) {
            super(name, description);
            this.baseBlockId = baseBlockId;
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            GateRankTier parsed = parseRank(rankArg.get(context));
            if (parsed == null) {
                context.sendMessage(Message.raw(
                        "Unknown rank '" + rankArg.get(context)
                                + "'. Valid ranks: E D C B A S")
                        .color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            return givePortal(context, baseBlockId, parsed);
        }
    }

    // -------------------------------------------------------------------------

    private static final class GiveAllPortalsSubCommand extends AbstractCommand {

        private final RequiredArg<String> rankArg =
            this.withRequiredArg("rank", "Rank tier (E/D/C/B/A/S)", ArgTypes.STRING);

        private GiveAllPortalsSubCommand() {
            super("all", "Give all dungeon portal items");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            GateRankTier parsed = parseRank(rankArg.get(context));
            if (parsed == null) {
            context.sendMessage(Message.raw(
                "Unknown rank '" + rankArg.get(context)
                    + "'. Valid ranks: E D C B A S")
                .color("#ff6666"));
            return CompletableFuture.completedFuture(null);
            }

            final GateRankTier finalTier = parsed;
            return givePortal(context, PORTAL_D01, finalTier)
                    .thenCompose(v -> givePortal(context, PORTAL_D02, finalTier))
                    .thenCompose(v -> givePortal(context, PORTAL_D03, finalTier))
                    .thenCompose(v -> givePortal(context, PORTAL_SWAMP, finalTier))
                    .thenCompose(v -> givePortal(context, PORTAL_FROZEN, finalTier))
                    .thenCompose(v -> givePortal(context, PORTAL_VOID, finalTier));
        }
    }
}
