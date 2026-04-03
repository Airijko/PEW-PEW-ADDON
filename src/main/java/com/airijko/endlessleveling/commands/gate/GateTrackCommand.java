package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.managers.GateTrackerManager;
import com.airijko.endlessleveling.managers.GateTrackerManager.GateTrackerEntry;
import com.airijko.endlessleveling.ui.GateTrackerHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class GateTrackCommand extends AbstractCommand {

    private final RequiredArg<String> idArg = this.withRequiredArg(
            "id",
            "Gate list index from /gate list (for example 1 or #1), or clear/off/stop to remove the tracker HUD",
            Objects.requireNonNull(ArgTypes.STRING));

    public GateTrackCommand() {
        super("track", "Track one active gate entry in a live HUD");
        this.addAliases("tracks", "tracking", "followgate", "trackgate");
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        String rawArg = idArg.get(context);
        if (rawArg == null || rawArg.isBlank()) {
            context.sendMessage(Message.raw("Usage: /gate track <index> (example: /gate track 1)").color("#ffcc66"));
            GateListCommand.sendGateList(context, true);
            return CompletableFuture.completedFuture(null);
        }

        if (isClearRequest(rawArg)) {
            return runWithPlayerRef(context, (ctx, player, playerRef) -> {
                GateTrackerManager.clearTrackedEntry(playerRef.getUuid());
                GateTrackerHud.close(player, playerRef);
                ctx.sendMessage(Message.raw("Gate tracker HUD cleared.").color("#6cff78"));
            });
        }

        GateTrackerEntry entry = GateTrackerManager.findByIndexOrDisplayId(rawArg);
        if (entry == null) {
            context.sendMessage(Message.raw("Unknown gate list index or id '" + rawArg + "'.").color("#ff6666"));
            GateListCommand.sendGateList(context, true);
            return CompletableFuture.completedFuture(null);
        }

        return runWithPlayerRef(context, (ctx, player, playerRef) -> {
            GateTrackerManager.setTrackedEntry(playerRef.getUuid(), entry);
            GateTrackerHud.OpenStatus status = GateTrackerHud.open(player, playerRef);
            switch (status) {
                case OPENED -> {
                    ctx.sendMessage(Message.raw(
                            "Tracking " + entry.type().label() + " " + entry.title() + ".")
                            .color("#6cff78"));
                    ctx.sendMessage(Message.raw("Use /gate track clear to close the tracker HUD.").color("#8fd3ff"));
                }
                case BLOCKED_BY_EXISTING_HUD -> {
                    GateTrackerManager.clearTrackedEntry(playerRef.getUuid());
                    ctx.sendMessage(Message.raw(
                            "Could not open the tracker HUD because another custom HUD already owns the slot.")
                            .color("#ff6666"));
                    ctx.sendMessage(Message.raw(
                            "Install or enable MultipleHUD to stack the gate tracker beside the core HUD, or clear the current custom HUD first.")
                            .color("#ffcc66"));
                }
                case PLAYER_INVALID -> {
                    GateTrackerManager.clearTrackedEntry(playerRef.getUuid());
                    ctx.sendMessage(Message.raw("Could not resolve live player state. Try again in a moment.").color("#ff6666"));
                }
            }
        });
    }

    private static boolean isClearRequest(@Nonnull String rawArg) {
        String normalized = rawArg.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("clear")
                || normalized.equals("off")
                || normalized.equals("stop")
                || normalized.equals("none");
    }

    @Nonnull
    private static CompletableFuture<Void> runWithPlayerRef(
            @Nonnull CommandContext context,
            @Nonnull PlayerAction action) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        World world = player.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    context.sendMessage(Message.raw("Could not resolve player state. Try again in a moment.").color("#ff6666"));
                    return;
                }

                Store<EntityStore> store = ref.getStore();
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null || !playerRef.isValid()) {
                    context.sendMessage(Message.raw("Could not resolve player state. Try again in a moment.").color("#ff6666"));
                    return;
                }

                action.run(context, player, playerRef);
            } finally {
                future.complete(null);
            }
        });
        return future;
    }

    @FunctionalInterface
    private interface PlayerAction {
        void run(@Nonnull CommandContext context, @Nonnull Player player, @Nonnull PlayerRef playerRef);
    }
}