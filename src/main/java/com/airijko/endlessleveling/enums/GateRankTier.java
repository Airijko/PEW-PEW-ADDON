package com.airijko.endlessleveling.enums;

import javax.annotation.Nonnull;

public enum GateRankTier {
    S("S", PortalGateColor.RANK_S),
    A("A", PortalGateColor.RANK_A),
    B("B", PortalGateColor.RANK_B),
    C("C", PortalGateColor.RANK_C),
    D("D", PortalGateColor.RANK_D),
    E("E", PortalGateColor.RANK_E);

    private final String letter;
    private final PortalGateColor color;

    GateRankTier(String letter, PortalGateColor color) {
        this.letter = letter;
        this.color = color;
    }

    @Nonnull
    public String letter() {
        return letter;
    }

    @Nonnull
    public PortalGateColor color() {
        return color;
    }

    @Nonnull
    public static GateRankTier fromRatio(double ratio) {
        if (ratio > 1.00D) {
            return S;
        }
        if (ratio >= 0.80D) {
            return A;
        }
        if (ratio >= 0.60D) {
            return B;
        }
        if (ratio >= 0.40D) {
            return C;
        }
        if (ratio >= 0.20D) {
            return D;
        }
        return E;
    }
}
