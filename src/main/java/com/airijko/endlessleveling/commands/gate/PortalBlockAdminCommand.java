package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.airijko.endlessleveling.managers.GateInstancePersistenceManager;
import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
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
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

        private static final List<String> RETURN_PORTAL_BLOCK_IDS = List.of(
            "Portal_Return",
            "Return_Portal"
        );

    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 319;
    private static final int MAX_LIST_LINES = 30;
    private static final int AIR_BLOCK_ID = 0;
    private static final long PENDING_RETRY_INTERVAL_SECONDS = 15L;
    private static final int PENDING_CHUNKS_PER_WORLD_PER_TICK = 8;
        private static final int[][] NEIGHBOR_OFFSETS = new int[][]{
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
        };

    private static final Map<UUID, Set<Long>> PENDING_CHUNK_SWEEPS = new ConcurrentHashMap<>();
    private static final AtomicBoolean PENDING_TASK_STARTED = new AtomicBoolean(false);
    private static ScheduledFuture<?> pendingTask;

    public PortalBlockAdminCommand() {
        super("blocks", "List and remove placed portal blocks in your current world");
        this.addAliases("portalblocks", "portalblock", "elportalblocks", "gateblocks");
        this.addSubCommand(new ListSubCommand());
        this.addSubCommand(new RemoveNearestSubCommand());
        this.addSubCommand(new RemoveAllSubCommand());
        ensurePendingRetryTask();
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /gate dungeon blocks <list|remove-nearest|remove-all>").color("#ffcc66"));
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
        for (String returnBlockId : RETURN_PORTAL_BLOCK_IDS) {
            addBlockIntId(blockIds, returnBlockId);
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
        for (String returnBlockId : RETURN_PORTAL_BLOCK_IDS) {
            if (returnBlockId.equals(blockId)) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static List<PortalStructure> sortStructuresForDisplay(@Nonnull List<PortalStructure> source) {
        List<PortalStructure> sorted = new ArrayList<>(source);
        sorted.sort((left, right) -> {
            PortalBlockHit a = left.anchor();
            PortalBlockHit b = right.anchor();
            int yCompare = Integer.compare(a.y(), b.y());
            if (yCompare != 0) {
                return yCompare;
            }
            int xCompare = Integer.compare(a.x(), b.x());
            if (xCompare != 0) {
                return xCompare;
            }
            int zCompare = Integer.compare(a.z(), b.z());
            if (zCompare != 0) {
                return zCompare;
            }
            return left.baseId().compareTo(right.baseId());
        });
        return sorted;
    }

    @Nonnull
    private static String normalizePortalBaseId(@Nonnull String blockId) {
        for (String baseBlockId : PORTAL_BASE_BLOCK_IDS) {
            if (blockId.equals(baseBlockId) || blockId.startsWith(baseBlockId + "_Rank")) {
                return baseBlockId;
            }
        }
        for (String returnBlockId : RETURN_PORTAL_BLOCK_IDS) {
            if (returnBlockId.equals(blockId)) {
                return returnBlockId;
            }
        }
        return blockId;
    }

    @Nonnull
    private static List<PortalStructure> scanPortalStructures(@Nonnull World world) {
        List<PortalBlockHit> hits = scanPortalBlocks(world);
        if (hits.isEmpty()) {
            return List.of();
        }

        Map<BlockPos, PortalBlockHit> byPos = new ConcurrentHashMap<>();
        for (PortalBlockHit hit : hits) {
            byPos.put(new BlockPos(hit.x(), hit.y(), hit.z()), hit);
        }

        Set<BlockPos> visited = new HashSet<>();
        List<PortalStructure> structures = new ArrayList<>();

        for (PortalBlockHit hit : hits) {
            BlockPos start = new BlockPos(hit.x(), hit.y(), hit.z());
            if (!visited.add(start)) {
                continue;
            }

            String baseId = normalizePortalBaseId(hit.blockId());
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);

            List<PortalBlockHit> cluster = new ArrayList<>();
            cluster.add(hit);

            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    BlockPos neighbor = new BlockPos(
                            current.x() + offset[0],
                            current.y() + offset[1],
                            current.z() + offset[2]);
                    if (visited.contains(neighbor)) {
                        continue;
                    }

                    PortalBlockHit neighborHit = byPos.get(neighbor);
                    if (neighborHit == null) {
                        continue;
                    }
                    if (!baseId.equals(normalizePortalBaseId(neighborHit.blockId()))) {
                        continue;
                    }

                    visited.add(neighbor);
                    queue.add(neighbor);
                    cluster.add(neighborHit);
                }
            }

            PortalBlockHit anchor = cluster.get(0);
            for (PortalBlockHit block : cluster) {
                if (block.y() < anchor.y()) {
                    anchor = block;
                } else if (block.y() == anchor.y() && block.x() < anchor.x()) {
                    anchor = block;
                } else if (block.y() == anchor.y() && block.x() == anchor.x() && block.z() < anchor.z()) {
                    anchor = block;
                }
            }

            structures.add(new PortalStructure(baseId, anchor.blockId(), anchor, cluster));
        }

        return structures;
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
                List<PortalStructure> structures = sortStructuresForDisplay(scanPortalStructures(world));

                UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
                List<ParsedGateIdentity> persistedUnloaded = new ArrayList<>();
                int persistedTotalForWorld = 0;
                int persistedLoadedForWorld = 0;

                if (worldUuid != null) {
                    Set<String> loadedGateKeys = new HashSet<>();
                    for (PortalStructure structure : structures) {
                        PortalBlockHit anchor = structure.anchor();
                        String gateId = NaturalPortalGateManager.resolveGateIdAt(world, anchor.x(), anchor.y(), anchor.z());
                        if (gateId == null || gateId.isBlank()) {
                            gateId = "el_gate:" + worldUuid + ":" + anchor.x() + ":" + anchor.y() + ":" + anchor.z();
                        }
                        loadedGateKeys.add(canonicalizeGateKey(gateId));
                    }

                    for (GateInstancePersistenceManager.StoredGateInstance stored
                            : GateInstancePersistenceManager.listSavedInstancesById()) {
                        ParsedGateIdentity parsed = parseGateIdentity(stored.gateKey);
                        if (parsed == null || !worldUuid.equals(parsed.worldUuid())) {
                            continue;
                        }

                        persistedTotalForWorld++;
                        String canonicalStoredGateKey = canonicalizeGateKey(stored.gateKey);
                        if (loadedGateKeys.contains(canonicalStoredGateKey)) {
                            persistedLoadedForWorld++;
                        } else {
                            persistedUnloaded.add(parsed);
                        }
                    }
                }

                if (structures.isEmpty()) {
                    ctx.sendMessage(Message.raw("No portal blocks found in loaded chunks.").color("#ffcc66"));
                    if (persistedTotalForWorld > 0) {
                        ctx.sendMessage(Message.raw("Persisted gates for this world: " + persistedTotalForWorld
                                        + " (loaded=" + persistedLoadedForWorld
                                        + ", unloaded=" + persistedUnloaded.size() + ")")
                                .color("#ffbb44"));
                        if (!persistedUnloaded.isEmpty()) {
                            ctx.sendMessage(Message.raw("Unloaded persisted gate coords: "
                                            + formatPersistedGateCoords(persistedUnloaded))
                                    .color("#ffbb44"));
                        }
                    }
                    return;
                }

                int totalBlocks = 0;
                for (PortalStructure structure : structures) {
                    totalBlocks += structure.blocks().size();
                }

                ctx.sendMessage(Message.raw("Portal Structures (loaded): " + structures.size()
                                + " structures / " + totalBlocks + " blocks")
                        .color("#6cff78").bold(true));

                int shown = Math.min(MAX_LIST_LINES, structures.size());
                for (int i = 0; i < shown; i++) {
                    PortalStructure structure = structures.get(i);
                    PortalBlockHit anchor = structure.anchor();
                    ctx.sendMessage(Message.join(
                            Message.raw(String.format("[%d] %s", i + 1, structure.displayBlockId())).color("#b8d0ff"),
                            Message.raw("\n"),
                            Message.raw(String.format("    Pos: %d, %d, %d | Blocks: %d",
                                    anchor.x(),
                                    anchor.y(),
                                    anchor.z(),
                                    structure.blocks().size())).color("#9eb3d6")
                    ));
                }

                if (structures.size() > shown) {
                    ctx.sendMessage(Message.raw("...and " + (structures.size() - shown) + " more (truncated)")
                            .color("#ffcc66"));
                }

                if (persistedTotalForWorld > 0) {
                    ctx.sendMessage(Message.raw("Persisted gates for this world: " + persistedTotalForWorld
                                    + " (loaded=" + persistedLoadedForWorld
                                    + ", unloaded=" + persistedUnloaded.size() + ")")
                            .color("#ffbb44"));
                    if (!persistedUnloaded.isEmpty()) {
                        ctx.sendMessage(Message.raw("Unloaded persisted gate coords: "
                                        + formatPersistedGateCoords(persistedUnloaded))
                                .color("#ffbb44"));
                    }
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

                List<PortalStructure> structures = scanPortalStructures(world);
                if (structures.isEmpty()) {
                    ctx.sendMessage(Message.raw("No portal blocks found to remove.").color("#ffcc66"));
                    return;
                }

                PortalStructure nearest = null;
                double nearestDistanceSquared = Double.MAX_VALUE;
                for (PortalStructure structure : structures) {
                    double distanceSquared = structure.distanceSquaredTo(playerPos);
                    if (distanceSquared < nearestDistanceSquared) {
                        nearestDistanceSquared = distanceSquared;
                        nearest = structure;
                    }
                }

                if (nearest == null) {
                    ctx.sendMessage(Message.raw("No portal blocks found to remove.").color("#ffcc66"));
                    return;
                }

                // Kick players, remove the paired instance, and clear disk entry first.
                PortalBlockHit anchor = nearest.anchor();
                NaturalPortalGateManager.forceRemoveGateAt(world, anchor.x(), anchor.y(), anchor.z(), anchor.blockId());

                int removed = 0;
                for (PortalBlockHit block : nearest.blocks()) {
                    if (removePortalBlock(world, block)) {
                        removed++;
                    }
                }

                if (removed > 0) {
                    ctx.sendMessage(Message.raw("Removed portal structure: " + nearest.displayBlockId()
                            + " @ " + anchor.x() + ", " + anchor.y() + ", " + anchor.z()
                            + " (" + removed + "/" + nearest.blocks().size() + " blocks)")
                            .color("#6cff78"));
                } else {
                    ctx.sendMessage(Message.raw("Failed to remove nearest portal structure at "
                            + anchor.x() + ", " + anchor.y() + ", " + anchor.z())
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

            CompletableFuture<Void> future = new CompletableFuture<>();
            world.execute(() -> {
                UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
                if (worldUuid == null) {
                    context.sendMessage(Message.raw("Could not resolve world identity for cleanup.").color("#ff6666"));
                    future.complete(null);
                    return;
                }

                Set<Integer> portalBlockIntIds = resolvePortalBlockIntIds();
                if (portalBlockIntIds.isEmpty()) {
                    context.sendMessage(Message.raw("No known portal block IDs are currently registered.")
                            .color("#ff6666"));
                    future.complete(null);
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
                    future.complete(null);
                    return;
                }

                context.sendMessage(Message.raw("Running remove-all portal cleanup across all known chunks...")
                        .color("#ffcc66"));

                // Remove any persisted gate-instance associations for this world first.
                // This also tears down paired instances and kicks players from those instances.
                int persistenceCleanupCount = 0;
                for (GateInstancePersistenceManager.StoredGateInstance stored
                    : GateInstancePersistenceManager.listSavedInstancesById()) {
                    ParsedGateIdentity parsed = parseGateIdentity(stored.gateKey);
                    if (parsed == null || !worldUuid.equals(parsed.worldUuid())) {
                    continue;
                    }

                    String blockId = stored.blockId == null || stored.blockId.isBlank()
                        ? "<unknown>"
                        : stored.blockId;
                    PortalLeveledInstanceRouter.cleanupGateInstanceByIdentity(stored.gateKey, blockId);
                    persistenceCleanupCount++;
                }
                // Kick players and tear down instances for every tracked gate in this world
                // before the block sweep clears the portal block markers.
                List<PortalStructure> structures = scanPortalStructures(world);
                for (PortalStructure structure : structures) {
                    PortalBlockHit anchor = structure.anchor();
                    NaturalPortalGateManager.forceRemoveGateAt(world, anchor.x(), anchor.y(), anchor.z(), anchor.blockId());
                }
                final int persistedCleanupCount = persistenceCleanupCount;
                int trackedCleanupCount = structures.size();

                CompletableFuture.runAsync(() -> {
                    AtomicInteger removed = new AtomicInteger();
                    AtomicInteger sweptChunks = new AtomicInteger();
                    List<Long> queuedChunkIndexes = new ArrayList<>();

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
                            queuedChunkIndexes.add(chunkIndex);
                            continue;
                        }

                        sweptChunks.incrementAndGet();
                        removed.addAndGet(removedInChunk);
                    }

                    world.execute(() -> {
                        int pendingTotal = pendingChunkCount(worldUuid);
                        int queuedNow = queuedChunkIndexes.size();
                        context.sendMessage(Message.raw("Remove-all cleanup complete:")
                            .color("#6cff78"));
                        context.sendMessage(Message.raw("- Persisted gate-instance associations removed: "
                                + persistedCleanupCount)
                            .color("#ffbb44"));
                        context.sendMessage(Message.raw("- Tracked gate instances removed: "
                                + trackedCleanupCount + " (players kicked)")
                            .color("#ffbb44"));
                        context.sendMessage(Message.raw("- Portal blocks removed: " + removed.get()
                                + " across " + sweptChunks.get() + " chunk(s)")
                            .color("#6cff78"));

                        if (pendingTotal > 0) {
                            context.sendMessage(Message.raw("- Pending retries: " + pendingTotal
                                    + " chunk(s) still unavailable")
                                .color("#ffcc66"));
                        }

                        if (queuedNow > 0) {
                            context.sendMessage(Message.raw("- Deferred portal deletion queued for " + queuedNow
                                    + " chunk(s). Portal block coords: "
                                            + formatQueuedChunkCoords(queuedChunkIndexes))
                                    .color("#ffbb44"));
                        }

                        future.complete(null);
                    });
                });
            });

            return future;
        }
    }

    @FunctionalInterface
    private interface WorldTask {
        void run(@Nonnull CommandContext context, @Nonnull Player player, @Nonnull World world);
    }

    private record BlockPos(int x, int y, int z) {
    }

    private record PortalBlockHit(String blockId, int x, int y, int z) {
    }

    private record PortalStructure(@Nonnull String baseId,
                                   @Nonnull String displayBlockId,
                                   @Nonnull PortalBlockHit anchor,
                                   @Nonnull List<PortalBlockHit> blocks) {
        private double distanceSquaredTo(@Nonnull Vector3d position) {
            double best = Double.MAX_VALUE;
            for (PortalBlockHit block : blocks) {
                double dx = position.x - (block.x() + 0.5D);
                double dy = position.y - (block.y() + 0.5D);
                double dz = position.z - (block.z() + 0.5D);
                double current = dx * dx + dy * dy + dz * dz;
                if (current < best) {
                    best = current;
                }
            }
            return best;
        }
    }

    @Nullable
    private static ParsedGateIdentity parseGateIdentity(@Nullable String gateKey) {
        if (gateKey == null || gateKey.isBlank()) {
            return null;
        }

        String normalized = gateKey.startsWith("el_gate:")
                ? gateKey.substring("el_gate:".length())
                : gateKey;
        String[] parts = normalized.split(":", 4);
        if (parts.length < 4) {
            return null;
        }

        try {
            UUID worldUuid = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new ParsedGateIdentity(worldUuid, x, y, z);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nonnull
    private static String formatQueuedChunkCoords(@Nonnull List<Long> chunkIndexes) {
        if (chunkIndexes.isEmpty()) {
            return "<none>";
        }

        List<Long> sorted = new ArrayList<>(chunkIndexes);
        sorted.sort(Long::compareTo);

        int limit = Math.min(10, sorted.size());
        List<String> parts = new ArrayList<>(limit + 1);
        for (int i = 0; i < limit; i++) {
            long chunkIndex = sorted.get(i);
            int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
            int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
            int blockX = chunkX << 5;
            int blockZ = chunkZ << 5;
            parts.add("[" + blockX + "," + blockZ + "]");
        }

        if (sorted.size() > limit) {
            parts.add("... +" + (sorted.size() - limit) + " more");
        }
        return String.join(" ", parts);
    }

    @Nonnull
    private static String formatPersistedGateCoords(@Nonnull List<ParsedGateIdentity> gates) {
        if (gates.isEmpty()) {
            return "<none>";
        }

        List<ParsedGateIdentity> sorted = new ArrayList<>(gates);
        sorted.sort((left, right) -> {
            int xCompare = Integer.compare(left.x(), right.x());
            if (xCompare != 0) {
                return xCompare;
            }
            int yCompare = Integer.compare(left.y(), right.y());
            if (yCompare != 0) {
                return yCompare;
            }
            return Integer.compare(left.z(), right.z());
        });

        int limit = Math.min(10, sorted.size());
        List<String> parts = new ArrayList<>(limit + 1);
        for (int i = 0; i < limit; i++) {
            ParsedGateIdentity gate = sorted.get(i);
            parts.add("[" + gate.x() + "," + gate.y() + "," + gate.z() + "]");
        }
        if (sorted.size() > limit) {
            parts.add("... +" + (sorted.size() - limit) + " more");
        }
        return String.join(" ", parts);
    }

    @Nonnull
    private static String canonicalizeGateKey(@Nonnull String gateKey) {
        return gateKey.startsWith("el_gate:") ? gateKey : "el_gate:" + gateKey;
    }

    private record ParsedGateIdentity(@Nonnull UUID worldUuid, int x, int y, int z) {
    }
}
