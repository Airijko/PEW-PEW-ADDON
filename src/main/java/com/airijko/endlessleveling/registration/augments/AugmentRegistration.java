package com.airijko.endlessleveling.registration.augments;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentParser;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Scans the augments directory and registers all enabled augments into Endless Leveling via API.
 * Augments are registered using the core's YamlAugment fallback (no custom Java factory needed).
 */
public final class AugmentRegistration {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final List<String> registeredAugmentIds = new ArrayList<>();

    private AugmentRegistration() {
    }

    /**
     * Scans the augments folder and registers all enabled augment YAML files.
     *
     * @param augmentsFolder the folder containing augment YAML files
     * @return the number of augments successfully registered
     */
    public static int registerAll(File augmentsFolder) {
        if (augmentsFolder == null || !augmentsFolder.isDirectory()) {
            LOGGER.atWarning().log("Augments folder is null or not a directory");
            return 0;
        }

        Yaml yaml = new Yaml();
        int count = 0;

        try (Stream<Path> paths = Files.walk(augmentsFolder.toPath(), 1)) {
            List<Path> yamlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .toList();

            for (Path yamlFile : yamlFiles) {
                if (registerAugment(yamlFile, yaml)) {
                    count++;
                }
            }
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to scan augments folder: %s", e.getMessage());
        }

        LOGGER.atInfo().log("Registered %d addon augment(s)", count);
        return count;
    }

    private static boolean registerAugment(Path yamlFile, Yaml yaml) {
        try {
            // Check if augment is enabled before parsing
            if (!isEnabled(yamlFile, yaml)) {
                String fileName = yamlFile.getFileName().toString();
                LOGGER.atFine().log("Skipping disabled augment: %s", fileName);
                return false;
            }

            AugmentDefinition definition = AugmentParser.parse(yamlFile, yaml);
            if (definition == null) {
                return false;
            }

            // Register without a custom factory - uses core's YamlAugment fallback
            boolean success = EndlessLevelingAPI.get().registerAugment(definition);
            if (success) {
                registeredAugmentIds.add(definition.getId());
                LOGGER.atInfo().log("Registered addon augment: %s", definition.getId());
            } else {
                LOGGER.atWarning().log("Failed to register augment: %s (may already exist)", definition.getId());
            }
            return success;
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to parse augment file %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.atWarning().log("Error registering augment from %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean isEnabled(Path yamlFile, Yaml yaml) {
        try (InputStream input = Files.newInputStream(yamlFile)) {
            Object loaded = yaml.load(input);
            if (loaded instanceof Map<?, ?> map) {
                Object rawEnabled = ((Map<String, Object>) map).get("enabled");
                if (rawEnabled instanceof Boolean enabled) {
                    return enabled;
                }
                if (rawEnabled instanceof String enabledText && !enabledText.isBlank()) {
                    return Boolean.parseBoolean(enabledText.trim());
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return true; // Default to enabled if not specified
    }

    /**
     * Unregisters all augments that were registered by this addon.
     *
     * @return the number of augments successfully unregistered
     */
    public static int unregisterAll() {
        int count = 0;
        for (String augmentId : registeredAugmentIds) {
            try {
                if (EndlessLevelingAPI.get().unregisterAugment(augmentId)) {
                    count++;
                    LOGGER.atInfo().log("Unregistered addon augment: %s", augmentId);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to unregister augment %s: %s", augmentId, e.getMessage());
            }
        }
        registeredAugmentIds.clear();
        return count;
    }

    /**
     * @return list of augment IDs that were registered by this addon
     */
    public static List<String> getRegisteredAugmentIds() {
        return List.copyOf(registeredAugmentIds);
    }
}

