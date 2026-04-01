package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.compatibility.EndlessLevelingCompatibility;
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

public class PortalGateTestCommand extends AbstractCommand {

    private final RequiredArg<String> spawnArg = this.withRequiredArg(
            "rank_or_random",
            "Rank tier (S/A/B/C/D/E) or random",
            Objects.requireNonNull(ArgTypes.STRING));

    public PortalGateTestCommand() {
        super("spawn", "Spawn an EL dungeon gate near you (/gate dungeon spawn <S|A|B|C|D|E|random>)");
        this.addAliases("gatespawn", "elgatespawn", "spawnrandomgate", "spawndungeongate");
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

        String rawArg = spawnArg.get(context);
        String normalizedArg = rawArg == null ? "" : rawArg.trim().toUpperCase(Locale.ROOT);

        if (normalizedArg.isBlank()) {
            context.sendMessage(Message.raw("Usage: /gate dungeon spawn <S|A|B|C|D|E|random>").color("#d9f0ff"));
            context.sendMessage(Message.raw("Examples: /gate dungeon spawn random   |   /gate dungeon spawn A").color("#ffcc66"));
            return CompletableFuture.completedFuture(null);
        }

        if ("RANDOM".equals(normalizedArg)) {
            return EndlessLevelingCompatibility.spawnRandomDungeonGate(player, false)
                    .thenAccept(spawned -> {
                        if (spawned) {
                            context.sendMessage(
                                    Message.raw("Spawned a random dungeon gate near your loaded chunks.").color("#6cff78"));
                        } else {
                            context.sendMessage(
                                    Message.raw("Failed to spawn a dungeon gate nearby (no suitable loaded chunk found).")
                                            .color("#ff6666"));
                        }
                    });
        }

        GateRankTier rankTier = parseRankTier(normalizedArg);
        if (rankTier == null) {
            context.sendMessage(Message.raw("Unknown rank '" + rawArg + "'. Use S A B C D E or random.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        return EndlessLevelingCompatibility.spawnDungeonGateWithRank(player, rankTier.name(), false)
            .thenAccept(spawned -> {
                if (spawned) {
                    context.sendMessage(
                            Message.raw("Spawned a " + rankTier.letter() + "-rank dungeon gate near your loaded chunks.").color("#6cff78"));
                } else {
                    context.sendMessage(
                            Message.raw("Failed to spawn a dungeon gate nearby (no suitable loaded chunk found).")
                                    .color("#ff6666"));
                }
            });
    }
}