package com.airijko.endlessleveling.registration.classes;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.managers.AddonLoggingManager;
import com.airijko.endlessleveling.managers.ExampleFeatureManager;
import com.airijko.endlessleveling.parsing.ClassParser;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.logging.Level;

/**
 * Scans the classes directory and registers all enabled classes into Endless Leveling via API.
 */
public final class ClassRegistration {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final List<String> registeredClassIds = new ArrayList<>();

    private ClassRegistration() {
    }

    /**
     * Scans the classes folder and registers all enabled class YAML files.
     *
     * @param classesFolder the folder containing class YAML files
     * @return the number of classes successfully registered
     */
    public static int registerAll(File classesFolder) {
        if (classesFolder == null || !classesFolder.isDirectory()) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Classes folder is null or not a directory");
            return 0;
        }

        Yaml yaml = new Yaml();
        int count = 0;

        try (Stream<Path> paths = Files.walk(classesFolder.toPath(), 1)) {
            List<Path> yamlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .toList();

            for (Path yamlFile : yamlFiles) {
                if (registerClass(yamlFile, yaml)) {
                    count++;
                }
            }
        } catch (IOException e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to scan classes folder: %s", e.getMessage());
        }

        AddonLoggingManager.log(LOGGER, Level.INFO, "Registered %d addon class(es)", count);
        return count;
    }

    private static boolean registerClass(Path yamlFile, Yaml yaml) {
        try {
            CharacterClassDefinition definition = ClassParser.parse(yamlFile, yaml);
            if (definition == null) {
                return false;
            }

            if (!definition.isEnabled()) {
                AddonLoggingManager.log(LOGGER, Level.FINE, "Skipping disabled class: %s", definition.getId());
                return false;
            }

            if (!ExampleFeatureManager.get().shouldRegisterContent(yamlFile.getFileName().toString(), definition.getId())) {
                AddonLoggingManager.log(LOGGER, Level.FINE, "Skipping example class due to config: %s", definition.getId());
                return false;
            }

            boolean success = EndlessLevelingAPI.get().registerClass(definition, false);
            if (success) {
                registeredClassIds.add(definition.getId());
                AddonLoggingManager.log(LOGGER, Level.INFO, "Registered addon class: %s", definition.getId());
            } else {
                AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to register class: %s (may already exist)", definition.getId());
            }
            return success;
        } catch (IOException e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to parse class file %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        } catch (Exception e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Error registering class from %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        }
    }

    /**
     * Unregisters all classes that were registered by this addon.
     *
     * @return the number of classes successfully unregistered
     */
    public static int unregisterAll() {
        int count = 0;
        for (String classId : registeredClassIds) {
            try {
                if (EndlessLevelingAPI.get().unregisterClass(classId)) {
                    count++;
                    AddonLoggingManager.log(LOGGER, Level.INFO, "Unregistered addon class: %s", classId);
                }
            } catch (Exception e) {
                AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to unregister class %s: %s", classId, e.getMessage());
            }
        }
        registeredClassIds.clear();
        return count;
    }

    /**
     * @return list of class IDs that were registered by this addon
     */
    public static List<String> getRegisteredClassIds() {
        return List.copyOf(registeredClassIds);
    }
}

