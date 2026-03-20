package com.airijko.endlessleveling.parsing;

import com.airijko.endlessleveling.classes.CharacterClassDefinition;
import com.airijko.endlessleveling.classes.WeaponConfig;
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
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses character class YAML files into CharacterClassDefinition objects for external registration.
 */
public final class ClassParser {

    private ClassParser() {
    }

    public static CharacterClassDefinition parse(Path yamlFile, Yaml yaml) throws IOException {
        try (Reader reader = Files.newBufferedReader(yamlFile)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = yaml.load(reader);
            if (data == null) {
                throw new IOException("Class file was empty: " + yamlFile.getFileName());
            }
            return buildDefinition(yamlFile, data);
        }
    }

    private static CharacterClassDefinition buildDefinition(Path file, Map<String, Object> yamlData) {
        String classId = deriveClassId(file, yamlData);
        String displayName = safeString(yamlData.getOrDefault("class_name", yamlData.get("name")));
        if (displayName == null) {
            displayName = classId;
        }
        String description = safeString(yamlData.get("description"));
        String role = safeString(yamlData.get("role"));
        String category = parseClassCategory(yamlData, classId);
        boolean enabled = parseBoolean(yamlData.getOrDefault("enabled", Boolean.TRUE), true);

        String iconItemId = parseIconId(yamlData);
        Map<String, Double> weaponMultipliers = parseWeaponSection(yamlData);
        List<Map<String, Object>> passives = parsePassives(yamlData.get("passives"));
        List<RacePassiveDefinition> passiveDefinitions = buildPassiveDefinitions(classId, passives);
        RaceAscensionDefinition ascension = parseAscensionDefinition(classId, yamlData.get("ascension"));

        return new CharacterClassDefinition(classId,
                displayName,
                description,
                role,
                category,
                enabled,
                iconItemId,
                weaponMultipliers,
                passives,
                passiveDefinitions,
                ascension);
    }

    private static String deriveClassId(Path file, Map<String, Object> yamlData) {
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

    private static String parseClassCategory(Map<String, Object> yamlData, String classId) {
        String configured = safeString(yamlData.get("category"));
        if (configured != null) {
            return normalizeKey(configured);
        }
        return inferLegacyClassCategory(classId);
    }

    private static String inferLegacyClassCategory(String classId) {
        String normalized = normalizeKey(classId);
        String baseId = normalized;
        int firstUnderscore = normalized.indexOf('_');
        if (firstUnderscore > 0) {
            baseId = normalized.substring(0, firstUnderscore);
        }

        return switch (baseId) {
            case "mage", "arcanist", "marksman", "assassin", "oracle", "healer", "necromancer" ->
                "glass_cannon";
            case "battlemage", "duelist", "brawler", "adventurer", "slayer" -> "fighter";
            case "juggernaut", "vanguard" -> "tank";
            default -> "default";
        };
    }

    private static String parseIconId(Map<String, Object> yamlData) {
        if (yamlData == null) {
            return null;
        }
        Object value = yamlData.get("icon");
        if (value == null) {
            value = yamlData.get("icon_id");
        }
        if (value == null) {
            value = yamlData.get("item_icon");
        }
        return safeString(value);
    }

    private static Map<String, Double> parseWeaponSection(Map<String, Object> yamlData) {
        Object node = yamlData.get("Weapon");
        if (node == null) {
            node = yamlData.get("weapon");
        }
        if (node == null) {
            node = yamlData.get("weapons");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        if (!(node instanceof Iterable<?> iterable)) {
            return result;
        }
        for (Object entry : iterable) {
            Map<String, Object> weaponMap = castToStringObjectMap(entry);
            if (weaponMap == null) {
                continue;
            }
            String typeKey = safeString(weaponMap.get("type"));
            if (typeKey == null) {
                continue;
            }
            String weaponCategory = WeaponConfig.normalizeCategoryKey(typeKey);
            if (weaponCategory == null) {
                continue;
            }
            double multiplier = parseDouble(weaponMap.get("damage"), 1.0D);
            if (multiplier <= 0.0D) {
                multiplier = 1.0D;
            }
            result.put(weaponCategory, multiplier);
        }
        return result;
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

    private static List<RacePassiveDefinition> buildPassiveDefinitions(String classId, List<Map<String, Object>> passives) {
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

            double value = parseDouble(passive.get("value"), 0.0D);
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

    private static RaceAscensionDefinition parseAscensionDefinition(String classId, Object node) {
        Map<String, Object> ascensionNode = castToStringObjectMap(node);
        if (ascensionNode == null) {
            return RaceAscensionDefinition.baseFallback(normalizeKey(classId));
        }

        String ascensionId = safeString(ascensionNode.get("id"));
        if (ascensionId == null) {
            ascensionId = normalizeKey(classId);
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

    private static double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
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

