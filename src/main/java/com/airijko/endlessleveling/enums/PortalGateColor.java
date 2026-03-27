package com.airijko.endlessleveling.enums;

import javax.annotation.Nonnull;

/**
 * Centralized color palette for natural portal gate messages and rank display.
 */
public enum PortalGateColor {
    PREFIX("#ff3b30"),
    HEADLINE("#ffc300"),
    WORLD("#66d9ff"),
    POSITION("#ffd166"),
    LEVEL("#6cff78"),

    RANK_S("#ffbf00"),
    RANK_A("#ef476f"),
    RANK_B("#f78c6b"),
    RANK_C("#8ac926"),
    RANK_D("#4cc9f0"),
    RANK_E("#adb5bd");

    private final String hex;

    PortalGateColor(String hex) {
        this.hex = hex;
    }

    @Nonnull
    public String hex() {
        return hex;
    }
}
