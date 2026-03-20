package com.airijko.endlessleveling.registration.augments;

import com.airijko.endlessleveling.api.EndlessLevelingAPI;
import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.registration.augments.examples.ConquerorExampleAugment;
import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Scans the augments directory and registers all enabled augments into Endless Leveling via API.
 * Augments default to data-only registration, with optional factory-backed Java behavior for examples.
 */
public final class AugmentRegistration {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    private static final Map<String, Function<AugmentDefinition, Augment>> EXAMPLE_FACTORIES = Map.of(
            ConquerorExampleAugment.ID,
            ConquerorExampleAugment::new);
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

            AugmentDefinition definition = parseDefinition(yamlFile, yaml);
            if (definition == null) {
                return false;
            }

            String augmentId = normalizeId(definition.getId());
            Function<AugmentDefinition, Augment> factory = augmentId == null
                    ? null
                    : EXAMPLE_FACTORIES.get(augmentId);
            boolean success = factory == null
                    ? EndlessLevelingAPI.get().registerAugment(definition)
                    : EndlessLevelingAPI.get().registerAugment(definition, factory);
            if (success) {
                registeredAugmentIds.add(definition.getId());
                if (factory == null) {
                    LOGGER.atInfo().log("Registered addon augment: %s", definition.getId());
                } else {
                    LOGGER.atInfo().log("Registered addon augment with backend factory: %s", definition.getId());
                }
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

    @SuppressWarnings("unchecked")
    private static AugmentDefinition parseDefinition(Path file, Yaml yaml) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            Map<String, Object> root = yaml.load(in);
            if (root == null) {
                root = Collections.emptyMap();
            }
            String id = stringVal(root.get("id"), stripExtension(file.getFileName().toString()));
            String name = stringVal(root.get("name"), id);
            String description = stringVal(root.get("description"), "");
            PassiveTier tier = PassiveTier.fromConfig(root.get("tier"), PassiveTier.COMMON);
            PassiveCategory category = PassiveCategory.fromConfig(root.get("category"), null);
            boolean stackable = booleanVal(root.get("stackable"), false);
            Object passivesNode = root.getOrDefault("passives", Collections.emptyMap());
            Map<String, Object> passives = passivesNode instanceof Map<?, ?> m
                    ? (Map<String, Object>) m
                    : Collections.emptyMap();
            List<AugmentDefinition.UiSection> uiSections = parseUiSections(root, yaml);
            return new AugmentDefinition(id, name, tier, category, stackable, description, passives, uiSections);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<AugmentDefinition.UiSection> parseUiSections(Map<String, Object> root, Yaml yaml) {
        List<AugmentDefinition.UiSection> sections = new ArrayList<>();
        Object uiNode = root.get("ui");
        if (!(uiNode instanceof Map<?, ?> uiMapRaw)) {
            return sections;
        }
        Map<String, Object> uiMap = (Map<String, Object>) uiMapRaw;
        Object sectionsNode = uiMap.get("sections");
        if (!(sectionsNode instanceof List<?> list)) {
            return sections;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> sectionRaw)) {
                continue;
            }
            Map<String, Object> section = (Map<String, Object>) sectionRaw;
            String title = stringVal(section.get("title"), "");
            String body = textVal(section.get("body"), yaml);
            String color = stringVal(section.get("color"), "");
            sections.add(new AugmentDefinition.UiSection(title, body, color));
        }
        return sections;
    }

    private static String textVal(Object value, Yaml yaml) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        return dumpYamlBlock(value, yaml);
    }

    private static String dumpYamlBlock(Object value, Yaml yaml) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        String dumped = yaml.dump(value);
        if (dumped == null) {
            return "";
        }
        dumped = dumped.replace("\r", "");
        if (dumped.endsWith("\n")) {
            dumped = dumped.substring(0, dumped.length() - 1);
        }
        return dumped;
    }

    private static String stringVal(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private static boolean booleanVal(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String text = s.trim();
            if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("yes") || text.equals("1")) {
                return true;
            }
            if (text.equalsIgnoreCase("false") || text.equalsIgnoreCase("no") || text.equals("0")) {
                return false;
            }
        }
        return fallback;
    }

    private static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "augment";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx <= 0) {
            return fileName;
        }
        return fileName.substring(0, idx);
    }

    private static String normalizeId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        return rawId.trim().toLowerCase(Locale.ROOT);
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

