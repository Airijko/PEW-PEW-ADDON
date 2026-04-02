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
        this.addSubCommand(new GateListCommand());
        this.addSubCommand(new GateTrackCommand());
        this.addSubCommand(new GateDungeonCommand());
        this.addSubCommand(new PortalWaveCommand());
        this.addSubCommand(new GateClearAllCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("EL gate commands").color("#8fd3ff"));
        context.sendMessage(Message.raw("Use one of:").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate list           Unified live gate list with IDs for dungeon, outbreak, and hybrid gates").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate track <id>     Open the live gate tracker HUD for one active gate entry").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon ...    Dungeon gate tools, spawning, tracking, cleanup, debug").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate wave ...       Wave gate / outbreak tools, test waves, combo tools").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate clearall       Clear dungeon/wave gates and stop active waves in your world").color("#d9f0ff"));
        context.sendMessage(Message.raw("Examples: /gate list   |   /gate track D-1A2B   |   /gate dungeon spawn D").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }
}