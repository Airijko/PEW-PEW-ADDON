package com.airijko.endlessleveling.registration.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveCategory;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.enums.PassiveTier;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.passives.archetype.ArchetypePassiveSource;
import com.airijko.endlessleveling.player.PlayerData;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A generic YAML-based passive source that reads passive configuration from YAML
 * and contributes it to all players.
 */
public final class YamlPassiveSource implements ArchetypePassiveSource {

    private final String id;
    private final RacePassiveDefinition definition;

    public YamlPassiveSource(String id, ArchetypePassiveType type, double value, Map<String, Object> properties) {
        this.id = id;
        this.definition = new RacePassiveDefinition(
                type,
                Math.max(0.0D, value),
                properties != null ? properties : Collections.emptyMap(),
                null, // attributeType - set if INNATE_ATTRIBUTE_GAIN
                null, // damageLayer
                id,   // tag
                PassiveCategory.PASSIVE_STAT,
                PassiveStackingStyle.defaultFor(type),
                PassiveTier.COMMON,
                null); // classValues
    }

    public YamlPassiveSource(String id, 
                              ArchetypePassiveType type, 
                              double value, 
                              Map<String, Object> properties,
                              SkillAttributeType attributeType,
                              DamageLayer damageLayer,
                              PassiveStackingStyle stackingStyle,
                              PassiveTier tier) {
        this.id = id;
        this.definition = new RacePassiveDefinition(
                type,
                Math.max(0.0D, value),
                properties != null ? properties : Collections.emptyMap(),
                attributeType,
                damageLayer,
                id,
                PassiveCategory.PASSIVE_STAT,
                stackingStyle != null ? stackingStyle : PassiveStackingStyle.defaultFor(type),
                tier != null ? tier : PassiveTier.COMMON,
                null);
    }

    public String getId() {
        return id;
    }

    public ArchetypePassiveType getType() {
        return definition.type();
    }

    @Override
    public void collect(PlayerData playerData,
            EnumMap<ArchetypePassiveType, StackAccumulator> totals,
            EnumMap<ArchetypePassiveType, List<RacePassiveDefinition>> grouped) {
        if (playerData == null || definition.value() <= 0.0D || definition.type() == null) {
            return;
        }

        ArchetypePassiveType type = definition.type();

        StackAccumulator accumulator = totals.computeIfAbsent(
                type,
                key -> new StackAccumulator(definition.effectiveStackingStyle()));
        accumulator.addValue(definition.value());

        List<RacePassiveDefinition> definitions = grouped.computeIfAbsent(
                type,
                key -> new ArrayList<>());
        definitions.add(definition);
    }
}

