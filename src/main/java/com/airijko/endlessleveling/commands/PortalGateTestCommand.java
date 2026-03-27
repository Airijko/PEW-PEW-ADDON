package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class PortalGateTestCommand extends AbstractCommand {

    public PortalGateTestCommand() {
        super("gatespawntest", "Spawn a random EL gate near you for testing");
        this.addAliases("elgatespawntest", "spawnrandomgate");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        return NaturalPortalGateManager.spawnRandomGateNearPlayer(player, true)
                .thenAccept(spawned -> {
                    if (spawned) {
                        context.sendMessage(Message.raw("Spawned a random gate near your loaded chunks.").color("#6cff78"));
                        String rankLine = NaturalPortalGateManager.consumeLastSpawnRankLine(player.getUuid());
                        if (rankLine != null) {
                            context.sendMessage(Message.raw(rankLine).color("#ff8fab"));
                        }
                    } else {
                        context.sendMessage(Message.raw("Failed to spawn a gate nearby (no suitable loaded chunk found).").color("#ff6666"));
                    }
                });
    }
}