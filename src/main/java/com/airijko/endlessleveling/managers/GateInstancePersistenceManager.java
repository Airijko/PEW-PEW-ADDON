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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            SAVED_INSTANCES.clear();
            for (StoredGateInstance inst : instances) {
                SAVED_INSTANCES.put(inst.gateKey, inst);
            }
            writeToDisk();
            System.out.println("[ELPortal-Persistence] Saved " + SAVED_INSTANCES.size()
                    + " gate instance mappings to " + persistenceFile.getName());
        } catch (Exception ex) {
            System.err.println("[ELPortal-Persistence] Failed to save gate instances: " + ex.getMessage());
        }
    }

    /**
     * Retrieves all saved gate instance mappings to restore on startup.
     */
    @Nonnull
    public static Map<String, StoredGateInstance> getSavedInstances() {
        return new HashMap<>(SAVED_INSTANCES);
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
            gateObj.addProperty("gateKey", instance.gateKey);
            gateObj.addProperty("instanceWorldName", instance.instanceWorldName);
            gateObj.addProperty("minLevel", instance.minLevel);
            gateObj.addProperty("maxLevel", instance.maxLevel);
            gateObj.addProperty("bossLevel", instance.bossLevel);
            gateObj.addProperty("rankLetter", instance.rankLetter);
            gateObj.addProperty("blockId", instance.blockId);
            gateObj.addProperty("savedTimestamp", instance.savedTimestamp);
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
            for (var element : gatesArray) {
                JsonObject gateObj = element.getAsJsonObject();
                String gateKey = gateObj.get("gateKey").getAsString();
                String instanceWorldName = gateObj.get("instanceWorldName").getAsString();
                int minLevel = gateObj.get("minLevel").getAsInt();
                int maxLevel = gateObj.get("maxLevel").getAsInt();
                long savedTimestamp = gateObj.has("savedTimestamp") ?
                        gateObj.get("savedTimestamp").getAsLong() : 0;
                // v2 fields — default gracefully for files written before this version
                int bossLevel = gateObj.has("bossLevel") ? gateObj.get("bossLevel").getAsInt() : maxLevel;
                String rankLetter = gateObj.has("rankLetter") ? gateObj.get("rankLetter").getAsString() : "E";
                String blockId = gateObj.has("blockId") ? gateObj.get("blockId").getAsString() : "";

                StoredGateInstance stored = new StoredGateInstance(
                        gateKey, instanceWorldName, minLevel, maxLevel, bossLevel, rankLetter, blockId);
                stored.savedTimestamp = savedTimestamp;
                SAVED_INSTANCES.put(gateKey, stored);
            }

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
        public long savedTimestamp;

        public StoredGateInstance(String gateKey, String instanceWorldName,
                                  int minLevel, int maxLevel,
                                  int bossLevel, String rankLetter, String blockId) {
            this.gateKey = gateKey;
            this.instanceWorldName = instanceWorldName;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.bossLevel = bossLevel;
            this.rankLetter = rankLetter != null ? rankLetter : "E";
            this.blockId = blockId != null ? blockId : "";
            this.savedTimestamp = System.currentTimeMillis();
        }
    }
}
