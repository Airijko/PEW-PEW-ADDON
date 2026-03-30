package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.managers.TestMobWaveManager;
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

public final class PortalWaveTestCommand extends AbstractCommand {

    public PortalWaveTestCommand() {
        super("wave", "Run barebones mob wave tests around your player");
        this.addAliases("waves", "wavetest", "mobwave");
        this.addSubCommand(new StartWaveSubCommand());
        this.addSubCommand(new StopWaveSubCommand());
        this.addSubCommand(new StatusWaveSubCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /gate wave <start <rank>|stop|status> (rank: S A B C D E)").color("#ffcc66"));
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
            super("start", "Start a barebones test wave sequence");
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

                TestMobWaveManager.StartResult result = TestMobWaveManager.startForPlayer(playerRef, tier);
                if (!result.started) {
                    ctx.sendMessage(Message.raw(result.message).color("#ff6666"));
                    return;
                }
                String rankLetter = result.rankTier == null ? tier.letter() : result.rankTier.letter();

                ctx.sendMessage(Message.raw("Started wave test.").color("#6cff78"));
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
            super("stop", "Stop an active test wave sequence");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                TestMobWaveManager.StopResult result = TestMobWaveManager.stopForPlayer(playerRef);
                if (!result.stopped) {
                    ctx.sendMessage(Message.raw("No active wave test for you.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw("Stopped active wave test for role " + result.roleName + ".").color("#6cff78"));
            });
        }
    }

    private static final class StatusWaveSubCommand extends AbstractCommand {
        private StatusWaveSubCommand() {
            super("status", "Show your current wave test status");
            this.addAliases("info");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                TestMobWaveManager.StatusResult status = TestMobWaveManager.getStatus(playerRef);
                if (status == null) {
                    ctx.sendMessage(Message.raw("No active wave test for you.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw(
                                "Active wave test: rank=" + status.rankTier.letter()
                                        + " role=" + status.roleName
                                        + " waves=" + status.waves
                                        + " mobsPerWave=" + status.mobsPerWave
                                        + " interval=" + status.intervalSeconds + "s"
                                        + " baseLevelRange=" + status.levelMin + "-" + status.levelMax)
                        .color("#8fd3ff"));
            });
        }
    }
}