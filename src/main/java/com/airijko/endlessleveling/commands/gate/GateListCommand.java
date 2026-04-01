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

        context.sendMessage(Message.raw("Live gate list: " + entries.size()).color("#8fd3ff"));
        int displayCount = Math.min(MAX_LIST_LINES, entries.size());
        for (int index = 0; index < displayCount; index++) {
            GateTrackerEntry entry = entries.get(index);
            context.sendMessage(Message.raw(formatEntryLine(entry)).color("#d9f0ff"));
        }

        if (entries.size() > displayCount) {
            context.sendMessage(Message.raw("Showing first " + displayCount + " entries.").color("#ffcc66"));
        }
        if (includeTrackerHint) {
            context.sendMessage(Message.raw("Track one with /gate track <id> or clear with /gate track clear").color("#8fd3ff"));
        }
    }

    @Nonnull
    static String formatEntryLine(@Nonnull GateTrackerEntry entry) {
        return String.format(
                "[%s] %-8s rank=%s %-24s %s world=%s status=%s",
                entry.displayId(),
                entry.type().label(),
                entry.rankLetter(),
                truncate(entry.title(), 24),
                formatCoords(entry.x(), entry.y(), entry.z()),
                entry.worldName(),
                entry.status());
    }

    @Nonnull
    private static String formatCoords(@Nullable Integer x, @Nullable Integer y, @Nullable Integer z) {
        if (x == null || y == null || z == null) {
            return "(<untracked>)";
        }
        return String.format("(%4d, %3d, %4d)", x, y, z);
    }

    @Nonnull
    private static String truncate(@Nonnull String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(1, maxLength - 1)) + "~";
    }
}