package com.airijko.endlessleveling.registration.passives;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.AddonLoggingManager;
import com.airijko.endlessleveling.managers.ExampleFeatureManager;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.logging.Level;

/**
 * Scans the passives directory and registers all enabled passives into Endless Leveling via API.
 */
public final class PassiveRegistration {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final List<YamlPassiveSource> registeredSources = new ArrayList<>();

    private PassiveRegistration() {
    }

    /**
     * Scans the passives folder and registers all enabled passive YAML files.
     *
     * @param passivesFolder the folder containing passive YAML files
     * @return the number of passives successfully registered
     */
    public static int registerAll(File passivesFolder) {
        if (passivesFolder == null || !passivesFolder.isDirectory()) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Passives folder is null or not a directory");
            return 0;
        }

        Yaml yaml = new Yaml();
        int count = 0;

        try (Stream<Path> paths = Files.walk(passivesFolder.toPath(), 1)) {
            List<Path> yamlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .toList();

            for (Path yamlFile : yamlFiles) {
                if (registerPassive(yamlFile, yaml)) {
                    count++;
                }
            }
        } catch (IOException e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to scan passives folder: %s", e.getMessage());
        }

        AddonLoggingManager.log(LOGGER, Level.INFO, "Registered %d addon passive(s)", count);
        return count;
    }

    private static boolean registerPassive(Path yamlFile, Yaml yaml) {
        try {
            PassiveConfig config = parsePassiveConfig(yamlFile, yaml);
            if (config == null) {
                return false;
            }

            if (!config.enabled) {
                AddonLoggingManager.log(LOGGER, Level.FINE, "Skipping disabled passive: %s", config.id);
                return false;
            }

            if (!ExampleFeatureManager.get().shouldRegisterContent(yamlFile.getFileName().toString(), config.id)) {
                AddonLoggingManager.log(LOGGER, Level.FINE, "Skipping example passive due to config: %s", config.id);
                return false;
            }

            if (config.type == null) {
                AddonLoggingManager.log(LOGGER, Level.WARNING, "Passive %s has invalid or missing type", config.id);
                return false;
            }

            YamlPassiveSource source = new YamlPassiveSource(
                    config.id,
                    config.type,
                    config.value,
                    config.properties,
                    config.attributeType,
                    config.damageLayer,
                    config.stackingStyle,
                    config.tier);

            boolean success = EndlessLevelingAPI.get().registerArchetypePassiveSource(source);
            if (success) {
                registeredSources.add(source);
                AddonLoggingManager.log(LOGGER, Level.INFO, "Registered addon passive: %s (%s)", config.id, config.type);
            } else {
                AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to register passive: %s", config.id);
            }
            return success;
        } catch (Exception e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Error registering passive from %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static PassiveConfig parsePassiveConfig(Path yamlFile, Yaml yaml) throws IOException {
        try (InputStream input = Files.newInputStream(yamlFile)) {
            Object loaded = yaml.load(input);
            if (!(loaded instanceof Map<?, ?> rootRaw)) {
                return null;
            }

            Map<String, Object> root = (Map<String, Object>) rootRaw;
            
            // Support both root-level and nested "passive:" structure
            Map<String, Object> passive = asMap(root.get("passive"));
            if (passive.isEmpty()) {
                passive = root;
            }

            // Derive ID from filename if not specified
            String id = stringValue(passive.get("id"), null);
            if (id == null) {
                String fileName = yamlFile.getFileName().toString();
                if (fileName.endsWith(".yml")) {
                    id = fileName.substring(0, fileName.length() - 4);
                } else if (fileName.endsWith(".yaml")) {
                    id = fileName.substring(0, fileName.length() - 5);
                } else {
                    id = fileName;
                }
            }

            boolean enabled = booleanValue(passive.get("enabled"), true);
            String typeStr = stringValue(passive.get("type"), null);
            ArchetypePassiveType type = typeStr != null ? ArchetypePassiveType.fromConfigKey(typeStr) : null;
            double value = doubleValue(passive.get("value"), 0.0D);

            // Optional fields
            String attributeStr = stringValue(passive.get("attribute"), null);
            SkillAttributeType attributeType = attributeStr != null ? SkillAttributeType.fromConfigKey(attributeStr) : null;

            String layerStr = stringValue(passive.get("layer"), stringValue(passive.get("damage_layer"), null));
            DamageLayer damageLayer = layerStr != null ? DamageLayer.fromConfig(layerStr, null) : null;

            String stackingStr = stringValue(passive.get("stacking"), stringValue(passive.get("stacking_style"), null));
            PassiveStackingStyle stackingStyle = stackingStr != null ? PassiveStackingStyle.fromConfig(stackingStr, null) : null;

            PassiveTier tier = PassiveTier.fromConfig(passive.get("tier"), PassiveTier.COMMON);

            // Collect remaining properties
            Map<String, Object> properties = new LinkedHashMap<>(passive);
            properties.remove("id");
            properties.remove("enabled");
            properties.remove("type");
            properties.remove("value");
            properties.remove("attribute");
            properties.remove("layer");
            properties.remove("damage_layer");
            properties.remove("stacking");
            properties.remove("stacking_style");
            properties.remove("tier");

            return new PassiveConfig(id, enabled, type, value, properties, attributeType, damageLayer, stackingStyle, tier);
        }
    }

    /**
     * Unregisters all passives that were registered by this addon.
     *
     * @return the number of passives successfully unregistered
     */
    public static int unregisterAll() {
        int count = 0;
        for (YamlPassiveSource source : registeredSources) {
            try {
                if (EndlessLevelingAPI.get().unregisterArchetypePassiveSource(source)) {
                    count++;
                    AddonLoggingManager.log(LOGGER, Level.INFO, "Unregistered addon passive: %s", source.getId());
                }
            } catch (Exception e) {
                AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to unregister passive %s: %s", source.getId(), e.getMessage());
            }
        }
        registeredSources.clear();
        return count;
    }

    /**
     * @return list of passive IDs that were registered by this addon
     */
    public static List<String> getRegisteredPassiveIds() {
        return registeredSources.stream().map(YamlPassiveSource::getId).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }

    private static String stringValue(Object value, String fallback) {
        if (value instanceof String str && !str.isBlank()) {
            return str.trim();
        }
        return fallback;
    }

    private static boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str && !str.isBlank()) {
            return Boolean.parseBoolean(str.trim());
        }
        return fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String str && !str.isBlank()) {
            try {
                return Double.parseDouble(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private record PassiveConfig(
            String id,
            boolean enabled,
            ArchetypePassiveType type,
            double value,
            Map<String, Object> properties,
            SkillAttributeType attributeType,
            DamageLayer damageLayer,
            PassiveStackingStyle stackingStyle,
            PassiveTier tier) {
    }
}

