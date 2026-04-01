package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.compatibility.EndlessLevelingCompatibility;
import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.entity.entities.Player;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class PortalGateDungeonTestCommand extends AbstractCommand {

    private final RequiredArg<String> rankArg = this.withRequiredArg(
            "rank",
            "Rank tier (S/A/B/C/D/E)",
            Objects.requireNonNull(ArgTypes.STRING));

    public PortalGateDungeonTestCommand() {
        super("test", "Spawn a dungeon-only gate (no wave/hybrid) near you (/gate dungeon test <S|A|B|C|D|E>)");
        this.addAliases("dungeontest", "testspawn", "spawntest");
    }

    @Nullable
    private static GateRankTier parseRankTier(@Nonnull String input) {
        try {
            return GateRankTier.valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        String rawArg = rankArg.get(context);
        String normalizedArg = rawArg == null ? "" : rawArg.trim().toUpperCase(Locale.ROOT);

        if (normalizedArg.isBlank()) {
            context.sendMessage(Message.raw("Usage: /gate dungeon test <S|A|B|C|D|E>").color("#d9f0ff"));
            context.sendMessage(Message.raw("Spawns a dungeon-only gate — no wave or hybrid component.").color("#d9f0ff"));
            return CompletableFuture.completedFuture(null);
        }

        GateRankTier rankTier = parseRankTier(normalizedArg);
        if (rankTier == null) {
            context.sendMessage(Message.raw("Unknown rank '" + rawArg + "'. Use S A B C D E.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        return NaturalPortalGateManager.spawnGateNearPlayerWithRankAndForcedNoLinkedWave(player, rankTier, true)
            .thenAccept(spawned -> {
                if (spawned) {
                    context.sendMessage(
                            Message.raw("Spawned a " + rankTier.letter() + "-rank dungeon gate (test mode, no hybrid) near your loaded chunks.").color("#6cff78"));
                } else {
                    context.sendMessage(
                            Message.raw("Failed to spawn a dungeon gate nearby (no suitable loaded chunk found).")
                                    .color("#ff6666"));
                }
            });
    }
}
