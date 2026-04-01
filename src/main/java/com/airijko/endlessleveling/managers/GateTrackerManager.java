package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.gates.TrackedDungeonGateSnapshot;
import com.airijko.endlessleveling.api.gates.TrackedWaveGateSnapshot;
import com.airijko.endlessleveling.compatibility.EndlessLevelingCompatibility;
import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class GateTrackerManager {

    private static final Map<UUID, String> TRACKED_TARGETS_BY_PLAYER = new ConcurrentHashMap<>();

    private GateTrackerManager() {
    }

    @Nonnull
    public static List<GateTrackerEntry> listEntries() {
        List<TrackedDungeonGateSnapshot> dungeonGates = EndlessLevelingCompatibility.listTrackedDungeonGates();
        List<TrackedWaveGateSnapshot> standaloneWaves = EndlessLevelingCompatibility.listTrackedStandaloneWaves();
        List<TrackedWaveGateSnapshot> hybridWaves = EndlessLevelingCompatibility.listTrackedGateWaveCombos();

        Set<String> hybridGateIds = hybridWaves.stream()
                .map(TrackedWaveGateSnapshot::linkedGateId)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());

        List<GateTrackerEntrySeed> seeds = new ArrayList<>();

        for (TrackedDungeonGateSnapshot gate : dungeonGates) {
            if (gate == null || hybridGateIds.contains(gate.gateId())) {
                continue;
            }
            seeds.add(new GateTrackerEntrySeed(
                    GateEntryType.DUNGEON,
                    "dungeon:" + gate.gateId(),
                    resolveDungeonName(gate.blockId()),
                    normalizeRank(gate.rankTierId()),
                    normalizeWorld(gate.worldName()),
                    "OPEN",
                    gate.x(),
                    gate.y(),
                    gate.z()));
        }

        for (TrackedWaveGateSnapshot wave : standaloneWaves) {
            if (wave == null) {
                continue;
            }
            seeds.add(new GateTrackerEntrySeed(
                    GateEntryType.OUTBREAK,
                    buildWaveUniqueKey("outbreak", wave),
                    resolveOutbreakName(wave.kind()),
                    normalizeRank(wave.rankTierId()),
                    normalizeWorld(wave.worldName()),
                    normalizeStage(wave.stage()),
                    wave.x(),
                    wave.y(),
                    wave.z()));
        }

        for (TrackedWaveGateSnapshot hybrid : hybridWaves) {
            if (hybrid == null) {
                continue;
            }
            String gateId = hybrid.linkedGateId();
            seeds.add(new GateTrackerEntrySeed(
                    GateEntryType.HYBRID,
                    "hybrid:" + (gateId == null ? buildWaveUniqueKey("hybrid", hybrid) : gateId),
                    resolveHybridName(gateId),
                    normalizeRank(hybrid.rankTierId()),
                    normalizeWorld(hybrid.worldName()),
                    normalizeStage(hybrid.stage()),
                    hybrid.x(),
                    hybrid.y(),
                    hybrid.z()));
        }

        seeds.sort(Comparator
                .comparing((GateTrackerEntrySeed entry) -> entry.worldName == null ? "" : entry.worldName)
                .thenComparingInt(entry -> entry.type.order)
                .thenComparingInt(entry -> rankOrder(entry.rankLetter))
                .thenComparingInt(entry -> entry.y == null ? Integer.MIN_VALUE : entry.y)
                .thenComparingInt(entry -> entry.x == null ? Integer.MIN_VALUE : entry.x)
                .thenComparingInt(entry -> entry.z == null ? Integer.MIN_VALUE : entry.z)
                .thenComparing(entry -> entry.title == null ? "" : entry.title));

        Map<String, Integer> displayIdCollisions = new HashMap<>();
        List<GateTrackerEntry> entries = new ArrayList<>(seeds.size());
        for (GateTrackerEntrySeed seed : seeds) {
            String baseDisplayId = seed.type.prefix + "-" + shortenHash(seed.uniqueKey);
            int collisionIndex = displayIdCollisions.merge(baseDisplayId, 1, Integer::sum);
            String displayId = collisionIndex == 1 ? baseDisplayId : baseDisplayId + collisionIndex;
            entries.add(new GateTrackerEntry(
                    displayId,
                    seed.uniqueKey,
                    seed.type,
                    seed.title,
                    seed.rankLetter,
                    seed.worldName,
                    seed.status,
                    seed.x,
                    seed.y,
                    seed.z));
        }
        return entries;
    }

    @Nullable
    public static GateTrackerEntry findByDisplayId(@Nullable String displayId) {
        if (displayId == null || displayId.isBlank()) {
            return null;
        }

        String normalized = displayId.trim().toUpperCase(Locale.ROOT);
        for (GateTrackerEntry entry : listEntries()) {
            if (entry.displayId().equalsIgnoreCase(normalized)) {
                return entry;
            }
        }
        return null;
    }

    @Nullable
    public static GateTrackerEntry getTrackedEntry(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return null;
        }

        String uniqueKey = TRACKED_TARGETS_BY_PLAYER.get(playerUuid);
        if (uniqueKey == null || uniqueKey.isBlank()) {
            return null;
        }

        for (GateTrackerEntry entry : listEntries()) {
            if (entry.uniqueKey().equals(uniqueKey)) {
                return entry;
            }
        }

        TRACKED_TARGETS_BY_PLAYER.remove(playerUuid);
        return null;
    }

    public static void setTrackedEntry(@Nonnull UUID playerUuid, @Nonnull GateTrackerEntry entry) {
        TRACKED_TARGETS_BY_PLAYER.put(playerUuid, entry.uniqueKey());
    }

    public static void clearTrackedEntry(@Nullable UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        TRACKED_TARGETS_BY_PLAYER.remove(playerUuid);
    }

    public static boolean hasTrackedEntry(@Nullable UUID playerUuid) {
        return playerUuid != null && TRACKED_TARGETS_BY_PLAYER.containsKey(playerUuid);
    }

    public static void shutdown() {
        TRACKED_TARGETS_BY_PLAYER.clear();
    }

    @Nonnull
    private static String buildWaveUniqueKey(@Nonnull String prefix, @Nonnull TrackedWaveGateSnapshot wave) {
        StringBuilder key = new StringBuilder(prefix);
        key.append(':');
        if (wave.ownerUuid() != null) {
            key.append(wave.ownerUuid());
        }
        key.append(':').append(normalizeWorld(wave.worldName()));
        key.append(':').append(wave.x()).append(':').append(wave.y()).append(':').append(wave.z());
        key.append(':').append(normalizeRank(wave.rankTierId()));
        return key.toString();
    }

    @Nonnull
    private static String resolveDungeonName(@Nullable String blockId) {
        String resolved = PortalLeveledInstanceRouter.resolveGateDisplayNameForBlockId(blockId);
        if (resolved != null && !resolved.isBlank()) {
            return resolved;
        }
        return prettifyBlockId(blockId);
    }

    @Nonnull
    private static String resolveHybridName(@Nullable String gateId) {
        String blockId = gateId == null ? null : NaturalPortalGateManager.resolveGateBlockId(gateId);
        String resolved = resolveDungeonName(blockId);
        return resolved.isBlank() ? "Hybrid Gate" : resolved;
    }

    @Nonnull
    private static String resolveOutbreakName(@Nullable String kind) {
        if (kind != null && kind.equalsIgnoreCase("auto-wave")) {
            return "Natural Outbreak";
        }
        return "Outbreak Rift";
    }

    @Nonnull
    private static String normalizeStage(@Nullable String stage) {
        if (stage == null || stage.isBlank()) {
            return "ACTIVE";
        }
        return stage.trim().toUpperCase(Locale.ROOT).replace('_', ' ');
    }

    @Nonnull
    private static String normalizeRank(@Nullable String rankTierId) {
        if (rankTierId == null || rankTierId.isBlank()) {
            return "E";
        }
        return rankTierId.trim().toUpperCase(Locale.ROOT);
    }

    @Nonnull
    private static String normalizeWorld(@Nullable String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return "<unknown world>";
        }
        return worldName;
    }

    @Nonnull
    private static String prettifyBlockId(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "Unknown Gate";
        }

        String raw = blockId;
        int rankIndex = raw.indexOf("_Rank");
        if (rankIndex > 0) {
            raw = raw.substring(0, rankIndex);
        }
        int separatorIndex = raw.indexOf(':');
        if (separatorIndex >= 0 && separatorIndex + 1 < raw.length()) {
            raw = raw.substring(separatorIndex + 1);
        }

        String[] parts = raw.split("[_-]");
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                text.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return text.isEmpty() ? "Unknown Gate" : text.toString();
    }

    private static int rankOrder(@Nullable String rankLetter) {
        if (rankLetter == null) {
            return Integer.MAX_VALUE;
        }
        return switch (rankLetter.toUpperCase(Locale.ROOT)) {
            case "S" -> 0;
            case "A" -> 1;
            case "B" -> 2;
            case "C" -> 3;
            case "D" -> 4;
            default -> 5;
        };
    }

    @Nonnull
    private static String shortenHash(@Nonnull String key) {
        long value = Integer.toUnsignedLong(key.hashCode());
        String base36 = Long.toString(value, 36).toUpperCase(Locale.ROOT);
        if (base36.length() >= 4) {
            return base36.substring(0, 4);
        }
        return "0".repeat(4 - base36.length()) + base36;
    }

    public enum GateEntryType {
        DUNGEON("D", 0, "Dungeon"),
        OUTBREAK("O", 1, "Outbreak"),
        HYBRID("H", 2, "Hybrid");

        private final String prefix;
        private final int order;
        private final String label;

        GateEntryType(String prefix, int order, String label) {
            this.prefix = prefix;
            this.order = order;
            this.label = label;
        }

        @Nonnull
        public String label() {
            return label;
        }
    }

    public record GateTrackerEntry(
            @Nonnull String displayId,
            @Nonnull String uniqueKey,
            @Nonnull GateEntryType type,
            @Nonnull String title,
            @Nonnull String rankLetter,
            @Nonnull String worldName,
            @Nonnull String status,
            @Nullable Integer x,
            @Nullable Integer y,
            @Nullable Integer z) {
    }

    private record GateTrackerEntrySeed(
            @Nonnull GateEntryType type,
            @Nonnull String uniqueKey,
            @Nonnull String title,
            @Nonnull String rankLetter,
            @Nonnull String worldName,
            @Nonnull String status,
            @Nullable Integer x,
            @Nullable Integer y,
            @Nullable Integer z) {
    }
}