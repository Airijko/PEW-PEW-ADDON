package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.events.PortalInstanceDiagnostics;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class ClearElDungeonsCommand extends AbstractCommand {

    public ClearElDungeonsCommand() {
        super("elcleardungeons", "Clear all tracked Endless Leveling dungeon instances");
        this.addAliases("cleareldungeons", "elclearinstances", "eldungeonsclear");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Clearing all tracked EL dungeon instances...").color("#ffcc66"));

        return CompletableFuture.runAsync(() -> {
            int purged = PortalInstanceDiagnostics.purgeTrackedInstancesOnShutdown();
            context.sendMessage(Message.raw("Requested removal for " + purged + " EL dungeon instance target(s).").color("#6cff78"));
        }).exceptionally(exception -> {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            context.sendMessage(Message.raw("Dungeon clear failed: " + cause.getMessage()).color("#ff6666"));
            return null;
        });
    }
}
