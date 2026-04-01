package com.airijko.endlessleveling.commands.gate;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class GateCommand extends AbstractCommand {

    public GateCommand() {
        super("gate", "Root command for EL gate-type admin tools");
        this.addAliases("g", "elgate", "gatetype", "gatetypes");
        this.addSubCommand(new GateDungeonCommand());
        this.addSubCommand(new PortalWaveCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw(
            "Usage: /gate <dungeon|dungeongate|wave|wavegate|outbreak> ...")
                .color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }
}