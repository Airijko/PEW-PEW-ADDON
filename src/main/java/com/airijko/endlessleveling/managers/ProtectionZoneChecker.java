package com.airijko.endlessleveling.managers;

import javax.annotation.Nonnull;
import java.util.HashMap;

/**
 * Optional-dependency bridge that checks whether a candidate gate spawn position
 * is too close to a protected zone from OrbisGuard or a claimed chunk from
 * SimpleClaims.
 *
 * All calls to both APIs are wrapped in Throwable catches so this class is
 * completely inert when neither mod is installed.
 */
public final class ProtectionZoneChecker {

    /** Hytale uses 32-block square chunks. */
    private static final int CHUNK_BLOCKS = 32;

    private ProtectionZoneChecker() {
    }

    /**
     * Returns {@code true} if (x, z) in {@code worldName} is within
     * {@code minDistanceBlocks} of any OrbisGuard region or SimpleClaims chunk.
     * Returns {@code false} when either mod is unavailable or an error occurs.
     */
    public static boolean isBlockedByProtectionZone(@Nonnull String worldName,
                                                     int x,
                                                     int z,
                                                     int minDistanceBlocks) {
        if (minDistanceBlocks <= 0) {
            return false;
        }
        return isBlockedByOrbisGuard(worldName, x, z, minDistanceBlocks)
                || isBlockedBySimpleClaims(worldName, x, z, minDistanceBlocks);
    }

    // -----------------------------------------------------------------------
    // OrbisGuard
    // -----------------------------------------------------------------------

    private static boolean isBlockedByOrbisGuard(@Nonnull String worldName,
                                                  int x,
                                                  int z,
                                                  int minDistanceBlocks) {
        try {
            return checkOrbisGuard(worldName, x, z, minDistanceBlocks);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean checkOrbisGuard(@Nonnull String worldName,
                                            int x,
                                            int z,
                                            int minDistanceBlocks) {
        com.orbisguard.api.OrbisGuardAPI api = com.orbisguard.api.OrbisGuardAPI.getInstance();
        if (api == null) {
            return false;
        }

        com.orbisguard.api.region.IRegionManager manager =
                api.getRegionContainer().getRegionManager(worldName);
        if (manager == null) {
            return false;
        }

        for (com.orbisguard.api.region.IRegion region : manager.getRegions()) {
            if (region == null) {
                continue;
            }
            if (xzDistanceToOrbisRegion(x, z, region) < minDistanceBlocks) {
                return true;
            }
        }
        return false;
    }

    private static int xzDistanceToOrbisRegion(int x,
                                                int z,
                                                @Nonnull com.orbisguard.api.region.IRegion region) {
        com.orbisguard.api.BlockVector3 min = region.getMinimumPoint();
        com.orbisguard.api.BlockVector3 max = region.getMaximumPoint();
        // Clamp (x, z) to the region's XZ bounding box and measure Euclidean distance.
        int clampedX = Math.max(min.x(), Math.min(max.x(), x));
        int clampedZ = Math.max(min.z(), Math.min(max.z(), z));
        int dx = x - clampedX;
        int dz = z - clampedZ;
        return (int) Math.sqrt((double) (dx * dx + dz * dz));
    }

    // -----------------------------------------------------------------------
    // SimpleClaims
    // -----------------------------------------------------------------------

    private static boolean isBlockedBySimpleClaims(@Nonnull String worldName,
                                                    int x,
                                                    int z,
                                                    int minDistanceBlocks) {
        try {
            return checkSimpleClaims(worldName, x, z, minDistanceBlocks);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean checkSimpleClaims(@Nonnull String worldName,
                                              int x,
                                              int z,
                                              int minDistanceBlocks) {
        com.buuz135.simpleclaims.claim.ClaimManager manager =
                com.buuz135.simpleclaims.claim.ClaimManager.getInstance();
        if (manager == null) {
            return false;
        }

        HashMap<String, HashMap<String, com.buuz135.simpleclaims.claim.chunk.ChunkInfo>> allChunks =
                manager.getChunks();
        if (allChunks == null) {
            return false;
        }

        HashMap<String, com.buuz135.simpleclaims.claim.chunk.ChunkInfo> worldChunks =
                allChunks.get(worldName);
        if (worldChunks == null || worldChunks.isEmpty()) {
            return false;
        }

        for (com.buuz135.simpleclaims.claim.chunk.ChunkInfo chunk : worldChunks.values()) {
            if (chunk == null) {
                continue;
            }
            if (xzDistanceToChunk(x, z, chunk.getChunkX(), chunk.getChunkZ()) < minDistanceBlocks) {
                return true;
            }
        }
        return false;
    }

    private static int xzDistanceToChunk(int blockX, int blockZ, int chunkX, int chunkZ) {
        int minX = chunkX * CHUNK_BLOCKS;
        int maxX = minX + CHUNK_BLOCKS - 1;
        int minZ = chunkZ * CHUNK_BLOCKS;
        int maxZ = minZ + CHUNK_BLOCKS - 1;
        int clampedX = Math.max(minX, Math.min(maxX, blockX));
        int clampedZ = Math.max(minZ, Math.min(maxZ, blockZ));
        int dx = blockX - clampedX;
        int dz = blockZ - clampedZ;
        return (int) Math.sqrt((double) (dx * dx + dz * dz));
    }
}
