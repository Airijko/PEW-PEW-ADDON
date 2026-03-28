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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

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
     * Captures the current state of GATE_KEY_TO_INSTANCE_NAME from PortalLeveledInstanceRouter.
     */
    public static void saveGateInstances(@Nonnull Map<String, String> gateKeyToInstanceName,
                                         @Nonnull Map<String, ?> activeLevelRanges) {
        try {
            SAVED_INSTANCES.clear();
            
            for (Map.Entry<String, String> entry : gateKeyToInstanceName.entrySet()) {
                String gateKey = entry.getKey();
                String instanceName = entry.getValue();
                
                // Extract level range from activeLevelRanges (Map<String, LevelRange>)
                // LevelRange has min/max fields
                int minLevel = 1;
                int maxLevel = 500;
                Object rangeObj = activeLevelRanges.get(instanceName);
                if (rangeObj != null) {
                    try {
                        // Use reflection to access min/max fields since it's a private record
                        var minField = rangeObj.getClass().getDeclaredField("min");
                        var maxField = rangeObj.getClass().getDeclaredField("max");
                        minField.setAccessible(true);
                        maxField.setAccessible(true);
                        minLevel = minField.getInt(rangeObj);
                        maxLevel = maxField.getInt(rangeObj);
                    } catch (Exception ex) {
                        // Fallback to defaults if reflection fails
                    }
                }
                
                StoredGateInstance stored = new StoredGateInstance(
                    gateKey,
                    instanceName,
                    minLevel,
                    maxLevel
                );
                SAVED_INSTANCES.put(gateKey, stored);
            }
            
            writeToDisk();
            System.out.println("[ELPortal-Persistence] Saved " + SAVED_INSTANCES.size() + " gate instance mappings to " + persistenceFile.getName());
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

                StoredGateInstance stored = new StoredGateInstance(gateKey, instanceWorldName, minLevel, maxLevel);
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
        public long savedTimestamp;

        public StoredGateInstance(String gateKey, String instanceWorldName, int minLevel, int maxLevel) {
            this.gateKey = gateKey;
            this.instanceWorldName = instanceWorldName;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.savedTimestamp = System.currentTimeMillis();
        }
    }
}
