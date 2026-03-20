package com.airijko.endlessleveling.parsing;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.passives.util.PassiveDefinitionParser;
import com.airijko.endlessleveling.races.RaceAscensionDefinition;
import com.airijko.endlessleveling.races.RaceAscensionPathLink;
import com.airijko.endlessleveling.races.RaceAscensionRequirements;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses race YAML files into RaceDefinition objects for external registration.
 */
public final class RaceParser {

    private RaceParser() {
    }

    public static RaceDefinition parse(Path yamlFile, Yaml yaml) throws IOException {
        try (Reader reader = Files.newBufferedReader(yamlFile)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yaml.load(reader);
            if (data == null) {
                throw new IOException("Race file was empty: " + yamlFile.getFileName());
            }
            return buildDefinition(yamlFile, data);
        }
    }

    private static RaceDefinition buildDefinition(Path file, Map<String, Object> yamlData) {
        String raceId = deriveRaceId(file, yamlData);
        String displayName = safeString(yamlData.getOrDefault("race_name", raceId));
        String description = safeString(yamlData.get("description"));
        String iconItemId = safeString(yamlData.get("icon"));
        String modelId = safeString(yamlData.get("model"));
        double modelScale = parseDouble(yamlData.getOrDefault("model_scale", 1.0));
        boolean enabled = parseBoolean(yamlData.getOrDefault("enabled", Boolean.TRUE), true);

        EnumMap<SkillAttributeType, Double> attributes = new EnumMap<>(SkillAttributeType.class);
        Map<String, Object> attributeSection = castToStringObjectMap(yamlData.get("attributes"));
        for (SkillAttributeType type : SkillAttributeType.values()) {
            if (attributeSection == null || !attributeSection.containsKey(type.getConfigKey())) {
                continue;
            }
            double value = parseDouble(attributeSection.get(type.getConfigKey()));
            attributes.put(type, value);
        }

        List<Map<String, Object>> passives = parsePassives(yamlData.get("passives"));
        List<RacePassiveDefinition> passiveDefinitions = buildPassiveDefinitions(raceId, passives);
        RaceAscensionDefinition ascension = parseAscensionDefinition(raceId, yamlData.get("ascension"));

        return new RaceDefinition(raceId,
                displayName,
                description,
                iconItemId,
                modelId,
                modelScale,
                enabled,
                attributes,
                passives,
                passiveDefinitions,
                ascension);
    }

    private static String deriveRaceId(Path file, Map<String, Object> yamlData) {
        String idFromYaml = safeString(yamlData.get("id"));
        if (idFromYaml != null && !idFromYaml.isBlank()) {
            return normalizeKey(idFromYaml);
        }
        String fileName = file.getFileName().toString();
        if (fileName.endsWith(".yml")) {
            fileName = fileName.substring(0, fileName.length() - 4);
        } else if (fileName.endsWith(".yaml")) {
            fileName = fileName.substring(0, fileName.length() - 5);
        }
        return normalizeKey(fileName);
    }

    private static List<Map<String, Object>> parsePassives(Object node) {
        List<Map<String, Object>> passives = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return passives;
        }
        for (Object entry : iterable) {
            Map<String, Object> passive = castToStringObjectMap(entry);
            if (passive != null && passive.containsKey("type")) {
                passives.add(passive);
            }
        }
        return passives;
    }

    private static List<RacePassiveDefinition> buildPassiveDefinitions(String raceId, List<Map<String, Object>> passives) {
        List<RacePassiveDefinition> definitions = new ArrayList<>();
        if (passives == null) {
            return definitions;
        }

        for (Map<String, Object> passive : passives) {
            if (passive == null) {
                continue;
            }

            String rawType = safeString(passive.get("type"));
            if (rawType == null) {
                continue;
            }

            ArchetypePassiveType type = ArchetypePassiveType.fromConfigKey(rawType);
            if (type == null) {
                continue;
            }

            double value = parseDouble(passive.get("value"));
            SkillAttributeType attributeType = null;
            if (type == ArchetypePassiveType.INNATE_ATTRIBUTE_GAIN) {
                String attributeKey = safeString(passive.get("attribute"));
                attributeType = SkillAttributeType.fromConfigKey(attributeKey);
                if (attributeType == null) {
                    continue;
                }
            }

            DamageLayer damageLayer = PassiveDefinitionParser.resolveDamageLayer(type, passive);
            String tag = PassiveDefinitionParser.resolveTag(type, passive);
            PassiveStackingStyle stacking = PassiveDefinitionParser.resolveStacking(type, passive);
            PassiveTier tier = PassiveTier.fromConfig(passive.get("tier"), PassiveTier.COMMON);
            PassiveCategory category = PassiveCategory.fromConfig(passive.get("category"), null);
            Map<String, Double> classValues = parseClassValues(passive.get("class_values"));

            definitions.add(new RacePassiveDefinition(type,
                    value,
                    passive,
                    attributeType,
                    damageLayer,
                    tag,
                    category,
                    stacking,
                    tier,
                    classValues));
        }
        return definitions;
    }

    private static RaceAscensionDefinition parseAscensionDefinition(String raceId, Object node) {
        Map<String, Object> ascensionNode = castToStringObjectMap(node);
        if (ascensionNode == null) {
            return RaceAscensionDefinition.baseFallback(normalizeKey(raceId));
        }

        String ascensionId = safeString(ascensionNode.get("id"));
        if (ascensionId == null) {
            ascensionId = normalizeKey(raceId);
        }
        String stage = safeString(ascensionNode.get("stage"));
        String path = safeString(ascensionNode.get("path"));
        boolean finalForm = parseBoolean(ascensionNode.get("final_form"), false);
        boolean singleRouteOnly = parseBoolean(ascensionNode.get("single_route_only"), true);
        if (ascensionNode.containsKey("allow_all_routes")) {
            singleRouteOnly = !parseBoolean(ascensionNode.get("allow_all_routes"), false);
        }
        RaceAscensionRequirements requirements = parseAscensionRequirements(ascensionNode.get("requirements"));
        List<RaceAscensionPathLink> nextPaths = parseAscensionNextPaths(ascensionNode.get("next_paths"));

        return new RaceAscensionDefinition(ascensionId,
                stage,
                path,
                finalForm,
                singleRouteOnly,
                requirements,
                nextPaths);
    }

    private static RaceAscensionRequirements parseAscensionRequirements(Object node) {
        Map<String, Object> requirementsNode = castToStringObjectMap(node);
        if (requirementsNode == null) {
            return RaceAscensionRequirements.none();
        }

        int requiredPrestige = parseInt(requirementsNode.get("required_prestige"), 0);
        Map<SkillAttributeType, Integer> minSkillLevels = parseSkillLevelRequirements(
                requirementsNode.get("min_skill_levels"));
        Map<SkillAttributeType, Integer> maxSkillLevels = parseSkillLevelRequirements(
                requirementsNode.get("max_skill_levels"));
        List<Map<SkillAttributeType, Integer>> minAnySkillLevels = parseMinAnySkillLevels(
                requirementsNode.get("min_any_skill_levels"));
        List<String> requiredAugments = parseStringList(requirementsNode.get("required_augments"));
        List<String> requiredForms = parseStringList(requirementsNode.get("required_forms"));
        List<String> requiredAnyForms = parseStringList(requirementsNode.get("required_any_forms"));

        return new RaceAscensionRequirements(
                requiredPrestige,
                minSkillLevels,
                maxSkillLevels,
                minAnySkillLevels,
                requiredAugments,
                requiredForms,
                requiredAnyForms);
    }

    private static Map<SkillAttributeType, Integer> parseSkillLevelRequirements(Object node) {
        Map<SkillAttributeType, Integer> result = new EnumMap<>(SkillAttributeType.class);
        Map<String, Object> map = castToStringObjectMap(node);
        if (map == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            SkillAttributeType type = SkillAttributeType.fromConfigKey(entry.getKey());
            if (type != null) {
                int level = parseInt(entry.getValue(), 0);
                result.put(type, level);
            }
        }
        return result;
    }

    private static List<Map<SkillAttributeType, Integer>> parseMinAnySkillLevels(Object node) {
        List<Map<SkillAttributeType, Integer>> result = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return result;
        }
        for (Object entry : iterable) {
            Map<String, Object> skillMap = castToStringObjectMap(entry);
            if (skillMap != null) {
                Map<SkillAttributeType, Integer> parsed = parseSkillLevelRequirements(skillMap);
                if (!parsed.isEmpty()) {
                    result.add(parsed);
                }
            }
        }
        return result;
    }

    private static List<String> parseStringList(Object node) {
        List<String> values = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return values;
        }
        for (Object entry : iterable) {
            String value = safeString(entry);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static List<RaceAscensionPathLink> parseAscensionNextPaths(Object node) {
        List<RaceAscensionPathLink> nextPaths = new ArrayList<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return nextPaths;
        }

        for (Object entry : iterable) {
            Map<String, Object> linkMap = castToStringObjectMap(entry);
            if (linkMap == null) {
                continue;
            }
            String id = safeString(linkMap.get("id"));
            if (id == null) {
                continue;
            }
            String name = safeString(linkMap.get("name"));
            nextPaths.add(new RaceAscensionPathLink(id, name));
        }
        return nextPaths;
    }

    private static Map<String, Double> parseClassValues(Object node) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (!(node instanceof Map<?, ?> map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                continue;
            }
            String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
            if (normalizedKey.isEmpty()) {
                continue;
            }
            Double value = extractDoubleValue(entry.getValue());
            if (value != null) {
                result.put(normalizedKey, value);
            }
        }
        return result;
    }

    private static Double extractDoubleValue(Object rawVal) {
        if (rawVal instanceof Number number) {
            return number.doubleValue();
        }
        if (rawVal instanceof Map<?, ?> map) {
            Object inner = map.get("value");
            if (inner instanceof Number number) {
                return number.doubleValue();
            }
            if (inner instanceof String str) {
                return parseDoubleOrNull(str);
            }
        }
        if (rawVal instanceof String str) {
            return parseDoubleOrNull(str);
        }
        return null;
    }

    private static Double parseDoubleOrNull(String str) {
        if (str == null) {
            return null;
        }
        try {
            return Double.parseDouble(str.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStringObjectMap(Object node) {
        if (node instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static String safeString(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString().trim();
        return str.isEmpty() ? null : str;
    }

    private static String normalizeKey(String key) {
        if (key == null) {
            return null;
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static double parseDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0D;
    }

    private static boolean parseBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str && !str.isBlank()) {
            return Boolean.parseBoolean(str.trim());
        }
        return defaultValue;
    }

    private static int parseInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}

