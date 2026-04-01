package com.airijko.endlessleveling.compatibility;

import com.airijko.endlessleveling.api.gates.WaveGateContentProvider;
import com.airijko.endlessleveling.api.gates.WavePoolDefinition;
import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.managers.AddonFilesManager;
import com.airijko.endlessleveling.managers.WavePoolConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class AddonWaveGateContentProvider implements WaveGateContentProvider {

    private final AddonFilesManager filesManager;

    public AddonWaveGateContentProvider(@Nonnull AddonFilesManager filesManager) {
        this.filesManager = filesManager;
    }

    @Nonnull
    @Override
    public String getProviderId() {
        return "endless-leveling-addon";
    }

    @Nullable
    @Override
    public WavePoolDefinition loadWavePool(@Nonnull String rankTierId) {
        GateRankTier tier = parseRankTier(rankTierId);
        if (tier == null) {
            return null;
        }

        WavePoolConfig config = filesManager.loadWavePoolConfig(tier);
        if (config == null || config.isEmpty()) {
            return null;
        }

        List<WavePoolDefinition.Pool> pools = new ArrayList<>();
        for (WavePoolConfig.Pool pool : config.pools) {
            pools.add(new WavePoolDefinition.Pool(pool.id, pool.mobs));
        }
        return new WavePoolDefinition(pools, config.bossPool);
    }

    @Nullable
    private static GateRankTier parseRankTier(@Nullable String rankTierId) {
        if (rankTierId == null || rankTierId.isBlank()) {
            return null;
        }

        String normalized = rankTierId.trim().toUpperCase();
        for (GateRankTier candidate : GateRankTier.values()) {
            if (candidate.name().equalsIgnoreCase(normalized)
                    || candidate.letter().equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }

        return null;
    }
}
