package com.airijko.endlessleveling.commands.gate;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class GateDebugCommand extends AbstractCommand {

    public GateDebugCommand() {
        super("debug", "Gate debug tools");
        this.addSubCommand(new GateDebugPreventEnterCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /gate debug <prevententer> ...").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }
}
