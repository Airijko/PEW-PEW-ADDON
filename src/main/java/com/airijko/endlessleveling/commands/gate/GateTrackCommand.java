package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.api.gates.TrackedDungeonGateSnapshot;
import com.airijko.endlessleveling.api.gates.TrackedWaveGateSnapshot;
import com.airijko.endlessleveling.compatibility.EndlessLevelingCompatibility;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class GateTrackCommand extends AbstractCommand {

    public GateTrackCommand() {
        super("track", "List tracked dungeon gates, wave gates, and dungeon+wave gate combos");
        this.addAliases("tracks", "tracking", "listtrack", "trackgatetypes");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        List<TrackedDungeonGateSnapshot> allGates = EndlessLevelingCompatibility.listTrackedDungeonGates();
        List<TrackedWaveGateSnapshot> standaloneWaves = EndlessLevelingCompatibility.listTrackedStandaloneWaves();
        List<TrackedWaveGateSnapshot> combos = EndlessLevelingCompatibility.listTrackedGateWaveCombos();

        Set<String> comboGateIds = new HashSet<>();
        for (TrackedWaveGateSnapshot combo : combos) {
            if (combo.linkedGateId() != null && !combo.linkedGateId().isBlank()) {
                comboGateIds.add(combo.linkedGateId());
            }
        }

        List<TrackedDungeonGateSnapshot> standaloneGates = allGates.stream()
                .filter(gate -> !comboGateIds.contains(gate.gateId()))
                .toList();

        context.sendMessage(Message.raw("Tracked Gate-Type Activity").color("#8fd3ff"));
        context.sendMessage(Message.raw(
            "Standalone dungeon gates=" + standaloneGates.size()
                        + " | standalone waves=" + standaloneWaves.size()
                + " | dungeon+wave gate combos=" + combos.size()).color("#d9f0ff"));

        sendGateSection(context, standaloneGates);
        sendWaveSection(context, standaloneWaves);
        sendComboSection(context, combos);

        return CompletableFuture.completedFuture(null);
    }

    private static void sendGateSection(@Nonnull CommandContext context,
                                        @Nonnull List<TrackedDungeonGateSnapshot> gates) {
        context.sendMessage(Message.raw("Standalone Dungeon Gates").color("#f8d66d"));
        if (gates.isEmpty()) {
            context.sendMessage(Message.raw("- none").color("#ffcc66"));
            return;
        }

        Map<String, List<TrackedDungeonGateSnapshot>> grouped = new LinkedHashMap<>();
        for (TrackedDungeonGateSnapshot gate : gates) {
            grouped.computeIfAbsent(formatWorld(gate.worldName()), ignored -> new ArrayList<>()).add(gate);
        }

        for (Map.Entry<String, List<TrackedDungeonGateSnapshot>> entry : grouped.entrySet()) {
            context.sendMessage(Message.raw(String.format("- %s (%d)", entry.getKey(), entry.getValue().size()))
                    .color("#8fd3ff"));
            for (TrackedDungeonGateSnapshot gate : entry.getValue()) {
                context.sendMessage(Message.raw(String.format(
                        "  %-5s %-18s %s",
                        "[" + gate.rankTierId() + "]",
                        formatCoords(gate.x(), gate.y(), gate.z()),
                        shortenGateId(gate.gateId()))).color("#d9f0ff"));
            }
        }
    }

    private static void sendWaveSection(@Nonnull CommandContext context,
                                        @Nonnull List<TrackedWaveGateSnapshot> waves) {
        context.sendMessage(Message.raw("Standalone Wave Gates").color("#f8d66d"));
        if (waves.isEmpty()) {
            context.sendMessage(Message.raw("- none").color("#ffcc66"));
            return;
        }

        Map<String, List<TrackedWaveGateSnapshot>> grouped = new LinkedHashMap<>();
        for (TrackedWaveGateSnapshot wave : waves) {
            grouped.computeIfAbsent(formatWorld(wave.worldName()), ignored -> new ArrayList<>()).add(wave);
        }

        for (Map.Entry<String, List<TrackedWaveGateSnapshot>> entry : grouped.entrySet()) {
            context.sendMessage(Message.raw(String.format("- %s (%d)", entry.getKey(), entry.getValue().size()))
                    .color("#8fd3ff"));
            for (TrackedWaveGateSnapshot wave : entry.getValue()) {
                context.sendMessage(Message.raw(String.format(
                        "  %-5s %-8s %-18s owner=%s",
                        "[" + wave.rankTierId() + "]",
                        wave.stage().toUpperCase(),
                        formatCoords(wave.x(), wave.y(), wave.z()),
                        formatOwner(wave.ownerName()))).color("#d9f0ff"));
            }
        }
    }

    private static void sendComboSection(@Nonnull CommandContext context,
                                         @Nonnull List<TrackedWaveGateSnapshot> combos) {
        context.sendMessage(Message.raw("Dungeon Gate / Wave Gate Combos").color("#f8d66d"));
        if (combos.isEmpty()) {
            context.sendMessage(Message.raw("- none").color("#ffcc66"));
            return;
        }

        Map<String, List<TrackedWaveGateSnapshot>> grouped = new LinkedHashMap<>();
        for (TrackedWaveGateSnapshot combo : combos) {
            grouped.computeIfAbsent(formatWorld(combo.worldName()), ignored -> new ArrayList<>()).add(combo);
        }

        for (Map.Entry<String, List<TrackedWaveGateSnapshot>> entry : grouped.entrySet()) {
            context.sendMessage(Message.raw(String.format("- %s (%d)", entry.getKey(), entry.getValue().size()))
                    .color("#8fd3ff"));
            for (TrackedWaveGateSnapshot combo : entry.getValue()) {
                context.sendMessage(Message.raw(String.format(
                        "  %-5s %-8s %-18s gate=%s owner=%s",
                        "[" + combo.rankTierId() + "]",
                        combo.stage().toUpperCase(),
                        formatCoords(combo.x(), combo.y(), combo.z()),
                        shortenGateId(combo.linkedGateId()),
                        formatOwner(combo.ownerName()))).color("#d9f0ff"));
            }
        }
    }

    @Nonnull
    private static String formatWorld(@Nullable String worldName) {
        return worldName == null || worldName.isBlank() ? "<unknown world>" : worldName;
    }

    @Nonnull
    private static String formatCoords(@Nullable Integer x, @Nullable Integer y, @Nullable Integer z) {
        if (x == null || y == null || z == null) {
            return "(<untracked>)";
        }
        return String.format("(%4d, %3d, %4d)", x, y, z);
    }

    @Nonnull
    private static String formatOwner(@Nullable String ownerName) {
        return ownerName == null || ownerName.isBlank() ? "<unknown>" : ownerName;
    }

    @Nonnull
    private static String shortenGateId(@Nullable String gateId) {
        if (gateId == null || gateId.isBlank()) {
            return "<unknown>";
        }
        if (!gateId.startsWith("el_gate:")) {
            return gateId.length() <= 24 ? gateId : gateId.substring(0, 24);
        }

        String[] parts = gateId.split(":");
        if (parts.length < 6) {
            return gateId.length() <= 24 ? gateId : gateId.substring(0, 24);
        }

        String worldToken = parts[1];
        String shortWorldToken = worldToken.length() <= 8 ? worldToken : worldToken.substring(0, 8);
        return "el_gate:" + shortWorldToken + ":" + parts[parts.length - 3] + ":" + parts[parts.length - 2] + ":" + parts[parts.length - 1];
    }
}