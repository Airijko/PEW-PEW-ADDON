package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.managers.MobWaveManager;
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

public final class PortalWaveCommand extends AbstractCommand {

    public PortalWaveCommand() {
        super("wave", "Run barebones mob waves around your player");
        this.addAliases("waves");
        this.addSubCommand(new StartWaveSubCommand());
        this.addSubCommand(new StopWaveSubCommand());
        this.addSubCommand(new SkipWaveSubCommand());
        this.addSubCommand(new StatusWaveSubCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /gate wave <start <rank>|stop|skip|status> (rank: S A B C D E)").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }

    @Nullable
    private static GateRankTier parseRankTier(@Nonnull String input) {
        try {
            return GateRankTier.valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nonnull
    private static CompletableFuture<Void> runWithPlayerRefOnWorldThread(
            @Nonnull CommandContext context,
            @Nonnull WaveAction action
    ) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
            CompletableFuture<Void> completed = new CompletableFuture<>();
            completed.complete(null);
            return completed;
        }

        World world = player.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
            CompletableFuture<Void> completed = new CompletableFuture<>();
            completed.complete(null);
            return completed;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
                    return;
                }

                Store<EntityStore> store = ref.getStore();
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) {
                    context.sendMessage(Message.raw("Could not resolve player state. Try again in a moment.").color("#ff6666"));
                    return;
                }

                action.run(context, playerRef);
            } catch (Exception ex) {
                context.sendMessage(Message.raw("Wave command failed: " + ex.getMessage()).color("#ff6666"));
            } finally {
                future.complete(null);
            }
        });

        return future;
    }

    @FunctionalInterface
    private interface WaveAction {
        void run(@Nonnull CommandContext context, @Nonnull PlayerRef playerRef);
    }

    private static final class StartWaveSubCommand extends AbstractCommand {
        private final RequiredArg<String> rankArg =
                this.withRequiredArg("rank", "Wave rank tier (S/A/B/C/D/E)", Objects.requireNonNull(ArgTypes.STRING));

        private StartWaveSubCommand() {
            super("start", "Start a wave sequence");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                GateRankTier tier = parseRankTier(Objects.requireNonNull(rankArg.get(ctx)));
                if (tier == null) {
                    ctx.sendMessage(Message.raw("Invalid rank. Use one of: S A B C D E").color("#ff6666"));
                    return;
                }

                MobWaveManager.StartResult result = MobWaveManager.startForPlayer(playerRef, tier);
                if (!result.started) {
                    ctx.sendMessage(Message.raw(result.message).color("#ff6666"));
                    return;
                }
                String rankLetter = result.rankTier == null ? tier.letter() : result.rankTier.letter();

                ctx.sendMessage(Message.raw("Started wave sequence.").color("#6cff78"));
                ctx.sendMessage(Message.raw(
                                "Rank=" + rankLetter
                                        + " role=" + result.roleName
                                        + " waves=" + result.waves
                                        + " mobsPerWave=" + result.mobsPerWave
                                        + " interval=" + result.intervalSeconds + "s"
                                        + " levelRange=" + result.levelMin + "-" + result.levelMax)
                        .color("#8fd3ff"));
            });
        }
    }

    private static final class StopWaveSubCommand extends AbstractCommand {
        private StopWaveSubCommand() {
            super("stop", "Stop an active wave sequence");
            this.addAliases("end");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                MobWaveManager.StopResult result = MobWaveManager.stopForPlayer(playerRef);
                if (!result.stopped) {
                    ctx.sendMessage(Message.raw("No active waves for you.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw("Stopped active wave for role " + result.roleName + ".").color("#6cff78"));
            });
        }
    }

    private static final class StatusWaveSubCommand extends AbstractCommand {
        private StatusWaveSubCommand() {
            super("status", "Show your current wave status");
            this.addAliases("info");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                MobWaveManager.StatusResult status = MobWaveManager.getStatus(playerRef);
                if (status == null) {
                    ctx.sendMessage(Message.raw("No active waves for you.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw(
                                "Active wave: rank=" + status.rankTier.letter()
                                        + " role=" + status.roleName
                                        + " waves=" + status.waves
                                        + " mobsPerWave=" + status.mobsPerWave
                                        + " interval=" + status.intervalSeconds + "s"
                                        + " baseLevelRange=" + status.levelMin + "-" + status.levelMax)
                        .color("#8fd3ff"));
            });
        }
    }

    private static final class SkipWaveSubCommand extends AbstractCommand {
        private SkipWaveSubCommand() {
            super("skip", "Kill current wave mobs and advance to next wave (debug)");
            this.addAliases("next");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                MobWaveManager.SkipResult result = MobWaveManager.skipWaveForPlayer(playerRef);
                if (!result.skipped) {
                    ctx.sendMessage(Message.raw("No active waves for you.").color("#ffcc66"));
                    return;
                }

                if (result.completed) {
                    ctx.sendMessage(Message.raw(
                            "Skipped final wave. Killed " + result.killed + " mob(s) and finished the sequence.")
                            .color("#6cff78"));
                    return;
                }

                ctx.sendMessage(Message.raw(
                        "Skipped wave: killed " + result.killed + " mob(s). Advanced to wave "
                                + result.nextWave + "/" + result.totalWaves + ".")
                        .color("#6cff78"));
            });
        }
    }
}