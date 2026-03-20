package com.airijko.endlessleveling.registration.augments.examples;

import com.airijko.endlessleveling.augments.Augment;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;

import java.util.Map;

/**
 * Addon-side backend logic example that mirrors the Conqueror-style stacking flow.
 */
public final class ConquerorExampleAugment extends Augment implements AugmentHooks.OnHitAugment {
    public static final String ID = "conqueror_example";

    private final double bonusDamagePerStack;
    private final int maxStacks;
    private final double maxStackFlatTrueDamage;
    private final double maxStackTrueDamagePercent;
    private final long stackDurationMillis;

    public ConquerorExampleAugment(AugmentDefinition definition) {
        super(definition);

        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> bonusDamage = AugmentValueReader.getMap(buffs, "bonus_damage");
        Map<String, Object> maxStackBonus = AugmentValueReader.getMap(passives, "max_stack_bonus");
        Map<String, Object> bonusTrueDamage = AugmentValueReader.getMap(maxStackBonus, "bonus_true_damage");
        Map<String, Object> duration = AugmentValueReader.getMap(passives, "duration");

        this.bonusDamagePerStack = AugmentUtils.normalizeConfiguredBonusMultiplier(
                AugmentValueReader.getDouble(bonusDamage, "value", 0.0D));
        this.maxStacks = Math.max(1, AugmentValueReader.getInt(buffs, "max_stacks", 1));
        this.maxStackFlatTrueDamage = Math.max(0.0D, AugmentValueReader.getDouble(bonusTrueDamage, "value", 0.0D));
        this.maxStackTrueDamagePercent = AugmentUtils.normalizeConfiguredBonusMultiplier(
                AugmentValueReader.getDouble(bonusTrueDamage, "true_damage_percent", 0.0D));
        this.stackDurationMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(duration, "seconds", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getDamage() : 0f;
        }

        long now = System.currentTimeMillis();
        var state = context.getRuntimeState().getState(ID);
        if (stackDurationMillis > 0L && state.getStacks() > 0 && state.getExpiresAt() > 0L && now >= state.getExpiresAt()) {
            state.clear();
        }

        int currentStacks = Math.max(0, state.getStacks());
        int nextStacks = Math.min(maxStacks, currentStacks + 1);
        state.setStacks(nextStacks);
        if (stackDurationMillis > 0L) {
            state.setExpiresAt(now + stackDurationMillis);
        }

        double bonusMultiplier = bonusDamagePerStack * nextStacks;
        float updatedDamage = AugmentUtils.applyAdditiveBonusFromBase(
                context.getDamage(),
                context.getBaseDamage(),
                bonusMultiplier);

        if (nextStacks >= maxStacks) {
            double trueDamage = maxStackFlatTrueDamage
                    + (Math.max(0.0D, context.getBaseDamage()) * maxStackTrueDamagePercent);
            context.addTrueDamageBonus(trueDamage);
        }

        return updatedDamage;
    }
}
