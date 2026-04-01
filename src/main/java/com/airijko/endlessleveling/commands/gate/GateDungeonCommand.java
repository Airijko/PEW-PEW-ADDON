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
        this.addSubCommand(new PortalGateDungeonTestCommand());
        this.addSubCommand(new PortalBlockAdminCommand());
        this.addSubCommand(new PortalReturnPosCommand());
        this.addSubCommand(new ClearElDungeonsCommand());
        this.addSubCommand(new GateInstancesCommand());
        this.addSubCommand(new GateDebugCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Dungeon gate commands").color("#8fd3ff"));
        context.sendMessage(Message.raw("Valid subcommands:").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon spawn <S|A|B|C|D|E|random>").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon test <S|A|B|C|D|E>  (dungeon-only, no wave/hybrid)").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon give <d1|d2|d3|swamp|frozen|void|all> <E|D|C|B|A|S>").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon blocks <list|remove-nearest|remove-all>").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon returnpos").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon instances <list|delete <id>>").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon deleteinstances").color("#d9f0ff"));
        context.sendMessage(Message.raw("- /gate dungeon debug prevententer <true|false|status>").color("#d9f0ff"));
        context.sendMessage(Message.raw("Tracker commands: /gate list   |   /gate track <id>").color("#8fd3ff"));
        context.sendMessage(Message.raw("Examples: /gate dungeon spawn D   |   /gate dungeon give frozen S").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }
}