package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.managers.GateTrackerManager;
import com.airijko.endlessleveling.managers.GateTrackerManager.GateTrackerEntry;
import com.airijko.endlessleveling.ui.GateListUIPage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;

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

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (context.sender() instanceof Player) {
            return GateListUIPage.openForSender(context);
        }
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
        context.sendMessage(Message.join(
            Message.raw("Legend: ").color("#9db8c9"),
            Message.raw("Dungeon").color(typeColor(GateTrackerManager.GateEntryType.DUNGEON)),
            Message.raw(" | ").color("#6f8fa3"),
            Message.raw("Outbreak").color(typeColor(GateTrackerManager.GateEntryType.OUTBREAK)),
            Message.raw(" | ").color("#6f8fa3"),
            Message.raw("Hybrid").color(typeColor(GateTrackerManager.GateEntryType.HYBRID)),
            Message.raw("  (OPENED = green, CLOSED = amber)").color("#9db8c9")));

        int displayCount = Math.min(MAX_LIST_LINES, entries.size());
        for (int index = 0; index < displayCount; index++) {
            GateTrackerEntry entry = entries.get(index);
            context.sendMessage(Message.join(
                Message.raw("#" + (index + 1) + " ").color("#d9f0ff"),
                Message.raw("[" + typeLabel(entry.type()) + "]").color(typeColor(entry.type())),
                Message.raw(" ").color("#d9f0ff"),
                Message.raw("[Rank " + entry.rankLetter() + "]").color(rankColor(entry.rankLetter())),
                Message.raw(" ").color("#d9f0ff"),
                Message.raw(entry.title()).color("#f2fbff")));

            context.sendMessage(Message.join(
                Message.raw("      Location: ").color("#6f8fa3"),
                Message.raw(formatCoords(entry.x(), entry.y(), entry.z())).color("#9ed8ff"),
                Message.raw("  in  ").color("#6f8fa3"),
                Message.raw(entry.worldName()).color("#9ed8ff"),
                Message.raw("  |  Seen ").color("#6f8fa3"),
                Message.raw(formatElapsed(entry.firstSeenMillis())).color("#9ed8ff")));

            context.sendMessage(Message.join(
                Message.raw("      Status: ").color("#6f8fa3"),
                Message.raw(entry.status()).color(statusColor(entry.status())),
                Message.raw("  |  Opens: ").color("#6f8fa3"),
                Message.raw(formatSchedule(entry.opensAtEpochMillis())).color("#9ed8ff"),
                Message.raw("  |  Expires: ").color("#6f8fa3"),
                Message.raw(formatSchedule(entry.expiresAtEpochMillis())).color("#9ed8ff")));
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
    private static String formatSchedule(long epochMillis) {
        if (epochMillis <= 0L) {
            return "--";
        }
        long now = System.currentTimeMillis();
        long deltaSeconds = (epochMillis - now) / 1000L;
        if (deltaSeconds >= 0L) {
            return "in " + formatDurationSeconds(deltaSeconds);
        }
        return formatDurationSeconds(Math.abs(deltaSeconds)) + " ago";
    }

    @Nonnull
    private static String formatDurationSeconds(long seconds) {
        if (seconds < 60L) {
            return seconds + "s";
        }
        long mins = seconds / 60L;
        long secs = seconds % 60L;
        if (mins < 60L) {
            return mins + "m " + secs + "s";
        }
        long hours = mins / 60L;
        long remainMins = mins % 60L;
        return hours + "h " + remainMins + "m";
    }

    @Nonnull
    private static String typeLabel(@Nonnull GateTrackerManager.GateEntryType type) {
        return switch (type) {
            case DUNGEON -> "DUNGEON GATE";
            case OUTBREAK -> "OUTBREAK GATE";
            case HYBRID -> "HYBRID GATE";
        };
    }

    @Nonnull
    private static String typeColor(@Nonnull GateTrackerManager.GateEntryType type) {
        return switch (type) {
            case DUNGEON -> "#5ec8ff";
            case OUTBREAK -> "#ff8a5b";
            case HYBRID -> "#d48dff";
        };
    }

    @Nonnull
    private static String rankColor(@Nullable String rankLetter) {
        if (rankLetter == null) {
            return "#c7d7e0";
        }
        return switch (rankLetter.toUpperCase()) {
            case "S" -> "#ff6767";
            case "A" -> "#ff9959";
            case "B" -> "#ffd166";
            case "C" -> "#b9e769";
            case "D" -> "#89d4ff";
            default -> "#c7d7e0";
        };
    }

    @Nonnull
    private static String statusColor(@Nonnull String status) {
        String normalized = status.toUpperCase();
        if (normalized.startsWith("OPEN")) {
            return "#6cff78";
        }
        if (normalized.startsWith("CLOSED")) {
            return "#ffcc66";
        }
        return "#9ed8ff";
    }

    @Nonnull
    private static String formatCoords(@Nullable Integer x, @Nullable Integer y, @Nullable Integer z) {
        if (x == null || y == null || z == null) {
            return "(<untracked>)";
        }
        return String.format("(%4d, %3d, %4d)", x, y, z);
    }
}