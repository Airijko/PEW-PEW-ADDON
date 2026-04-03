package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.managers.GateTrackerManager;
import com.airijko.endlessleveling.managers.GateTrackerManager.GateTrackerEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

public final class GateListUIPage extends InteractiveCustomUIPage<com.airijko.endlessleveling.ui.SkillsUIPage.Data> {

    private static final String ROW_TEMPLATE = "Pages/Gates/GateEntryRow.ui";

    /** Number of rows appended at build time — used for refresh-only updates. */
    private int builtRowCount = 0;

    public GateListUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, com.airijko.endlessleveling.ui.SkillsUIPage.Data.CODEC);
    }

    @Nonnull
    public static CompletableFuture<Void> openForSender(@Nonnull CommandContext context) {
        Player player = context.senderAs(Player.class);
        if (player == null) {
            context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        World world = player.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("This command is player-only and requires being in-world.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    context.sendMessage(Message.raw("Could not resolve player state. Try again in a moment.").color("#ff6666"));
                    return;
                }

                Store<EntityStore> store = ref.getStore();
                PlayerRef senderRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (senderRef == null || !senderRef.isValid()) {
                    context.sendMessage(Message.raw("Could not resolve player state. Try again in a moment.").color("#ff6666"));
                    return;
                }

                player.getPageManager().openCustomPage(ref, store,
                        new GateListUIPage(senderRef, CustomPageLifetime.CanDismiss));
            } finally {
                future.complete(null);
            }
        });
        return future;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        ui.append("Pages/Gates/GateListPage.ui");
        ui.set("#GatePageTitle.Text", "LIVE GATE LIST");
        ui.set("#GatePageSubtitle.Text", "Track one active dungeon, outbreak, or hybrid gate.");

        List<GateTrackerEntry> entries = GateTrackerManager.listEntries();
        ui.clear("#GateRows");
        for (int i = 0; i < entries.size(); i++) {
            ui.append("#GateRows", ROW_TEMPLATE);
            events.addEventBinding(Activating,
                    "#GateRows[" + i + "] #TrackButton",
                    of("Action", "gateui:track:" + i),
                    false);
        }
        builtRowCount = entries.size();

        ui.set("#GateCountLabel.Text", "Active Gates: " + entries.size());
        GateTrackerEntry tracked = GateTrackerManager.getTrackedEntry(playerRef.getUuid());
        for (int i = 0; i < entries.size(); i++) {
            applyRowProperties(ui, "#GateRows[" + i + "] ", entries.get(i), tracked, i);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull com.airijko.endlessleveling.ui.SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null || !data.action.startsWith("gateui:track:")) {
            return;
        }

        int rowIndex = parseRowIndex(data.action.substring("gateui:track:".length()));
        if (rowIndex < 0) {
            return;
        }

        List<GateTrackerEntry> entries = GateTrackerManager.listEntries();
        if (rowIndex >= entries.size()) {
            return;
        }

        GateTrackerEntry entry = entries.get(rowIndex);
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        GateTrackerManager.setTrackedEntry(playerRef.getUuid(), entry);
        GateTrackerHud.OpenStatus status = GateTrackerHud.open(player, playerRef);

        String feedback;
        String color;
        if (status == GateTrackerHud.OpenStatus.OPENED) {
            feedback = "Tracking " + entry.title();
            color = "#6cff78";
            playerRef.sendMessage(Message.raw(feedback).color(color));
        } else if (status == GateTrackerHud.OpenStatus.BLOCKED_BY_EXISTING_HUD) {
            GateTrackerManager.clearTrackedEntry(playerRef.getUuid());
            feedback = "Tracker blocked by another custom HUD slot.";
            color = "#ff6666";
            playerRef.sendMessage(Message.raw(feedback).color(color));
        } else {
            GateTrackerManager.clearTrackedEntry(playerRef.getUuid());
            feedback = "Could not resolve player state for tracker HUD.";
            color = "#ff6666";
            playerRef.sendMessage(Message.raw(feedback).color(color));
        }

        UICommandBuilder ui = new UICommandBuilder();
        ui.set("#FeedbackText.Text", feedback);
        ui.set("#FeedbackText.Style.TextColor", color);
        refreshRows(ui);
        this.sendUpdate(ui, new UIEventBuilder(), false);
    }

    /**
     * Refreshes row properties on the already-appended rows (no clear/append — event bindings
     * from build time are preserved).
     */
    private void refreshRows(@Nonnull UICommandBuilder ui) {
        List<GateTrackerEntry> entries = GateTrackerManager.listEntries();
        GateTrackerEntry tracked = GateTrackerManager.getTrackedEntry(playerRef.getUuid());

        ui.set("#GateCountLabel.Text", "Active Gates: " + entries.size());

        int count = Math.min(entries.size(), builtRowCount);
        for (int i = 0; i < count; i++) {
            applyRowProperties(ui, "#GateRows[" + i + "] ", entries.get(i), tracked, i);
        }
    }

    private void applyRowProperties(@Nonnull UICommandBuilder ui,
            @Nonnull String base,
            @Nonnull GateTrackerEntry entry,
            @Nullable GateTrackerEntry tracked,
            int index) {
        boolean isTracked = tracked != null && tracked.uniqueKey().equals(entry.uniqueKey());

        ui.set(base + "#TypeAccent.Background", typeColor(entry));
        ui.set(base + "#Title.Text", "#" + (index + 1) + " " + entry.title());
        ui.set(base + "#Title.Style.TextColor", typeColor(entry));
        ui.set(base + "#Meta.Text", "Type: " + entry.type().label() + " | Rank: " + entry.rankLetter()
                + " | World: " + entry.worldName());
        ui.set(base + "#Status.Text", "Status: " + entry.status()
                + " | Coords: " + formatCoords(entry.x(), entry.y(), entry.z()));
        ui.set(base + "#Status.Style.TextColor", statusColor(entry.status()));
        ui.set(base + "#TrackButton.Text", isTracked ? "TRACKING" : "TRACK");
        ui.set(base + "#TrackButton.Style.Default.LabelStyle.TextColor", isTracked ? "#f8fbff" : "#bfd7ea");
        ui.set(base + "#TrackButton.Style.Hovered.LabelStyle.TextColor", isTracked ? "#ffffff" : "#e6f4ff");
        ui.set(base + "#TrackButton.Style.Pressed.LabelStyle.TextColor", isTracked ? "#dde9f5" : "#a8cde7");
    }

    private static int parseRowIndex(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    @Nonnull
    private static String typeColor(@Nonnull GateTrackerEntry entry) {
        return switch (entry.type()) {
            case DUNGEON -> "#5ec8ff";
            case OUTBREAK -> "#ff8a5b";
            case HYBRID -> "#d48dff";
        };
    }

    @Nonnull
    private static String statusColor(@Nonnull String status) {
        String normalized = status.toUpperCase(Locale.ROOT);
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
