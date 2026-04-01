package com.airijko.endlessleveling.commands.gate;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class GateDungeonCommand extends AbstractCommand {

    public GateDungeonCommand() {
        super("dungeon", "Dungeon gate commands");
        this.addAliases("dungeons", "dungeongate", "dungeongates");

        this.addSubCommand(new PortalGiveCommand());
        this.addSubCommand(new PortalGateTestCommand());
        this.addSubCommand(new PortalBlockAdminCommand());
        this.addSubCommand(new PortalReturnPosCommand());
        this.addSubCommand(new ClearElDungeonsCommand());
        this.addSubCommand(new GateInstancesCommand());
        this.addSubCommand(new GateTrackCommand());
        this.addSubCommand(new GateDebugCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw(
                "Usage: /gate dungeon <give|spawn|blocks|returnpos|deleteinstances|instances|track|debug> ... (alias: /gate dungeongate ...)")
                .color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }
}