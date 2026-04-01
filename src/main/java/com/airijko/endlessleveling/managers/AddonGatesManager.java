package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.hypixel.hytale.server.core.entity.entities.Player;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;

/**
 * Addon-owned gates manager bridge published through EndlessLevelingAPI manager registry.
 */
public final class AddonGatesManager {

    public static final AddonGatesManager INSTANCE = new AddonGatesManager();

    private AddonGatesManager() {
    }

    public CompletableFuture<Boolean> spawnRandomGateNearPlayer(@Nonnull Player player, boolean isTestSpawn) {
        return NaturalPortalGateManager.spawnRandomGateNearPlayer(player, isTestSpawn);
    }

    public CompletableFuture<Boolean> spawnGateNearPlayerWithRank(@Nonnull Player player,
                                                                  @Nonnull GateRankTier rankTier,
                                                                  boolean isTestSpawn) {
        return NaturalPortalGateManager.spawnGateNearPlayerWithRank(player, rankTier, isTestSpawn);
    }
}
