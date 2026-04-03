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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class GateTrackerManager {

    private static final Map<UUID, String> TRACKED_TARGETS_BY_PLAYER = new ConcurrentHashMap<>();
    /** Records the first wall-clock time (ms) that each gate uniqueKey was seen in a list snapshot. */
    private static final Map<String, Long> GATE_FIRST_SEEN = new ConcurrentHashMap<>();

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
                    gate.gateId(),
                    null,
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
                    null,
                    wave.ownerUuid(),
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
                    gateId,
                    hybrid.ownerUuid(),
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

        long now = System.currentTimeMillis();
        Map<String, Integer> displayIdCollisions = new HashMap<>();
        List<GateTrackerEntry> entries = new ArrayList<>(seeds.size());
        for (GateTrackerEntrySeed seed : seeds) {
            String baseDisplayId = seed.type.prefix + "-" + shortenHash(seed.uniqueKey);
            int collisionIndex = displayIdCollisions.merge(baseDisplayId, 1, Integer::sum);
            String displayId = collisionIndex == 1 ? baseDisplayId : baseDisplayId + collisionIndex;
            long firstSeen = GATE_FIRST_SEEN.computeIfAbsent(seed.uniqueKey, k -> now);
            MobWaveManager.LinkedGateWaveTimingSnapshot linkedTiming = seed.gateIdentity == null
                ? null
                : MobWaveManager.getLinkedGateWaveTimingSnapshot(seed.gateIdentity);
            long opensAt = resolveOpensAtEpochMillis(seed, firstSeen, linkedTiming);
            long expiresAt = resolveExpiresAtEpochMillis(seed, opensAt, linkedTiming);
            entries.add(new GateTrackerEntry(
                    displayId,
                    seed.uniqueKey,
                    seed.gateIdentity,
                    seed.type,
                    seed.title,
                    seed.rankLetter,
                    seed.worldName,
                resolveStatus(seed, now, linkedTiming),
                    seed.x,
                    seed.y,
                    seed.z,
                firstSeen,
                opensAt,
                expiresAt));
        }
        // Prune timestamps for gates that are no longer in the live snapshot.
        if (entries.isEmpty()) {
            GATE_FIRST_SEEN.clear();
        } else {
            Set<String> activeKeys = entries.stream()
                    .map(GateTrackerEntry::uniqueKey)
                    .collect(Collectors.toSet());
            GATE_FIRST_SEEN.keySet().retainAll(activeKeys);
        }
        return entries;
    }

    @Nullable
    public static GateTrackerEntry findByIndexOrDisplayId(@Nullable String idOrIndex) {
        if (idOrIndex == null || idOrIndex.isBlank()) {
            return null;
        }

        String trimmed = idOrIndex.trim();
        Integer parsedIndex = parseListIndex(trimmed);
        List<GateTrackerEntry> entries = listEntries();
        if (parsedIndex != null && parsedIndex >= 1 && parsedIndex <= entries.size()) {
            return entries.get(parsedIndex - 1);
        }

        String normalized = trimmed.toUpperCase(Locale.ROOT);
        for (GateTrackerEntry entry : entries) {
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

    @Nullable
    public static Integer findListIndexForGateIdentity(@Nullable String gateIdentity) {
        if (gateIdentity == null || gateIdentity.isBlank()) {
            return null;
        }
        String canonical = gateIdentity.startsWith("el_gate:") ? gateIdentity : "el_gate:" + gateIdentity;
        List<GateTrackerEntry> entries = listEntries();
        for (int i = 0; i < entries.size(); i++) {
            GateTrackerEntry entry = entries.get(i);
            if (canonical.equals(entry.gateIdentity())) {
                return i + 1;
            }
        }
        return null;
    }

    @Nullable
    public static Integer findListIndexByTypeAndLocation(@Nonnull GateEntryType type,
                                                         @Nullable String worldName,
                                                         @Nullable Integer x,
                                                         @Nullable Integer y,
                                                         @Nullable Integer z) {
        if (worldName == null || x == null || y == null || z == null) {
            return null;
        }
        List<GateTrackerEntry> entries = listEntries();
        for (int i = 0; i < entries.size(); i++) {
            GateTrackerEntry entry = entries.get(i);
            if (entry.type() != type) {
                continue;
            }
            if (!worldName.equals(entry.worldName())) {
                continue;
            }
            if (!x.equals(entry.x()) || !y.equals(entry.y()) || !z.equals(entry.z())) {
                continue;
            }
            return i + 1;
        }
        return null;
    }

    public static void shutdown() {
        TRACKED_TARGETS_BY_PLAYER.clear();
        GATE_FIRST_SEEN.clear();
    }

    @Nullable
    private static Integer parseListIndex(@Nonnull String rawInput) {
        String candidate = rawInput.startsWith("#") ? rawInput.substring(1) : rawInput;
        if (candidate.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(candidate);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
            return formatGateTitle(resolved);
        }
        return formatGateTitle(prettifyBlockId(blockId));
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
    private static String resolveStatus(@Nonnull GateTrackerEntrySeed seed,
                                        long nowMillis,
                                        @Nullable MobWaveManager.LinkedGateWaveTimingSnapshot linkedTiming) {
        if (linkedTiming != null) {
            return formatLinkedStatus(linkedTiming, nowMillis);
        }

        String normalized = normalizeStage(seed.status);
        if ("ACTIVE".equals(normalized) || "OPEN".equals(normalized)) {
            return "OPENED";
        }
        if ("PENDING".equals(normalized)) {
            long opensAt = resolveStandalonePendingOpensAtEpochMillis(seed);
            if (opensAt > nowMillis) {
                long remainingSeconds = Math.max(0L, (opensAt - nowMillis + 999L) / 1000L);
                return "CLOSED (opens in " + remainingSeconds + "s)";
            }
            return "CLOSED (opening)";
        }
        return normalized;
    }

    @Nonnull
    private static String formatLinkedStatus(@Nonnull MobWaveManager.LinkedGateWaveTimingSnapshot linkedTiming,
                                             long nowMillis) {
        String stage = normalizeStage(linkedTiming.stage());
        if ("PENDING".equals(stage)) {
            long opensAt = linkedTiming.opensAtEpochMillis();
            if (opensAt > nowMillis) {
                long remainingSeconds = Math.max(0L, (opensAt - nowMillis + 999L) / 1000L);
                return "CLOSED (opens in " + remainingSeconds + "s)";
            }
            return "CLOSED (opening)";
        }
        if ("ACTIVE".equals(stage)) {
            return "OPENED";
        }
        return stage;
    }

    private static long resolveOpensAtEpochMillis(@Nonnull GateTrackerEntrySeed seed,
                                                  long firstSeenMillis,
                                                  @Nullable MobWaveManager.LinkedGateWaveTimingSnapshot linkedTiming) {
        if (linkedTiming != null && "PENDING".equals(normalizeStage(linkedTiming.stage()))) {
            long opensAt = linkedTiming.opensAtEpochMillis();
            if (opensAt > 0L) {
                return opensAt;
            }
        }

        if (linkedTiming != null) {
            long openedAt = linkedTiming.openedAtEpochMillis();
            if (openedAt > 0L) {
                return openedAt;
            }
        }

        long standalonePendingOpensAt = resolveStandalonePendingOpensAtEpochMillis(seed);
        if (standalonePendingOpensAt > 0L) {
            return standalonePendingOpensAt;
        }

        return firstSeenMillis;
    }

    private static long resolveStandalonePendingOpensAtEpochMillis(@Nonnull GateTrackerEntrySeed seed) {
        if (seed.ownerUuid == null) {
            return 0L;
        }
        if (!"PENDING".equals(normalizeStage(seed.status))) {
            return 0L;
        }
        return MobWaveManager.getPendingNaturalWaveOpensAtEpochMillis(seed.ownerUuid);
    }

    private static long resolveExpiresAtEpochMillis(@Nonnull GateTrackerEntrySeed seed,
                                                    long opensAtEpochMillis,
                                                    @Nullable MobWaveManager.LinkedGateWaveTimingSnapshot linkedTiming) {
        if (linkedTiming != null && linkedTiming.expiresAtEpochMillis() > 0L) {
            return linkedTiming.expiresAtEpochMillis();
        }

        if ((seed.type == GateEntryType.DUNGEON || seed.type == GateEntryType.HYBRID) && opensAtEpochMillis > 0L) {
            return opensAtEpochMillis + NaturalPortalGateManager.getConfiguredGateLifetimeMillis();
        }

        return 0L;
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

        return formatGateTitle(raw);
    }

    @Nonnull
    private static String formatGateTitle(@Nullable String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "Unknown Gate";
        }

        String normalized = rawTitle.trim()
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
                .replaceAll("(?i)^EL\\b\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.isBlank()) {
            return "Unknown Gate";
        }

        StringBuilder out = new StringBuilder();
        for (String word : normalized.split(" ")) {
            if (word.isBlank() || word.equalsIgnoreCase("EL")) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(formatTitleWord(word));
        }

        return out.isEmpty() ? "Unknown Gate" : out.toString();
    }

    @Nonnull
    private static String formatTitleWord(@Nonnull String word) {
        if (word.matches("^[A-Za-z]\\d+$")) {
            return word.toUpperCase(Locale.ROOT);
        }
        if (word.matches("^[A-Z0-9]+$") && word.length() <= 4) {
            return word;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
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
            @Nullable String gateIdentity,
            @Nonnull GateEntryType type,
            @Nonnull String title,
            @Nonnull String rankLetter,
            @Nonnull String worldName,
            @Nonnull String status,
            @Nullable Integer x,
            @Nullable Integer y,
            @Nullable Integer z,
                long firstSeenMillis,
                long opensAtEpochMillis,
                long expiresAtEpochMillis) {
    }

    private record GateTrackerEntrySeed(
            @Nonnull GateEntryType type,
            @Nonnull String uniqueKey,
            @Nullable String gateIdentity,
            @Nullable UUID ownerUuid,
            @Nonnull String title,
            @Nonnull String rankLetter,
            @Nonnull String worldName,
            @Nonnull String status,
            @Nullable Integer x,
            @Nullable Integer y,
            @Nullable Integer z) {
    }
}