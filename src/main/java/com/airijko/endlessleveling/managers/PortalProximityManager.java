package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class PortalProximityManager {

    private static final List<String> PORTAL_BASE_BLOCK_IDS = List.of(
            "EL_MajorDungeonPortal_D01",
            "EL_MajorDungeonPortal_D02",
            "EL_MajorDungeonPortal_D03",
            "EL_EndgamePortal_Swamp_Dungeon",
            "EL_EndgamePortal_Frozen_Dungeon",
            "EL_EndgamePortal_Golem_Void"
    );

    private static final List<String> RETURN_PORTAL_BLOCK_IDS = List.of(
            "Portal_Return",
            "Return_Portal"
    );

    private static final long SCAN_INTERVAL_MILLIS = 250L;
    private static final long TRIGGER_COOLDOWN_MILLIS = 2500L;
    private static final long ENTRY_GRACE_MILLIS = 8000L;
    private static final int HORIZONTAL_SCAN_RADIUS = 7;
    private static final int VERTICAL_SCAN_BELOW = 1;
    private static final int VERTICAL_SCAN_ABOVE = 4;
    private static final long RETURN_DEBUG_COOLDOWN_MILLIS = 5000L;
    private static final long INSTANCE_PORTAL_SCAN_DEBUG_COOLDOWN_MILLIS = 8000L;
    private static final int INSTANCE_PORTAL_SCAN_RADIUS = 9;
    private static final int INSTANCE_PORTAL_SCAN_VERTICAL_BELOW = 3;
    private static final int INSTANCE_PORTAL_SCAN_VERTICAL_ABOVE = 6;
    private static final int INSTANCE_PORTAL_SCAN_MAX_RESULTS = 10;

    private static final Map<UUID, Long> PLAYER_COOLDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> RETURN_DEBUG_COOLDOWNS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> INSTANCE_PORTAL_SCAN_DEBUG_COOLDOWNS = new ConcurrentHashMap<>();

    private static JavaPlugin plugin;
    private static ScheduledFuture<?> scanTask;

    private PortalProximityManager() {
    }

    public static void initialize(@Nonnull JavaPlugin owner) {
        plugin = owner;
        if (scanTask != null) {
            scanTask.cancel(false);
        }
        scanTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
                PortalProximityManager::scanTick,
                SCAN_INTERVAL_MILLIS,
                SCAN_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS
        );
        log(Level.INFO, "[ELPortal] Proximity portal scanner enabled: every %dms", SCAN_INTERVAL_MILLIS);
    }

    public static void shutdown() {
        if (scanTask != null) {
            scanTask.cancel(false);
            scanTask = null;
        }
        PLAYER_COOLDOWNS.clear();
        RETURN_DEBUG_COOLDOWNS.clear();
        INSTANCE_PORTAL_SCAN_DEBUG_COOLDOWNS.clear();
        plugin = null;
    }

    public static void markPlayerEnterInstance(@Nonnull UUID playerUuid) {
        PLAYER_COOLDOWNS.put(playerUuid, System.currentTimeMillis() + ENTRY_GRACE_MILLIS);
    }

    private static void scanTick() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            long now = System.currentTimeMillis();
            for (PlayerRef playerRef : universe.getPlayers()) {
                if (playerRef == null) {
                    continue;
                }

                UUID playerId = playerRef.getUuid();
                if (playerId == null || now < PLAYER_COOLDOWNS.getOrDefault(playerId, 0L)) {
                    continue;
                }

                UUID worldUuid = playerRef.getWorldUuid();
                if (worldUuid == null) {
                    continue;
                }

                World world = universe.getWorld(worldUuid);
                if (world == null) {
                    continue;
                }

                world.execute(() -> scanPlayer(world, playerRef, now));
            }
        } catch (Exception ex) {
            AddonLoggingManager.log(plugin, Level.WARNING, ex, "[ELPortal] Proximity scan tick failed");
        }
    }

    private static void scanPlayer(@Nonnull World world, @Nonnull PlayerRef playerRef, long now) {
        UUID playerId = playerRef.getUuid();
        if (playerId == null || now < PLAYER_COOLDOWNS.getOrDefault(playerId, 0L)) {
            return;
        }

        if (!playerRef.isValid() || playerRef.getTransform() == null || playerRef.getTransform().getPosition() == null) {
            return;
        }

        Vector3d position = playerRef.getTransform().getPosition();
        PortalCandidate candidate = findNearestPortal(world, position);
        if (candidate == null) {
            debugNearbyPortalLikeBlocks(world, playerRef, position, now);
            return;
        }

        boolean triggered;
        if (candidate.returnPortal()) {
            if (!PortalLeveledInstanceRouter.shouldHandleCustomReturnPortal(world)) {
                return;
            }

            if (PortalLeveledInstanceRouter.shouldSuppressImmediateReturnPortal(playerRef, world)) {
                return;
            }

            logReturnPortalProbe(world, playerRef, candidate, now);
            triggered = PortalLeveledInstanceRouter.returnPlayerToEntryPortal(playerRef, world);
            if (!triggered) {
                log(Level.WARNING,
                        "[ELPortal] Return portal detected but fallback did not trigger player=%s world=%s block=%s at %d %d %d",
                        playerRef.getUsername(),
                        world.getName(),
                        candidate.blockId(),
                        candidate.x(),
                        candidate.y(),
                        candidate.z());
            }
        } else {
            NaturalPortalGateManager.GateAnchor anchor = NaturalPortalGateManager.resolveTrackedGateAnchor(
                world,
                candidate.blockId(),
                candidate.x(),
                candidate.y(),
                candidate.z());
            triggered = PortalLeveledInstanceRouter.enterPortalFromBlock(
                    playerRef,
                    world,
                    candidate.blockId(),
                    anchor.x(),
                    anchor.y(),
                    anchor.z(),
                    anchor.gateId());
        }

        if (triggered) {
            PLAYER_COOLDOWNS.put(playerId, now + TRIGGER_COOLDOWN_MILLIS);
            log(Level.INFO,
                    "[ELPortal] Proximity trigger player=%s world=%s block=%s return=%s at %d %d %d distance=%.2f",
                    playerRef.getUsername(),
                    world.getName(),
                    candidate.blockId(),
                    candidate.returnPortal(),
                    candidate.x(),
                    candidate.y(),
                    candidate.z(),
                    Math.sqrt(candidate.distanceSquared()));
        }
    }

    private static void logReturnPortalProbe(@Nonnull World world,
                                             @Nonnull PlayerRef playerRef,
                                             @Nonnull PortalCandidate candidate,
                                             long now) {
        UUID playerId = playerRef.getUuid();
        if (playerId == null) {
            return;
        }

        long nextAllowed = RETURN_DEBUG_COOLDOWNS.getOrDefault(playerId, 0L);
        if (now < nextAllowed) {
            return;
        }
        RETURN_DEBUG_COOLDOWNS.put(playerId, now + RETURN_DEBUG_COOLDOWN_MILLIS);

        Vector3d pos = playerRef.getTransform() != null ? playerRef.getTransform().getPosition() : null;
        String playerPos = pos == null ? "unknown" : String.format(Locale.ROOT, "%.2f %.2f %.2f", pos.x, pos.y, pos.z);

        log(Level.INFO,
                "[ELPortal] Return portal candidate player=%s world=%s playerPos=%s block=%s at %d %d %d distance=%.2f",
                playerRef.getUsername(),
                world.getName(),
                playerPos,
                candidate.blockId(),
                candidate.x(),
                candidate.y(),
                candidate.z(),
                Math.sqrt(candidate.distanceSquared()));
    }

    private static void debugNearbyPortalLikeBlocks(@Nonnull World world,
                                                    @Nonnull PlayerRef playerRef,
                                                    @Nonnull Vector3d position,
                                                    long now) {
        String worldName = world.getName();
        if (worldName == null || !worldName.startsWith("instance-")) {
            return;
        }

        UUID playerId = playerRef.getUuid();
        if (playerId == null) {
            return;
        }

        long nextAllowed = INSTANCE_PORTAL_SCAN_DEBUG_COOLDOWNS.getOrDefault(playerId, 0L);
        if (now < nextAllowed) {
            return;
        }
        INSTANCE_PORTAL_SCAN_DEBUG_COOLDOWNS.put(playerId, now + INSTANCE_PORTAL_SCAN_DEBUG_COOLDOWN_MILLIS);

        int baseX = MathUtil.floor(position.x);
        int baseY = MathUtil.floor(position.y);
        int baseZ = MathUtil.floor(position.z);

        StringBuilder found = new StringBuilder();
        int matches = 0;
        for (int x = baseX - INSTANCE_PORTAL_SCAN_RADIUS; x <= baseX + INSTANCE_PORTAL_SCAN_RADIUS; x++) {
            for (int z = baseZ - INSTANCE_PORTAL_SCAN_RADIUS; z <= baseZ + INSTANCE_PORTAL_SCAN_RADIUS; z++) {
                if (world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z)) == null) {
                    continue;
                }

                for (int y = baseY - INSTANCE_PORTAL_SCAN_VERTICAL_BELOW; y <= baseY + INSTANCE_PORTAL_SCAN_VERTICAL_ABOVE; y++) {
                    String blockId = resolveBlockId(world.getBlockType(x, y, z));
                    if (!isPortalLikeId(blockId)) {
                        continue;
                    }

                    if (matches < INSTANCE_PORTAL_SCAN_MAX_RESULTS) {
                        double distance = Math.sqrt(distanceSquaredToPortal(position, x, y, z));
                        if (found.length() > 0) {
                            found.append(" | ");
                        }
                        found.append(String.format(Locale.ROOT,
                                "%s@(%d,%d,%d) d=%.2f",
                                blockId,
                                x,
                                y,
                                z,
                                distance));
                    }
                    matches++;
                }
            }
        }

        if (matches == 0) {
            log(Level.WARNING,
                    "[ELPortal] Instance portal debug: no portal-like blocks near player=%s world=%s at %.2f %.2f %.2f radius=%d",
                    playerRef.getUsername(),
                    worldName,
                    position.x,
                    position.y,
                    position.z,
                    INSTANCE_PORTAL_SCAN_RADIUS);
            return;
        }

        log(Level.INFO,
                "[ELPortal] Instance portal debug: nearby portal-like blocks player=%s world=%s total=%d sample=%s",
                playerRef.getUsername(),
                worldName,
                matches,
                found.toString());
    }

    @Nullable
    private static PortalCandidate findNearestPortal(@Nonnull World world, @Nonnull Vector3d position) {
        int baseX = MathUtil.floor(position.x);
        int baseY = MathUtil.floor(position.y);
        int baseZ = MathUtil.floor(position.z);

        PortalCandidate nearest = null;
        for (int x = baseX - HORIZONTAL_SCAN_RADIUS; x <= baseX + HORIZONTAL_SCAN_RADIUS; x++) {
            for (int z = baseZ - HORIZONTAL_SCAN_RADIUS; z <= baseZ + HORIZONTAL_SCAN_RADIUS; z++) {
                if (world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z)) == null) {
                    continue;
                }

                for (int y = baseY - VERTICAL_SCAN_BELOW; y <= baseY + VERTICAL_SCAN_ABOVE; y++) {
                    String blockId = resolveBlockId(world.getBlockType(x, y, z));
                    boolean returnPortal = isReturnPortalBlockId(blockId);
                    if (!returnPortal && !isRoutablePortalBlockId(blockId)) {
                        continue;
                    }

                    double distanceSquared = distanceSquaredToPortal(position, x, y, z);
                    double triggerRadius = returnPortal ? 2.0D : resolveTriggerRadius(blockId);
                    if (distanceSquared > triggerRadius * triggerRadius) {
                        continue;
                    }

                    if (nearest == null || distanceSquared < nearest.distanceSquared()) {
                        nearest = new PortalCandidate(blockId, x, y, z, distanceSquared, returnPortal);
                    }
                }
            }
        }
        return nearest;
    }

    private static double distanceSquaredToPortal(@Nonnull Vector3d position, int x, int y, int z) {
        double dx = position.x - (x + 0.5D);
        double dy = position.y - (y + 1.5D);
        double dz = position.z - (z + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isRoutablePortalBlockId(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        for (String baseBlockId : PORTAL_BASE_BLOCK_IDS) {
            if (baseBlockId.equals(blockId) || blockId.startsWith(baseBlockId + "_Rank")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReturnPortalBlockId(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }

        for (String exactId : RETURN_PORTAL_BLOCK_IDS) {
            if (exactId.equals(blockId)) {
                return true;
            }
        }

        String normalized = blockId.toLowerCase(Locale.ROOT);
        return normalized.contains("portal_return") || normalized.contains("return_portal");
    }

    private static boolean isPortalLikeId(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }

        if (isReturnPortalBlockId(blockId) || isRoutablePortalBlockId(blockId)) {
            return true;
        }

        String normalized = blockId.toLowerCase(Locale.ROOT);
        return normalized.contains("portal") || normalized.contains("return");
    }

    private static double resolveTriggerRadius(@Nonnull String blockId) {
        GateRankTier tier = resolveRankTier(blockId);
        return switch (tier) {
            case S -> 6.0D;
            case A -> 5.0D;
            case B -> 4.0D;
            case C -> 3.0D;
            case D -> 2.25D;
            case E -> 1.75D;
        };
    }

    @Nonnull
    private static GateRankTier resolveRankTier(@Nonnull String blockId) {
        for (GateRankTier tier : GateRankTier.values()) {
            String suffix = tier.blockIdSuffix();
            if (!suffix.isEmpty() && blockId.endsWith(suffix)) {
                return tier;
            }
        }
        return GateRankTier.E;
    }

    @Nullable
    private static String resolveBlockId(@Nullable BlockType blockType) {
        if (blockType == null) {
            return null;
        }
        return blockType.getId();
    }

    private static void log(@Nonnull Level level, @Nonnull String message, Object... args) {
        AddonLoggingManager.log(plugin, level, message, args);
    }

    private record PortalCandidate(String blockId,
                                   int x,
                                   int y,
                                   int z,
                                   double distanceSquared,
                                   boolean returnPortal) {
    }
}