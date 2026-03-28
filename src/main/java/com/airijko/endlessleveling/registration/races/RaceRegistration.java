package com.airijko.endlessleveling.registration.races;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.managers.AddonLoggingManager;
import com.airijko.endlessleveling.managers.ExampleFeatureManager;
import com.airijko.endlessleveling.parsing.RaceParser;
import com.airijko.endlessleveling.races.RaceDefinition;
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
 * Scans the races directory and registers all enabled races into Endless Leveling via API.
 */
public final class RaceRegistration {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final List<String> registeredRaceIds = new ArrayList<>();

    private RaceRegistration() {
    }

    /**
     * Scans the races folder and registers all enabled race YAML files.
     *
     * @param racesFolder the folder containing race YAML files
     * @return the number of races successfully registered
     */
    public static int registerAll(File racesFolder) {
        if (racesFolder == null || !racesFolder.isDirectory()) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Races folder is null or not a directory");
            return 0;
        }

        Yaml yaml = new Yaml();
        int count = 0;

        try (Stream<Path> paths = Files.walk(racesFolder.toPath(), 1)) {
            List<Path> yamlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .toList();

            for (Path yamlFile : yamlFiles) {
                if (registerRace(yamlFile, yaml)) {
                    count++;
                }
            }
        } catch (IOException e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to scan races folder: %s", e.getMessage());
        }

        AddonLoggingManager.log(LOGGER, Level.INFO, "Registered %d addon race(s)", count);
        return count;
    }

    private static boolean registerRace(Path yamlFile, Yaml yaml) {
        try {
            RaceDefinition definition = RaceParser.parse(yamlFile, yaml);
            if (definition == null) {
                return false;
            }

            if (!definition.isEnabled()) {
                AddonLoggingManager.log(LOGGER, Level.FINE, "Skipping disabled race: %s", definition.getId());
                return false;
            }

            if (!ExampleFeatureManager.get().shouldRegisterContent(yamlFile.getFileName().toString(), definition.getId())) {
                AddonLoggingManager.log(LOGGER, Level.FINE, "Skipping example race due to config: %s", definition.getId());
                return false;
            }

            boolean success = EndlessLevelingAPI.get().registerRace(definition, false);
            if (success) {
                registeredRaceIds.add(definition.getId());
                AddonLoggingManager.log(LOGGER, Level.INFO, "Registered addon race: %s", definition.getId());
            } else {
                AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to register race: %s (may already exist)", definition.getId());
            }
            return success;
        } catch (IOException e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to parse race file %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        } catch (Exception e) {
            AddonLoggingManager.log(LOGGER, Level.WARNING, "Error registering race from %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        }
    }

    /**
     * Unregisters all races that were registered by this addon.
     *
     * @return the number of races successfully unregistered
     */
    public static int unregisterAll() {
        int count = 0;
        for (String raceId : registeredRaceIds) {
            try {
                if (EndlessLevelingAPI.get().unregisterRace(raceId)) {
                    count++;
                    AddonLoggingManager.log(LOGGER, Level.INFO, "Unregistered addon race: %s", raceId);
                }
            } catch (Exception e) {
                AddonLoggingManager.log(LOGGER, Level.WARNING, "Failed to unregister race %s: %s", raceId, e.getMessage());
            }
        }
        registeredRaceIds.clear();
        return count;
    }

    /**
     * @return list of race IDs that were registered by this addon
     */
    public static List<String> getRegisteredRaceIds() {
        return List.copyOf(registeredRaceIds);
    }
}

