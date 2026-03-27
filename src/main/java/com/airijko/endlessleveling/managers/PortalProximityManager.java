package com.airijko.endlessleveling.managers;

import com.airijko.endlessleveling.enums.GateRankTier;
import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
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

    private static final long SCAN_INTERVAL_MILLIS = 250L;
    private static final long TRIGGER_COOLDOWN_MILLIS = 2500L;
    private static final int HORIZONTAL_SCAN_RADIUS = 7;
    private static final int VERTICAL_SCAN_BELOW = 1;
    private static final int VERTICAL_SCAN_ABOVE = 4;

    private static final Map<UUID, Long> PLAYER_COOLDOWNS = new ConcurrentHashMap<>();

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
        plugin = null;
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
            if (plugin != null) {
                plugin.getLogger().at(Level.WARNING).withCause(ex).log("[ELPortal] Proximity scan tick failed");
            }
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
            return;
        }

        if (PortalLeveledInstanceRouter.enterPortalFromBlockId(playerRef, world, candidate.blockId())) {
            PLAYER_COOLDOWNS.put(playerId, now + TRIGGER_COOLDOWN_MILLIS);
            log(Level.INFO,
                    "[ELPortal] Proximity trigger player=%s world=%s block=%s at %d %d %d distance=%.2f",
                    playerRef.getUsername(),
                    world.getName(),
                    candidate.blockId(),
                    candidate.x(),
                    candidate.y(),
                    candidate.z(),
                    Math.sqrt(candidate.distanceSquared()));
        }
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
                    if (!isPortalBlockId(blockId)) {
                        continue;
                    }

                    double distanceSquared = distanceSquaredToPortal(position, x, y, z);
                    double triggerRadius = resolveTriggerRadius(blockId);
                    if (distanceSquared > triggerRadius * triggerRadius) {
                        continue;
                    }

                    if (nearest == null || distanceSquared < nearest.distanceSquared()) {
                        nearest = new PortalCandidate(blockId, x, y, z, distanceSquared);
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

    private static boolean isPortalBlockId(@Nullable String blockId) {
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
    private static String resolveBlockId(@Nullable Object blockType) {
        if (blockType == null) {
            return null;
        }
        try {
            Object id = blockType.getClass().getMethod("getId").invoke(blockType);
            return id instanceof String stringId ? stringId : null;
        } catch (ReflectiveOperationException ex) {
            log(Level.WARNING,
                    "[ELPortal] Failed to resolve block id from %s: %s",
                    blockType.getClass().getName(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return null;
        }
    }

    private static void log(@Nonnull Level level, @Nonnull String message, Object... args) {
        if (plugin != null) {
            if (args == null || args.length == 0) {
                plugin.getLogger().at(level).log(message);
            } else {
                plugin.getLogger().at(level).log(String.format(Locale.ROOT, message, args));
            }
        }
    }

    private record PortalCandidate(String blockId, int x, int y, int z, double distanceSquared) {
    }
}