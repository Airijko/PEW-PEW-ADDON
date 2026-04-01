package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.api.gates.DungeonWaveGateBridge;
import com.airijko.endlessleveling.api.gates.DungeonGateLifecycleBridge;
import com.airijko.endlessleveling.api.gates.TrackedDungeonGateSnapshot;
import com.airijko.endlessleveling.api.gates.TrackedWaveGateSnapshot;
import com.airijko.endlessleveling.api.gates.WaveGateRuntimeBridge;
import com.airijko.endlessleveling.api.gates.WaveGateSessionBridge;
import com.airijko.endlessleveling.api.gates.WaveGateSessionExecutorBridge;
import com.airijko.endlessleveling.api.gates.WaveSessionResults;
import com.airijko.endlessleveling.enums.GateRankTier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Addon-owned gates manager bridge published through EndlessLevelingAPI manager registry.
 */
public final class AddonGatesManager
    implements DungeonWaveGateBridge, WaveGateRuntimeBridge, DungeonGateLifecycleBridge, WaveGateSessionExecutorBridge {

    public static final AddonGatesManager INSTANCE = new AddonGatesManager();

    private AddonGatesManager() {
    }

    @Override
    public CompletableFuture<Boolean> spawnRandomGateNearPlayer(@Nonnull Player player, boolean isTestSpawn) {
        return NaturalPortalGateManager.spawnRandomGateNearPlayer(player, isTestSpawn);
    }

    @Override
    public CompletableFuture<Boolean> spawnGateNearPlayerWithRank(@Nonnull Player player,
            @Nonnull String rankTierId,
            boolean isTestSpawn) {
        GateRankTier rankTier = parseRankTier(rankTierId);
        if (rankTier == null) {
            return CompletableFuture.completedFuture(false);
        }
        return NaturalPortalGateManager.spawnGateNearPlayerWithRank(player, rankTier, isTestSpawn);
    }

    @Override
    public CompletableFuture<Boolean> spawnGateNearPlayerWithRankAndForcedLinkedWave(@Nonnull Player player,
            @Nonnull String rankTierId,
            boolean isTestSpawn) {
        GateRankTier rankTier = parseRankTier(rankTierId);
        if (rankTier == null) {
            return CompletableFuture.completedFuture(false);
        }
        return NaturalPortalGateManager.spawnGateNearPlayerWithRankAndForcedLinkedWave(player, rankTier, isTestSpawn);
    }

    @Override
    public String rollGateRankTierForPlayer(@Nonnull PlayerRef playerRef) {
        GateRankTier tier = NaturalPortalGateManager.rollGateRankTierForPlayer(playerRef);
        return tier == null ? null : tier.name();
    }

    @Override
    public List<TrackedDungeonGateSnapshot> listTrackedGates() {
        return NaturalPortalGateManager.listTrackedGates().stream()
                .map(gate -> new TrackedDungeonGateSnapshot(
                        gate.gateId(),
                        gate.worldUuid(),
                        gate.worldName(),
                        gate.blockId(),
                        gate.rankTier().name(),
                        gate.x(),
                        gate.y(),
                        gate.z()))
                .toList();
    }

    @Override
    public String resolveGateIdAt(@Nonnull World world, int x, int y, int z) {
        return NaturalPortalGateManager.resolveGateIdAt(world, x, y, z);
    }

    @Override
    public void forceRemoveGateAt(@Nonnull World world, int x, int y, int z, @Nonnull String blockId) {
        NaturalPortalGateManager.forceRemoveGateAt(world, x, y, z, blockId);
    }

    @Override
    public boolean isGateEntryLocked(@Nullable String gateIdentity) {
        return MobWaveManager.isGateEntryLocked(gateIdentity);
    }

    @Override
    public CompletableFuture<Boolean> startNaturalWaveForPlayer(@Nonnull Player player, @Nullable String preferredRankTierId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();

        World world = player.getWorld();
        if (world == null) {
            result.complete(false);
            return result;
        }

        world.execute(() -> {
            try {
                PlayerRef playerRef = resolvePlayerRef(player);
                if (playerRef == null) {
                    result.complete(false);
                    return;
                }

                GateRankTier tier = parseRankTier(preferredRankTierId);
                if (tier == null) {
                    tier = NaturalPortalGateManager.rollGateRankTierForPlayer(playerRef);
                }

                MobWaveManager.NaturalStartResult waveResult = MobWaveManager.startNaturalForPlayer(playerRef, tier);
                result.complete(waveResult != null && waveResult.scheduled);
            } catch (Exception ignored) {
                result.complete(false);
            }
        });

        return result;
    }

    @Override
    public WaveSessionResults.StartResult startWaveForPlayer(@Nonnull PlayerRef playerRef, @Nonnull String rankTierId) {
        GateRankTier tier = parseRankTier(rankTierId);
        if (tier == null) {
            return new WaveSessionResults.StartResult(false, "Invalid rank tier.", null, null, 0, 0, 0, 0, 0);
        }

        MobWaveManager.StartResult result = MobWaveManager.startForPlayer(playerRef, tier);
        String mappedTier = result.rankTier == null ? null : result.rankTier.name();
        return new WaveSessionResults.StartResult(
                result.started,
                result.message,
                result.roleName,
                mappedTier,
                result.waves,
                result.mobsPerWave,
                result.intervalSeconds,
                result.levelMin,
                result.levelMax);
    }

    @Override
    public WaveSessionResults.StopResult stopWaveForPlayer(@Nonnull PlayerRef playerRef) {
        MobWaveManager.StopResult result = MobWaveManager.stopForPlayer(playerRef);
        return new WaveSessionResults.StopResult(result.stopped, result.roleName);
    }

    @Override
    public WaveSessionResults.StatusResult getStatus(@Nonnull PlayerRef playerRef) {
        MobWaveManager.StatusResult result = MobWaveManager.getStatus(playerRef);
        if (result == null) {
            return null;
        }

        return new WaveSessionResults.StatusResult(
                result.roleName,
                result.rankTier.name(),
                result.waves,
                result.mobsPerWave,
                result.intervalSeconds,
                result.levelMin,
                result.levelMax);
    }

    @Override
    public WaveSessionResults.SkipResult skipWaveForPlayer(@Nonnull PlayerRef playerRef) {
        MobWaveManager.SkipResult result = MobWaveManager.skipWaveForPlayer(playerRef);
        return new WaveSessionResults.SkipResult(
                result.skipped,
                result.completed,
                result.killed,
                result.nextWave,
                result.totalWaves);
    }

    @Override
    public int clearLinkedGateCombosForPlayer(@Nonnull PlayerRef playerRef) {
        return MobWaveManager.clearLinkedGateCombosForPlayer(playerRef);
    }

    @Override
    public int clearWavePortalVisualsInPlayerWorld(@Nonnull PlayerRef playerRef) {
        return MobWaveManager.clearWavePortalVisualsInPlayerWorld(playerRef);
    }

    @Override
    public List<TrackedWaveGateSnapshot> listTrackedStandaloneWaves() {
        return MobWaveManager.listTrackedStandaloneWaves().stream()
                .map(AddonGatesManager::mapTrackedWave)
                .toList();
    }

    @Override
    public List<TrackedWaveGateSnapshot> listTrackedGateWaveCombos() {
        return MobWaveManager.listTrackedGateWaveCombos().stream()
                .map(AddonGatesManager::mapTrackedWave)
                .toList();
    }

    private static TrackedWaveGateSnapshot mapTrackedWave(MobWaveManager.TrackedWaveSnapshot snapshot) {
        return new TrackedWaveGateSnapshot(
                snapshot.ownerUuid(),
                snapshot.ownerName(),
                snapshot.rankTier().name(),
                snapshot.stage(),
                snapshot.kind(),
                snapshot.worldUuid(),
                snapshot.worldName(),
                snapshot.linkedGateId(),
                snapshot.x(),
                snapshot.y(),
                snapshot.z());
    }

    @Nullable
    private static PlayerRef resolvePlayerRef(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }

        Store<EntityStore> store = ref.getStore();
        return store.getComponent(ref, PlayerRef.getComponentType());
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
