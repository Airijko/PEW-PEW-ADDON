package com.airijko.endlessleveling.commands.gate;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class GateCommand extends AbstractCommand {

    public GateCommand() {
        super("gate", "Root command for EL gate test/admin tools");
        this.addAliases("g", "elgate");
        this.addSubCommand(new PortalGiveCommand());
        this.addSubCommand(new PortalGateTestCommand());
        this.addSubCommand(new PortalWaveTestCommand());
        this.addSubCommand(new PortalBlockAdminCommand());
        this.addSubCommand(new PortalReturnPosCommand());
        this.addSubCommand(new ClearElDungeonsCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw(
                "Usage: /gate <give|spawn|wave|blocks|returnpos|deleteinstances> ...")
                .color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }
}