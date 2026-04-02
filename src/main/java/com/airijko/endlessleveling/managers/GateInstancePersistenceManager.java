package com.airijko.endlessleveling.managers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages persistence of gate-to-instance mappings across server restarts.
 * Saves gate instance IDs and level configurations when shutting down,
 * and restores them on startup to maintain dungeon continuity.
 */
public final class GateInstancePersistenceManager {

    private static final String PERSISTENCE_FILENAME = "gate_instances.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    private static File persistenceFile;
    private static final Map<String, StoredGateInstance> SAVED_INSTANCES = new HashMap<>();
    private static int NEXT_ENTRY_ID = 1;

    private GateInstancePersistenceManager() {
    }

    public static void initialize() {
        File dataFolder = PluginManager.MODS_PATH.resolve("EndlessLevelingAddon").toFile();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        persistenceFile = new File(dataFolder, PERSISTENCE_FILENAME);
        loadFromDisk();
    }

    /**
     * Saves current gate instance mappings to disk before server shutdown.
     * The caller (PortalLeveledInstanceRouter) builds the pre-populated list so
     * no reflection against private router types is needed here.
     */
    public static void saveGateInstances(@Nonnull List<StoredGateInstance> instances) {
        try {
            Map<String, StoredGateInstance> existing = new HashMap<>(SAVED_INSTANCES);
            SAVED_INSTANCES.clear();
            for (StoredGateInstance inst : instances) {
            StoredGateInstance existingEntry = existing.get(inst.gateKey);
            int entryId = inst.entryId > 0
                ? inst.entryId
                : (existingEntry != null && existingEntry.entryId > 0 ? existingEntry.entryId : NEXT_ENTRY_ID++);

            if (entryId >= NEXT_ENTRY_ID) {
                NEXT_ENTRY_ID = entryId + 1;
            }

            long savedTimestamp = inst.savedTimestamp > 0L
                ? inst.savedTimestamp
                : (existingEntry != null ? existingEntry.savedTimestamp : System.currentTimeMillis());

            List<String> deathLockedUuids = inst.deathLockedPlayerUuids.isEmpty() && existingEntry != null
                ? existingEntry.deathLockedPlayerUuids
                : inst.deathLockedPlayerUuids;

            StoredGateInstance normalized = new StoredGateInstance(
                entryId,
                inst.gateKey,
                inst.instanceWorldName,
                inst.minLevel,
                inst.maxLevel,
                inst.bossLevel,
                inst.rankLetter,
                inst.blockId,
                deathLockedUuids,
                inst.coordinateOnly);
            normalized.savedTimestamp = savedTimestamp;
            SAVED_INSTANCES.put(normalized.gateKey, normalized);
            }

            for (StoredGateInstance existingEntry : existing.values()) {
                if (!existingEntry.coordinateOnly) {
                    continue;
                }
                SAVED_INSTANCES.putIfAbsent(existingEntry.gateKey, existingEntry);
            }
            writeToDisk();
            System.out.println("[ELPortal-Persistence] Saved " + SAVED_INSTANCES.size()
                    + " gate instance mappings to " + persistenceFile.getName());
        } catch (Exception ex) {
            System.err.println("[ELPortal-Persistence] Failed to save gate instances: " + ex.getMessage());
        }
    }

    /**
     * Upserts a single gate instance entry immediately to disk (write-through).
     * Preserves the entryId and savedTimestamp from any previously-persisted entry.
     */
    public static void upsertGateInstance(@Nonnull StoredGateInstance instance) {
        StoredGateInstance existing = SAVED_INSTANCES.get(instance.gateKey);
        int entryId = instance.entryId > 0
                ? instance.entryId
                : (existing != null && existing.entryId > 0 ? existing.entryId : NEXT_ENTRY_ID++);
        if (entryId >= NEXT_ENTRY_ID) {
            NEXT_ENTRY_ID = entryId + 1;
        }
        long savedTimestamp = existing != null && existing.savedTimestamp > 0L
                ? existing.savedTimestamp
                : System.currentTimeMillis();
        StoredGateInstance normalized = new StoredGateInstance(
                entryId,
                instance.gateKey,
                instance.instanceWorldName,
                instance.minLevel,
                instance.maxLevel,
                instance.bossLevel,
                instance.rankLetter,
                instance.blockId,
            instance.deathLockedPlayerUuids,
            instance.coordinateOnly);
        normalized.savedTimestamp = savedTimestamp;
        SAVED_INSTANCES.put(normalized.gateKey, normalized);
        try {
            writeToDisk();
        } catch (Exception ex) {
            System.err.println("[ELPortal-Persistence] Failed to write after upsert for " + instance.gateKey
                    + ": " + ex.getMessage());
        }
    }

    /**
     * Retrieves all saved gate instance mappings to restore on startup.
     */
    @Nonnull
    public static Map<String, StoredGateInstance> getSavedInstances() {
        return new HashMap<>(SAVED_INSTANCES);
    }

    @Nonnull
    public static List<StoredGateInstance> listSavedInstancesById() {
        List<StoredGateInstance> all = new ArrayList<>(SAVED_INSTANCES.values());
        all.sort(Comparator.comparingInt(value -> value.entryId));
        return all;
    }

    /**
     * Checks if a specific gate has a saved instance.
     */
    public static boolean hasSavedInstance(@Nonnull String gateKey) {
        return SAVED_INSTANCES.containsKey(gateKey);
    }

    /**
     * Gets a single saved instance mapping.
     */
    @Nullable
    public static StoredGateInstance getSavedInstance(@Nonnull String gateKey) {
        return SAVED_INSTANCES.get(gateKey);
    }

    @Nullable
    public static StoredGateInstance getSavedInstanceById(int entryId) {
        if (entryId <= 0) {
            return null;
        }

        for (StoredGateInstance instance : SAVED_INSTANCES.values()) {
            if (instance.entryId == entryId) {
                return instance;
            }
        }
        return null;
    }

    /**
     * Removes a single gate instance mapping by gate key and persists to disk.
     * Tries both the canonical ("el_gate:...") and legacy ("<uuid>:...") forms.
     */
    public static void removeGateInstance(@Nonnull String gateKey) {
        boolean removed = SAVED_INSTANCES.remove(gateKey) != null;
        // Also try the alternate key form so callers don't have to pre-canonicalize.
        String altKey = gateKey.startsWith("el_gate:")
                ? gateKey.substring("el_gate:".length())
                : "el_gate:" + gateKey;
        removed |= SAVED_INSTANCES.remove(altKey) != null;
        try {
            writeToDisk();
            if (removed) {
                System.out.println("[ELPortal-Persistence] Removed gate instance mapping for " + gateKey);
            }
        } catch (Exception ex) {
            System.err.println("[ELPortal-Persistence] Failed to persist after removing gate " + gateKey
                    + ": " + ex.getMessage());
        }
    }

    public static boolean removeGateInstanceById(int entryId) {
        StoredGateInstance instance = getSavedInstanceById(entryId);
        if (instance == null) {
            return false;
        }

        removeGateInstance(instance.gateKey);
        return true;
    }

    /**
     * Clears all saved instances (useful for complete reset).
     */
    public static void clearSavedInstances() {
        SAVED_INSTANCES.clear();
        try {
            writeToDisk();
            System.out.println("[ELPortal-Persistence] Cleared all saved gate instances");
        } catch (Exception ex) {
            System.err.println("[ELPortal-Persistence] Failed to clear saved instances: " + ex.getMessage());
        }
    }

    private static void writeToDisk() throws Exception {
        JsonObject root = new JsonObject();
        JsonArray gatesArray = new JsonArray();

        for (StoredGateInstance instance : SAVED_INSTANCES.values()) {
            JsonObject gateObj = new JsonObject();
            gateObj.addProperty("id", instance.entryId);
            gateObj.addProperty("gateKey", instance.gateKey);
            gateObj.addProperty("instanceWorldName", instance.instanceWorldName);
            gateObj.addProperty("minLevel", instance.minLevel);
            gateObj.addProperty("maxLevel", instance.maxLevel);
            gateObj.addProperty("bossLevel", instance.bossLevel);
            gateObj.addProperty("rankLetter", instance.rankLetter);
            gateObj.addProperty("blockId", instance.blockId);
            gateObj.addProperty("savedTimestamp", instance.savedTimestamp);
            gateObj.addProperty("coordinateOnly", instance.coordinateOnly);

            JsonArray deathLocks = new JsonArray();
            for (String playerUuid : instance.deathLockedPlayerUuids) {
                deathLocks.add(playerUuid);
            }
            gateObj.add("deathLockedPlayerUuids", deathLocks);
            gatesArray.add(gateObj);
        }

        root.add("gateInstances", gatesArray);
        root.addProperty("lastSaved", System.currentTimeMillis());

        try (FileWriter writer = new FileWriter(persistenceFile)) {
            GSON.toJson(root, writer);
        }
    }

    private static void loadFromDisk() {
        if (!persistenceFile.exists()) {
            System.out.println("[ELPortal-Persistence] No persistence file found at startup");
            return;
        }

        try (FileReader reader = new FileReader(persistenceFile)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("gateInstances")) {
                System.err.println("[ELPortal-Persistence] Invalid persistence file format");
                return;
            }

            JsonArray gatesArray = root.getAsJsonArray("gateInstances");
            int maxEntryId = 0;
            Set<Integer> usedIds = new HashSet<>();
            for (var element : gatesArray) {
                JsonObject gateObj = element.getAsJsonObject();
                String gateKey = gateObj.get("gateKey").getAsString();
                String instanceWorldName = gateObj.get("instanceWorldName").getAsString();
                int minLevel = gateObj.get("minLevel").getAsInt();
                int maxLevel = gateObj.get("maxLevel").getAsInt();
                long savedTimestamp = gateObj.has("savedTimestamp") ?
                        gateObj.get("savedTimestamp").getAsLong() : 0;
                int entryId = gateObj.has("id") ? gateObj.get("id").getAsInt() : 0;
                if (entryId <= 0 || usedIds.contains(entryId)) {
                    entryId = Math.max(1, maxEntryId + 1);
                }
                usedIds.add(entryId);
                maxEntryId = Math.max(maxEntryId, entryId);
                // v2 fields — default gracefully for files written before this version
                int bossLevel = gateObj.has("bossLevel") ? gateObj.get("bossLevel").getAsInt() : maxLevel;
                String rankLetter = gateObj.has("rankLetter") ? gateObj.get("rankLetter").getAsString() : "E";
                String blockId = gateObj.has("blockId") ? gateObj.get("blockId").getAsString() : "";
                List<String> deathLockedPlayerUuids = new ArrayList<>();
                if (gateObj.has("deathLockedPlayerUuids") && gateObj.get("deathLockedPlayerUuids").isJsonArray()) {
                    JsonArray deathLocks = gateObj.getAsJsonArray("deathLockedPlayerUuids");
                    for (var lockElement : deathLocks) {
                        if (!lockElement.isJsonPrimitive()) {
                            continue;
                        }
                        String playerUuid = lockElement.getAsString();
                        if (!playerUuid.isBlank()) {
                            deathLockedPlayerUuids.add(playerUuid);
                        }
                    }
                }

                StoredGateInstance stored = new StoredGateInstance(
                        entryId,
                        gateKey,
                        instanceWorldName,
                        minLevel,
                        maxLevel,
                        bossLevel,
                        rankLetter,
                        blockId,
                    deathLockedPlayerUuids,
                    gateObj.has("coordinateOnly") && gateObj.get("coordinateOnly").getAsBoolean());
                stored.savedTimestamp = savedTimestamp;
                SAVED_INSTANCES.put(gateKey, stored);
            }

            NEXT_ENTRY_ID = Math.max(1, maxEntryId + 1);

            System.out.println("[ELPortal-Persistence] Loaded " + SAVED_INSTANCES.size() + " saved gate instance mappings");
        } catch (Exception ex) {
            System.err.println("[ELPortal-Persistence] Failed to load gate instances: " + ex.getMessage());
        }
    }

    public static void shutdown() {
        // Cleanup handled by saveGateInstances() call before shutdown
    }

    /**
     * Represents a stored gate instance mapping with associated level range.
     */
    public static class StoredGateInstance {
        public final int entryId;
        public final String gateKey;
        public final String instanceWorldName;
        public final int minLevel;
        public final int maxLevel;
        /** Absolute boss level (minLevel ≤ maxLevel ≤ bossLevel in normal configurations). */
        public final int bossLevel;
        /** Gate rank letter: E/D/C/B/A/S. */
        public final String rankLetter;
        /** Block type ID used for this gate (with rank suffix, e.g. "EL_MajorDungeonPortal_D01_RankB"). */
        public final String blockId;
        /** UUID strings of players locked out from this gate instance due to death. */
        public final List<String> deathLockedPlayerUuids;
        /** True when this entry exists only to persist gate coordinates before instance pairing. */
        public final boolean coordinateOnly;
        public long savedTimestamp;

        public StoredGateInstance(String gateKey, String instanceWorldName,
                                  int minLevel, int maxLevel,
                                  int bossLevel, String rankLetter, String blockId) {
            this(0, gateKey, instanceWorldName, minLevel, maxLevel, bossLevel, rankLetter, blockId, List.of(), false);
        }

        public StoredGateInstance(String gateKey,
                                  String instanceWorldName,
                                  int minLevel,
                                  int maxLevel,
                                  int bossLevel,
                                  String rankLetter,
                                  String blockId,
                                  @Nonnull List<String> deathLockedPlayerUuids) {
            this(0, gateKey, instanceWorldName, minLevel, maxLevel, bossLevel, rankLetter, blockId, deathLockedPlayerUuids, false);
        }

        public StoredGateInstance(String gateKey,
                                  String instanceWorldName,
                                  int minLevel,
                                  int maxLevel,
                                  int bossLevel,
                                  String rankLetter,
                                  String blockId,
                                  @Nonnull List<String> deathLockedPlayerUuids,
                                  boolean coordinateOnly) {
            this(0, gateKey, instanceWorldName, minLevel, maxLevel, bossLevel, rankLetter, blockId, deathLockedPlayerUuids, coordinateOnly);
        }

        public StoredGateInstance(int entryId,
                                  String gateKey,
                                  String instanceWorldName,
                                  int minLevel,
                                  int maxLevel,
                                  int bossLevel,
                                  String rankLetter,
                                  String blockId,
                                  @Nonnull List<String> deathLockedPlayerUuids,
                                  boolean coordinateOnly) {
            this.entryId = entryId;
            this.gateKey = gateKey;
            this.instanceWorldName = instanceWorldName;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.bossLevel = bossLevel;
            this.rankLetter = rankLetter != null ? rankLetter : "E";
            this.blockId = blockId != null ? blockId : "";
            this.deathLockedPlayerUuids = List.copyOf(deathLockedPlayerUuids);
            this.coordinateOnly = coordinateOnly;
            this.savedTimestamp = System.currentTimeMillis();
        }
    }
}
