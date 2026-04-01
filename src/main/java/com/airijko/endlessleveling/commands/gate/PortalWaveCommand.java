package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.api.gates.WaveSessionResults;
import com.airijko.endlessleveling.compatibility.EndlessLevelingCompatibility;
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

    private final RequiredArg<String> rankArg = this.withRequiredArg(
            "rank_or_random",
            "Wave gate rank tier (S/A/B/C/D/E) or 'random' — starts a natural wave gate outbreak countdown",
            Objects.requireNonNull(ArgTypes.STRING));

    public PortalWaveCommand() {
        super("wave", "Wave gate commands");
        this.addAliases("waves", "wavegate", "wavegates", "outbreak", "outbreaks");
        this.addSubCommand(new TestWaveSubCommand());
        this.addSubCommand(new TestGateWaveComboSubCommand());
        this.addSubCommand(new ClearGateWaveComboSubCommand());
        this.addSubCommand(new StartWaveSubCommand());
        this.addSubCommand(new StopWaveSubCommand());
        this.addSubCommand(new SkipWaveSubCommand());
        this.addSubCommand(new StatusWaveSubCommand());
        this.addSubCommand(new ClearWaveParticlesSubCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        String rawArg = rankArg.get(context);
        if (rawArg == null || rawArg.isBlank()) {
            context.sendMessage(Message.raw(
                    "Usage: /gate wave <S|A|B|C|D|E|random>  -or-  /gate wave test <rank>  -or-  /gate wave testcombo <rank>  |  clearcombo|clearparticles|stop|skip|status (aliases: /gate wavegate ..., legacy /gate outbreak ...)")
                    .color("#ffcc66"));
            return CompletableFuture.completedFuture(null);
        }
        String normalizedArg = rawArg.trim().toUpperCase(Locale.ROOT);
        String rankTierId = "RANDOM".equals(normalizedArg) ? null : normalizedArg;
        if (rankTierId != null && parseRankTier(rankTierId) == null) {
            context.sendMessage(Message.raw("Unknown rank '" + rawArg + "'. Use: S A B C D E random").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        WaveSessionResults.NaturalStartResult result =
            EndlessLevelingCompatibility.startNaturalWaveForPlayer(player, rankTierId);
        context.sendMessage(Message.raw(result.message())
            .color(result.scheduled() ? "#6cff78" : "#ff6666"));
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

    private static final class TestWaveSubCommand extends AbstractCommand {
        private final RequiredArg<String> rankArg =
                this.withRequiredArg("rank", "Wave rank tier (S/A/B/C/D/E)", Objects.requireNonNull(ArgTypes.STRING));

        private TestWaveSubCommand() {
            super("test", "Start an immediate test wave — mobs spawn right away, no countdown");
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
                WaveSessionResults.StartResult result =
                        EndlessLevelingCompatibility.startWaveForPlayer(playerRef, tier.name());
                if (!result.started()) {
                    ctx.sendMessage(Message.raw(result.message()).color("#ff6666"));
                    return;
                }
                String rankLetter = result.rankTierId() == null ? tier.letter() : result.rankTierId();
                ctx.sendMessage(Message.raw("Test wave started immediately.").color("#6cff78"));
                ctx.sendMessage(Message.raw(
                                "Rank=" + rankLetter
                                        + " waves=" + result.waves()
                                        + " mobsPerWave=" + result.mobsPerWave()
                                        + " interval=" + result.intervalSeconds() + "s"
                                        + " levelRange=" + result.levelMin() + "-" + result.levelMax())
                        .color("#8fd3ff"));
            });
        }
    }

    private static final class TestGateWaveComboSubCommand extends AbstractCommand {
        private final RequiredArg<String> rankArg =
                this.withRequiredArg("rank", "Dungeon gate rank tier (S/A/B/C/D/E)", Objects.requireNonNull(ArgTypes.STRING));

        private TestGateWaveComboSubCommand() {
            super("testcombo", "Spawn a test dungeon gate forcibly linked to a wave gate countdown");
            this.addAliases("combo", "testgatewave");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            if (!(context.sender() instanceof Player player)) {
                context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
                return CompletableFuture.completedFuture(null);
            }

            GateRankTier tier = parseRankTier(Objects.requireNonNull(rankArg.get(context)));
            if (tier == null) {
                context.sendMessage(Message.raw("Invalid rank. Use one of: S A B C D E").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            return EndlessLevelingCompatibility.spawnDungeonGateWithForcedLinkedWave(player, tier.name(), true)
                    .thenAccept(spawned -> {
                        if (!spawned) {
                            context.sendMessage(Message.raw("Failed to spawn test dungeon gate + wave gate combo nearby.").color("#ff6666"));
                            return;
                        }

                        context.sendMessage(Message.raw(
                                String.format(Locale.ROOT,
                                        "Spawned %s-rank dungeon gate + wave gate test combo. Dungeon gate stays locked until linked waves are cleared.",
                                        tier.letter())).color("#6cff78"));
                    });
        }
    }

    private static final class ClearGateWaveComboSubCommand extends AbstractCommand {
        private ClearGateWaveComboSubCommand() {
            super("clearcombo", "Clear your linked dungeon gate + wave gate combo locks and countdowns");
            this.addAliases("clearlinked", "comboff", "unlockgatewave");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                int cleared = EndlessLevelingCompatibility.clearLinkedGateCombosForPlayer(playerRef);
                if (cleared <= 0) {
                    ctx.sendMessage(Message.raw("No linked dungeon gate + wave gate combos to clear.").color("#ffcc66"));
                    return;
                }
                ctx.sendMessage(Message.raw("Cleared " + cleared + " linked dungeon gate + wave gate combo(s).").color("#6cff78"));
            });
        }
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

                WaveSessionResults.StartResult result =
                        EndlessLevelingCompatibility.startWaveForPlayer(playerRef, tier.name());
                if (!result.started()) {
                    ctx.sendMessage(Message.raw(result.message()).color("#ff6666"));
                    return;
                }
                String rankLetter = result.rankTierId() == null ? tier.letter() : result.rankTierId();

                ctx.sendMessage(Message.raw("Started wave sequence.").color("#6cff78"));
                ctx.sendMessage(Message.raw(
                                "Rank=" + rankLetter
                                        + " role=" + result.roleName()
                                        + " waves=" + result.waves()
                                        + " mobsPerWave=" + result.mobsPerWave()
                                        + " interval=" + result.intervalSeconds() + "s"
                                        + " levelRange=" + result.levelMin() + "-" + result.levelMax())
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
                WaveSessionResults.StopResult result = EndlessLevelingCompatibility.stopWaveForPlayer(playerRef);
                if (!result.stopped()) {
                    ctx.sendMessage(Message.raw("No active waves for you.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw("Stopped active wave for role " + result.roleName() + ".").color("#6cff78"));
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
                WaveSessionResults.StatusResult status = EndlessLevelingCompatibility.getWaveStatus(playerRef);
                if (status == null) {
                    ctx.sendMessage(Message.raw("No active waves for you.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw(
                                "Active wave: rank=" + status.rankTierId()
                                        + " role=" + status.roleName()
                                        + " waves=" + status.waves()
                                        + " mobsPerWave=" + status.mobsPerWave()
                                        + " interval=" + status.intervalSeconds() + "s"
                                        + " baseLevelRange=" + status.levelMin() + "-" + status.levelMax())
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
                WaveSessionResults.SkipResult result = EndlessLevelingCompatibility.skipWaveForPlayer(playerRef);
                if (!result.skipped()) {
                    ctx.sendMessage(Message.raw("No active waves for you.").color("#ffcc66"));
                    return;
                }

                if (result.completed()) {
                    ctx.sendMessage(Message.raw(
                            "Skipped final wave. Killed " + result.killed() + " mob(s) and finished the sequence.")
                            .color("#6cff78"));
                    return;
                }

                ctx.sendMessage(Message.raw(
                        "Skipped wave: killed " + result.killed() + " mob(s). Advanced to wave "
                                + result.nextWave() + "/" + result.totalWaves() + ".")
                        .color("#6cff78"));
            });
        }
    }

    private static final class ClearWaveParticlesSubCommand extends AbstractCommand {
        private ClearWaveParticlesSubCommand() {
            super("clearparticles", "Clear nearby stuck wave magicportal visuals");
            this.addAliases("purgeparticles", "cleanparticles", "clearwaveportals");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runWithPlayerRefOnWorldThread(context, (ctx, playerRef) -> {
                int removed = EndlessLevelingCompatibility.clearWavePortalVisualsInPlayerWorld(playerRef);
                if (removed <= 0) {
                    ctx.sendMessage(Message.raw("No wave magicportal anchor blocks were found in this world.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw("Cleared " + removed + " wave magicportal block(s) in this world.")
                        .color("#6cff78"));
            });
        }
    }
}