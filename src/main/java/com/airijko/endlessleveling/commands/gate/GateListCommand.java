package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.managers.GateTrackerManager;
import com.airijko.endlessleveling.managers.GateTrackerManager.GateTrackerEntry;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class GateListCommand extends AbstractCommand {

    private static final int MAX_LIST_LINES = 60;

    public GateListCommand() {
        super("list", "List active dungeon, outbreak, and hybrid gates with tracker IDs");
        this.addAliases("ls", "active", "gatelist", "gates");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        sendGateList(context, true);
        return CompletableFuture.completedFuture(null);
    }

    static void sendGateList(@Nonnull CommandContext context, boolean includeTrackerHint) {
        List<GateTrackerEntry> entries = GateTrackerManager.listEntries();
        if (entries.isEmpty()) {
            context.sendMessage(Message.raw("No active dungeon gates, outbreaks, or hybrids found.").color("#ffcc66"));
            return;
        }

        context.sendMessage(Message.raw("-- Live Gates (" + entries.size() + ") --").color("#8fd3ff"));

        int displayCount = Math.min(MAX_LIST_LINES, entries.size());
        for (int index = 0; index < displayCount; index++) {
            GateTrackerEntry entry = entries.get(index);
            // Line 1: index, type, rank, name
            context.sendMessage(Message.raw(
                    String.format("#%-2d  %-9s  %s  %s",
                            index + 1,
                            entry.type().label(),
                            entry.rankLetter(),
                            entry.title())
            ).color("#d9f0ff"));
            // Line 2: coords | world | status | elapsed
            context.sendMessage(Message.raw(
                    String.format("      %s  |  %s  |  %s  |  %s",
                            formatCoords(entry.x(), entry.y(), entry.z()),
                            entry.worldName(),
                            entry.status(),
                            formatElapsed(entry.firstSeenMillis()))
            ).color("#8fd3ff"));
        }

        if (entries.size() > displayCount) {
            context.sendMessage(Message.raw("Showing first " + displayCount + " entries.").color("#ffcc66"));
        }
        if (includeTrackerHint) {
            context.sendMessage(Message.raw("Track one: /gate track <index>  (example: /gate track 1)").color("#8fd3ff"));
            context.sendMessage(Message.raw("Token IDs also work: /gate track <token-id>  |  clear: /gate track clear").color("#8fd3ff"));
        }
    }

    @Nonnull
    private static String formatElapsed(long firstSeenMillis) {
        long elapsed = (System.currentTimeMillis() - firstSeenMillis) / 1000L;
        if (elapsed < 0) elapsed = 0;
        if (elapsed < 60) return elapsed + "s";
        long mins = elapsed / 60;
        long secs = elapsed % 60;
        if (mins < 60) return mins + "m " + secs + "s";
        long hours = mins / 60;
        long remainMins = mins % 60;
        return hours + "h " + remainMins + "m";
    }

    @Nonnull
    private static String formatCoords(@Nullable Integer x, @Nullable Integer y, @Nullable Integer z) {
        if (x == null || y == null || z == null) {
            return "(<untracked>)";
        }
        return String.format("(%4d, %3d, %4d)", x, y, z);
    }
}