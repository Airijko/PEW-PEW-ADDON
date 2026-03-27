package com.airijko.endlessleveling.commands;

import com.airijko.endlessleveling.EndlessLevelingAddon;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public final class AddonReloadCommand extends AbstractCommand {

    private final EndlessLevelingAddon addon;

    public AddonReloadCommand(@Nonnull EndlessLevelingAddon addon) {
        super("elreload", "Reload Endless Leveling addon YAML content and dungeon gate config");
        this.addon = addon;
        this.addAliases("endreload", "eladdonreload", "elmodreload");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Reloading Endless Leveling addon content...").color("#ffcc66"));

        return CompletableFuture.runAsync(() -> {
            EndlessLevelingAddon.ReloadSummary summary = addon.reloadAddonRuntime();
            context.sendMessage(Message.raw("Reload complete.").color("#6cff78"));
            context.sendMessage(Message.raw(String.format(
                    "Unregistered: races=%d classes=%d augments=%d passives=%d",
                    summary.unregisteredRaces,
                    summary.unregisteredClasses,
                    summary.unregisteredAugments,
                    summary.unregisteredPassives)).color("#9cd6ff"));
            context.sendMessage(Message.raw(String.format(
                    "Registered: races=%d classes=%d augments=%d passives=%d",
                    summary.registeredRaces,
                    summary.registeredClasses,
                    summary.registeredAugments,
                    summary.registeredPassives)).color("#9cd6ff"));
            context.sendMessage(Message.raw(String.format(
                    "Dungeon gates: enabled=%s spawn_interval=%dmin duration=%dmin",
                    summary.dungeonGateEnabled,
                    summary.spawnIntervalMinutes,
                    summary.gateDurationMinutes)).color("#9cd6ff"));
        }).exceptionally(exception -> {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            context.sendMessage(Message.raw("Reload failed: " + cause.getMessage()).color("#ff6666"));
            return null;
        });
    }
}
