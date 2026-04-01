package com.airijko.endlessleveling.compatibility;

import com.airijko.endlessleveling.api.gates.DungeonGateContentProvider;
import com.airijko.endlessleveling.managers.AddonFilesManager;

import javax.annotation.Nonnull;
import java.util.List;

public final class AddonDungeonGateContentProvider implements DungeonGateContentProvider {

    private final AddonFilesManager filesManager;

    public AddonDungeonGateContentProvider(@Nonnull AddonFilesManager filesManager) {
        this.filesManager = filesManager;
    }

    @Nonnull
    @Override
    public String getProviderId() {
        return "endless-leveling-addon";
    }

    @Override
    public boolean isEnabled() {
        return filesManager.isDungeonGateEnabled();
    }

    @Override
    public int getSpawnIntervalMinutesMin() {
        return filesManager.getDungeonSpawnIntervalMinutesMin();
    }

    @Override
    public int getSpawnIntervalMinutesMax() {
        return filesManager.getDungeonSpawnIntervalMinutesMax();
    }

    @Override
    public int getGateDurationMinutes() {
        return filesManager.getDungeonDurationMinutes();
    }

    @Override
    public int getMaxConcurrentSpawns() {
        return filesManager.getDungeonMaxConcurrentSpawns();
    }

    @Nonnull
    @Override
    public List<String> getPortalWorldWhitelist() {
        return filesManager.getDungeonPortalWorldWhitelist();
    }
}
