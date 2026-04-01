package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class GateDebugPreventEnterCommand extends AbstractCommand {

    public GateDebugPreventEnterCommand() {
        super("prevententer", "Toggle dungeon gate entry prevention for debug testing");
        this.addSubCommand(new PreventEnterSetCommand(true));
        this.addSubCommand(new PreventEnterSetCommand(false));
        this.addSubCommand(new PreventEnterStatusCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /gate dungeon debug prevententer <true|false|status>").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }

    private static final class PreventEnterSetCommand extends AbstractCommand {

        private final boolean enabled;

        private PreventEnterSetCommand(boolean enabled) {
            super(enabled ? "true" : "false", enabled ? "Enable prevent-enter mode" : "Disable prevent-enter mode");
            this.enabled = enabled;
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            PortalLeveledInstanceRouter.setDebugPreventEnter(enabled);
            context.sendMessage(Message.raw("Dungeon gate debug prevententer set to " + enabled + ".").color(enabled ? "#ff6666" : "#6cff78"));
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class PreventEnterStatusCommand extends AbstractCommand {

        private PreventEnterStatusCommand() {
            super("status", "Show prevent-enter debug status");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            boolean enabled = PortalLeveledInstanceRouter.isDebugPreventEnterEnabled();
            context.sendMessage(Message.raw("Dungeon gate debug prevententer is " + enabled + ".").color(enabled ? "#ff6666" : "#6cff78"));
            return CompletableFuture.completedFuture(null);
        }
    }
}
