package com.airijko.endlessleveling.registration.races;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
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
            LOGGER.atWarning().log("Races folder is null or not a directory");
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
            LOGGER.atWarning().log("Failed to scan races folder: %s", e.getMessage());
        }

        LOGGER.atInfo().log("Registered %d addon race(s)", count);
        return count;
    }

    private static boolean registerRace(Path yamlFile, Yaml yaml) {
        try {
            RaceDefinition definition = RaceParser.parse(yamlFile, yaml);
            if (definition == null) {
                return false;
            }

            if (!definition.isEnabled()) {
                LOGGER.atFine().log("Skipping disabled race: %s", definition.getId());
                return false;
            }

            boolean success = EndlessLevelingAPI.get().registerRace(definition, false);
            if (success) {
                registeredRaceIds.add(definition.getId());
                LOGGER.atInfo().log("Registered addon race: %s", definition.getId());
            } else {
                LOGGER.atWarning().log("Failed to register race: %s (may already exist)", definition.getId());
            }
            return success;
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to parse race file %s: %s", yamlFile.getFileName(), e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.atWarning().log("Error registering race from %s: %s", yamlFile.getFileName(), e.getMessage());
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
                    LOGGER.atInfo().log("Unregistered addon race: %s", raceId);
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("Failed to unregister race %s: %s", raceId, e.getMessage());
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

