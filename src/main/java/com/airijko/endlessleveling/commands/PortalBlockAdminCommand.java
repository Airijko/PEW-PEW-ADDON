package com.airijko.endlessleveling.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

public class PortalBlockAdminCommand extends AbstractCommand {

    private static final List<String> PORTAL_BASE_BLOCK_IDS = List.of(
            "EL_MajorDungeonPortal_D01",
            "EL_MajorDungeonPortal_D02",
            "EL_MajorDungeonPortal_D03",
            "EL_EndgamePortal_Swamp_Dungeon",
            "EL_EndgamePortal_Frozen_Dungeon",
            "EL_EndgamePortal_Golem_Void"
    );

    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 319;
    private static final int MAX_LIST_LINES = 30;
    private static final int AIR_BLOCK_ID = 0;
    private static final long PENDING_RETRY_INTERVAL_SECONDS = 15L;
    private static final int PENDING_CHUNKS_PER_WORLD_PER_TICK = 8;

    private static final Map<UUID, Set<Long>> PENDING_CHUNK_SWEEPS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PENDING_TASK_STARTED = new AtomicBoolean(false);
    private static ScheduledFuture<?> pendingTask;

    public PortalBlockAdminCommand() {
        super("portalblocks", "List and remove placed portal blocks in your current world");
        this.addAliases("portalblock", "elportalblocks", "gateblocks");
        this.addSubCommand(new ListSubCommand());
        this.addSubCommand(new RemoveNearestSubCommand());
        this.addSubCommand(new RemoveAllSubCommand());
        ensurePendingRetryTask();
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /portalblocks <list|remove-nearest|remove-all>").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }

    @Nonnull
    private static CompletableFuture<Void> runInPlayerWorld(@Nonnull CommandContext context,
                                                            @Nonnull WorldTask task) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        World world = player.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("You are not in a world right now.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                task.run(context, player, world);
            } catch (Exception ex) {
                context.sendMessage(Message.raw("Portal block operation failed.").color("#ff6666"));
            }
            future.complete(null);
        });
        return future;
    }

    @Nullable
    private static Vector3d resolvePlayerPosition(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }

        Store<EntityStore> store = ref.getStore();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        return transform == null ? null : transform.getPosition();
    }

    @Nullable
    private static UUID resolvePlayerWorldUuid(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }

        Store<EntityStore> store = ref.getStore();
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : playerRef.getWorldUuid();
    }

    @Nonnull
    private static List<PortalBlockHit> scanPortalBlocks(@Nonnull World world) {
        List<PortalBlockHit> hits = new ArrayList<>();
        Set<Integer> portalBlockIntIds = resolvePortalBlockIntIds();
        if (portalBlockIntIds.isEmpty()) {
            return hits;
        }

        for (Long chunkIndexObj : world.getChunkStore().getChunkIndexes()) {
            if (chunkIndexObj == null) {
                continue;
            }

            long chunkIndex = chunkIndexObj;
            WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            if (chunk == null) {
                continue;
            }

            int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
            int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
            int minX = chunkX << 5;
            int minZ = chunkZ << 5;

            for (int y = WORLD_MIN_Y; y <= WORLD_MAX_Y; y++) {
                for (int x = minX; x < minX + 32; x++) {
                    for (int z = minZ; z < minZ + 32; z++) {
                        int blockIntId = chunk.getBlock(x, y, z);
                        if (blockIntId == 0 || !portalBlockIntIds.contains(blockIntId)) {
                            continue;
                        }

                        BlockType blockType = BlockType.getAssetMap().getAsset(blockIntId);
                        if (blockType == null || blockType.getId() == null) {
                            continue;
                        }

                        String blockId = blockType.getId();
                        if (isPortalBlockId(blockId)) {
                            hits.add(new PortalBlockHit(blockId, x, y, z));
                        }
                    }
                }
            }
        }

        return hits;
    }

    @Nonnull
    private static Set<Integer> resolvePortalBlockIntIds() {
        Set<Integer> blockIds = new HashSet<>();
        for (String baseBlockId : PORTAL_BASE_BLOCK_IDS) {
            addBlockIntId(blockIds, baseBlockId);
            addBlockIntId(blockIds, baseBlockId + "_RankD");
            addBlockIntId(blockIds, baseBlockId + "_RankC");
            addBlockIntId(blockIds, baseBlockId + "_RankB");
            addBlockIntId(blockIds, baseBlockId + "_RankA");
            addBlockIntId(blockIds, baseBlockId + "_RankS");
        }
        return blockIds;
    }

    private static void addBlockIntId(@Nonnull Set<Integer> blockIds, @Nonnull String blockTypeKey) {
        int index = BlockType.getAssetMap().getIndex(blockTypeKey);
        if (index != Integer.MIN_VALUE) {
            blockIds.add(index);
        }
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

    private static boolean removePortalBlock(@Nonnull World world, @Nonnull PortalBlockHit hit) {
        try {
            WorldChunk chunk = world.getChunkIfInMemory(ChunkUtil.indexChunkFromBlock(hit.x(), hit.z()));
            if (chunk == null) {
                return false;
            }
            chunk.setBlock(hit.x(), hit.y(), hit.z(), AIR_BLOCK_ID);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int sweepChunkForPortals(@Nonnull WorldChunk chunk,
                                            long chunkIndex,
                                            @Nonnull Set<Integer> portalBlockIntIds) {
        if (portalBlockIntIds.isEmpty()) {
            return 0;
        }

        int removed = 0;
        int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
        int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
        int minX = chunkX << 5;
        int minZ = chunkZ << 5;

        for (int y = WORLD_MIN_Y; y <= WORLD_MAX_Y; y++) {
            for (int x = minX; x < minX + 32; x++) {
                for (int z = minZ; z < minZ + 32; z++) {
                    int blockIntId = chunk.getBlock(x, y, z);
                    if (!portalBlockIntIds.contains(blockIntId)) {
                        continue;
                    }
                    chunk.setBlock(x, y, z, AIR_BLOCK_ID);
                    removed++;
                }
            }
        }

        return removed;
    }

    private static void addPendingChunk(@Nonnull UUID worldUuid, long chunkIndex) {
        PENDING_CHUNK_SWEEPS.computeIfAbsent(worldUuid, ignored -> ConcurrentHashMap.newKeySet()).add(chunkIndex);
    }

    private static int pendingChunkCount(@Nonnull UUID worldUuid) {
        Set<Long> pending = PENDING_CHUNK_SWEEPS.get(worldUuid);
        return pending == null ? 0 : pending.size();
    }

    private static void ensurePendingRetryTask() {
        if (!PENDING_TASK_STARTED.compareAndSet(false, true)) {
            return;
        }

        pendingTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
                PortalBlockAdminCommand::processPendingSweeps,
                PENDING_RETRY_INTERVAL_SECONDS,
                PENDING_RETRY_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    private static void processPendingSweeps() {
        Universe universe = Universe.get();
        if (universe == null || PENDING_CHUNK_SWEEPS.isEmpty()) {
            return;
        }

        for (Map.Entry<UUID, Set<Long>> entry : PENDING_CHUNK_SWEEPS.entrySet()) {
            UUID worldUuid = entry.getKey();
            Set<Long> pending = entry.getValue();
            if (pending == null || pending.isEmpty()) {
                continue;
            }

            World world = universe.getWorld(worldUuid);
            if (world == null) {
                continue;
            }

            Set<Integer> portalBlockIntIds = resolvePortalBlockIntIds();
            if (portalBlockIntIds.isEmpty()) {
                continue;
            }

            world.execute(() -> {
                int processed = 0;
                List<Long> toRemove = new ArrayList<>();
                for (Long chunkIndexObj : new ArrayList<>(pending)) {
                    if (chunkIndexObj == null) {
                        continue;
                    }
                    long chunkIndex = chunkIndexObj;
                    WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
                    if (chunk == null) {
                        continue;
                    }

                    sweepChunkForPortals(chunk, chunkIndex, portalBlockIntIds);
                    toRemove.add(chunkIndex);
                    processed++;
                    if (processed >= PENDING_CHUNKS_PER_WORLD_PER_TICK) {
                        break;
                    }
                }

                if (!toRemove.isEmpty()) {
                    pending.removeAll(toRemove);
                }
                if (pending.isEmpty()) {
                    PENDING_CHUNK_SWEEPS.remove(worldUuid);
                }
            });
        }
    }

    private static final class ListSubCommand extends AbstractCommand {

        private ListSubCommand() {
            super("list", "List all portal blocks currently loaded in this world");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runInPlayerWorld(context, (ctx, player, world) -> {
                List<PortalBlockHit> hits = scanPortalBlocks(world);
                if (hits.isEmpty()) {
                    ctx.sendMessage(Message.raw("No portal blocks found in loaded chunks.").color("#ffcc66"));
                    return;
                }

                ctx.sendMessage(Message.raw("Found " + hits.size() + " portal block(s) in loaded chunks:")
                        .color("#6cff78"));

                int shown = Math.min(MAX_LIST_LINES, hits.size());
                for (int i = 0; i < shown; i++) {
                    PortalBlockHit hit = hits.get(i);
                    ctx.sendMessage(Message.raw(String.format("[%d] %s @ %d, %d, %d",
                            i + 1,
                            hit.blockId(),
                            hit.x(),
                            hit.y(),
                            hit.z())).color("#b8d0ff"));
                }

                if (hits.size() > shown) {
                    ctx.sendMessage(Message.raw("...and " + (hits.size() - shown) + " more (truncated)")
                            .color("#ffcc66"));
                }
            });
        }
    }

    private static final class RemoveNearestSubCommand extends AbstractCommand {

        private RemoveNearestSubCommand() {
            super("remove-nearest", "Remove the nearest placed portal block in your current world");
            this.addAliases("remove", "rm");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return runInPlayerWorld(context, (ctx, player, world) -> {
                Vector3d playerPos = resolvePlayerPosition(player);
                if (playerPos == null) {
                    ctx.sendMessage(Message.raw("Could not resolve your position right now.").color("#ff6666"));
                    return;
                }

                List<PortalBlockHit> hits = scanPortalBlocks(world);
                if (hits.isEmpty()) {
                    ctx.sendMessage(Message.raw("No portal blocks found to remove.").color("#ffcc66"));
                    return;
                }

                PortalBlockHit nearest = null;
                double nearestDistanceSquared = Double.MAX_VALUE;
                for (PortalBlockHit hit : hits) {
                    double dx = playerPos.x - (hit.x() + 0.5D);
                    double dy = playerPos.y - (hit.y() + 0.5D);
                    double dz = playerPos.z - (hit.z() + 0.5D);
                    double distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (distanceSquared < nearestDistanceSquared) {
                        nearestDistanceSquared = distanceSquared;
                        nearest = hit;
                    }
                }

                if (nearest == null) {
                    ctx.sendMessage(Message.raw("No portal blocks found to remove.").color("#ffcc66"));
                    return;
                }

                if (removePortalBlock(world, nearest)) {
                    ctx.sendMessage(Message.raw("Removed portal block: " + nearest.blockId()
                            + " @ " + nearest.x() + ", " + nearest.y() + ", " + nearest.z())
                            .color("#6cff78"));
                } else {
                    ctx.sendMessage(Message.raw("Failed to remove nearest portal block at "
                            + nearest.x() + ", " + nearest.y() + ", " + nearest.z())
                            .color("#ff6666"));
                }
            });
        }
    }

    private static final class RemoveAllSubCommand extends AbstractCommand {

        private RemoveAllSubCommand() {
            super("remove-all", "Remove all placed portal blocks in your current world");
            this.addAliases("clear", "purge");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            if (!(context.sender() instanceof Player player)) {
                context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
                return CompletableFuture.completedFuture(null);
            }

            World world = player.getWorld();
            if (world == null) {
                context.sendMessage(Message.raw("You are not in a world right now.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            UUID worldUuid = resolvePlayerWorldUuid(player);
            if (worldUuid == null) {
                context.sendMessage(Message.raw("Could not resolve world identity for cleanup.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            context.sendMessage(Message.raw("Starting all-chunks portal cleanup (this may take a moment)...")
                    .color("#ffcc66"));

            return CompletableFuture.runAsync(() -> {
                Set<Integer> portalBlockIntIds = resolvePortalBlockIntIds();
                if (portalBlockIntIds.isEmpty()) {
                    context.sendMessage(Message.raw("No known portal block IDs are currently registered.")
                            .color("#ff6666"));
                    return;
                }

                Set<Long> chunkIndexes = new HashSet<>();
                for (Long chunkIndexObj : world.getChunkStore().getChunkIndexes()) {
                    if (chunkIndexObj != null) {
                        chunkIndexes.add(chunkIndexObj);
                    }
                }

                if (chunkIndexes.isEmpty()) {
                    context.sendMessage(Message.raw("No chunks known for this world right now.").color("#ffcc66"));
                    return;
                }

                AtomicInteger removed = new AtomicInteger();
                AtomicInteger sweptChunks = new AtomicInteger();
                AtomicInteger pendingChunks = new AtomicInteger();

                for (long chunkIndex : chunkIndexes) {
                    int removedInChunk = world.getNonTickingChunkAsync(chunkIndex)
                            .thenApplyAsync(chunk -> {
                                if (chunk == null) {
                                    return Integer.MIN_VALUE;
                                }
                                return sweepChunkForPortals(chunk, chunkIndex, portalBlockIntIds);
                            }, world)
                            .exceptionally(ex -> Integer.MIN_VALUE)
                            .join();

                    if (removedInChunk == Integer.MIN_VALUE) {
                        addPendingChunk(worldUuid, chunkIndex);
                        pendingChunks.incrementAndGet();
                        continue;
                    }

                    sweptChunks.incrementAndGet();
                    removed.addAndGet(removedInChunk);
                }

                int pendingTotal = pendingChunkCount(worldUuid);
                if (pendingTotal > 0) {
                    context.sendMessage(Message.raw("Removed " + removed.get() + " portal block(s) across "
                                    + sweptChunks.get() + " chunk(s). Pending retries for " + pendingTotal
                                    + " chunk(s) until they are available.")
                            .color("#ffcc66"));
                } else {
                    context.sendMessage(Message.raw("Removed " + removed.get() + " portal block(s) across "
                                    + sweptChunks.get() + " chunk(s).")
                            .color("#6cff78"));
                }
            });
        }
    }

    @FunctionalInterface
    private interface WorldTask {
        void run(@Nonnull CommandContext context, @Nonnull Player player, @Nonnull World world);
    }

    private record PortalBlockHit(String blockId, int x, int y, int z) {
    }
}
