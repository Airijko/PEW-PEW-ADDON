package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.managers.MobWaveManager;
import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
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
        super("track", "List tracked gates, waves, and gate-wave combos");
        this.addAliases("tracks", "tracking", "listtrack");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        List<NaturalPortalGateManager.TrackedGateSnapshot> allGates = NaturalPortalGateManager.listTrackedGates();
        List<MobWaveManager.TrackedWaveSnapshot> standaloneWaves = MobWaveManager.listTrackedStandaloneWaves();
        List<MobWaveManager.TrackedWaveSnapshot> combos = MobWaveManager.listTrackedGateWaveCombos();

        Set<String> comboGateIds = new HashSet<>();
        for (MobWaveManager.TrackedWaveSnapshot combo : combos) {
            if (combo.linkedGateId() != null && !combo.linkedGateId().isBlank()) {
                comboGateIds.add(combo.linkedGateId());
            }
        }

        List<NaturalPortalGateManager.TrackedGateSnapshot> standaloneGates = allGates.stream()
                .filter(gate -> !comboGateIds.contains(gate.gateId()))
                .toList();

        context.sendMessage(Message.raw("Tracked Gate Activity").color("#8fd3ff"));
        context.sendMessage(Message.raw(
                "Standalone gates=" + standaloneGates.size()
                        + " | standalone waves=" + standaloneWaves.size()
                        + " | gate+wave combos=" + combos.size()).color("#d9f0ff"));

        sendGateSection(context, standaloneGates);
        sendWaveSection(context, standaloneWaves);
        sendComboSection(context, combos);

        return CompletableFuture.completedFuture(null);
    }

    private static void sendGateSection(@Nonnull CommandContext context,
                                        @Nonnull List<NaturalPortalGateManager.TrackedGateSnapshot> gates) {
        context.sendMessage(Message.raw("Standalone Gates").color("#f8d66d"));
        if (gates.isEmpty()) {
            context.sendMessage(Message.raw("- none").color("#ffcc66"));
            return;
        }

        Map<String, List<NaturalPortalGateManager.TrackedGateSnapshot>> grouped = new LinkedHashMap<>();
        for (NaturalPortalGateManager.TrackedGateSnapshot gate : gates) {
            grouped.computeIfAbsent(formatWorld(gate.worldName()), ignored -> new ArrayList<>()).add(gate);
        }

        for (Map.Entry<String, List<NaturalPortalGateManager.TrackedGateSnapshot>> entry : grouped.entrySet()) {
            context.sendMessage(Message.raw(String.format("- %s (%d)", entry.getKey(), entry.getValue().size()))
                    .color("#8fd3ff"));
            for (NaturalPortalGateManager.TrackedGateSnapshot gate : entry.getValue()) {
                context.sendMessage(Message.raw(String.format(
                        "  %-5s %-18s %s",
                        "[" + gate.rankTier().letter() + "]",
                        formatCoords(gate.x(), gate.y(), gate.z()),
                        shortenGateId(gate.gateId()))).color("#d9f0ff"));
            }
        }
    }

    private static void sendWaveSection(@Nonnull CommandContext context,
                                        @Nonnull List<MobWaveManager.TrackedWaveSnapshot> waves) {
        context.sendMessage(Message.raw("Standalone Waves").color("#f8d66d"));
        if (waves.isEmpty()) {
            context.sendMessage(Message.raw("- none").color("#ffcc66"));
            return;
        }

        Map<String, List<MobWaveManager.TrackedWaveSnapshot>> grouped = new LinkedHashMap<>();
        for (MobWaveManager.TrackedWaveSnapshot wave : waves) {
            grouped.computeIfAbsent(formatWorld(wave.worldName()), ignored -> new ArrayList<>()).add(wave);
        }

        for (Map.Entry<String, List<MobWaveManager.TrackedWaveSnapshot>> entry : grouped.entrySet()) {
            context.sendMessage(Message.raw(String.format("- %s (%d)", entry.getKey(), entry.getValue().size()))
                    .color("#8fd3ff"));
            for (MobWaveManager.TrackedWaveSnapshot wave : entry.getValue()) {
                context.sendMessage(Message.raw(String.format(
                        "  %-5s %-8s %-18s owner=%s",
                        "[" + wave.rankTier().letter() + "]",
                        wave.stage().toUpperCase(),
                        formatCoords(wave.x(), wave.y(), wave.z()),
                        formatOwner(wave.ownerName()))).color("#d9f0ff"));
            }
        }
    }

    private static void sendComboSection(@Nonnull CommandContext context,
                                         @Nonnull List<MobWaveManager.TrackedWaveSnapshot> combos) {
        context.sendMessage(Message.raw("Gate/Wave Combos").color("#f8d66d"));
        if (combos.isEmpty()) {
            context.sendMessage(Message.raw("- none").color("#ffcc66"));
            return;
        }

        Map<String, List<MobWaveManager.TrackedWaveSnapshot>> grouped = new LinkedHashMap<>();
        for (MobWaveManager.TrackedWaveSnapshot combo : combos) {
            grouped.computeIfAbsent(formatWorld(combo.worldName()), ignored -> new ArrayList<>()).add(combo);
        }

        for (Map.Entry<String, List<MobWaveManager.TrackedWaveSnapshot>> entry : grouped.entrySet()) {
            context.sendMessage(Message.raw(String.format("- %s (%d)", entry.getKey(), entry.getValue().size()))
                    .color("#8fd3ff"));
            for (MobWaveManager.TrackedWaveSnapshot combo : entry.getValue()) {
                context.sendMessage(Message.raw(String.format(
                        "  %-5s %-8s %-18s gate=%s owner=%s",
                        "[" + combo.rankTier().letter() + "]",
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