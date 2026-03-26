package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class NaturalPortalGateManager {

    private static final List<String> PORTAL_BLOCK_IDS = List.of(
            "EL_MajorDungeonPortal_D01",
            "EL_MajorDungeonPortal_D02",
            "EL_MajorDungeonPortal_D03",
            "EL_EndgamePortal_Swamp_Dungeon",
            "EL_EndgamePortal_Frozen_Dungeon",
            "EL_EndgamePortal_Golem_Void"
    );

    private static final long SPAWN_INTERVAL_MINUTES = 30L;
    private static final long GATE_LIFETIME_MINUTES = 30L;
    private static final int MAX_OFFSET_BLOCKS = 96;
    private static final int PLACEMENT_ATTEMPTS = 16;

    private static JavaPlugin plugin;
    private static ScheduledFuture<?> periodicTask;

    private NaturalPortalGateManager() {
    }

    public static void initialize(@Nonnull JavaPlugin owner) {
        plugin = owner;
        periodicTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
                NaturalPortalGateManager::spawnNaturalGateTick,
                SPAWN_INTERVAL_MINUTES,
                SPAWN_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        plugin.getLogger().at(Level.INFO).log(
                "[ELPortal] Natural gate spawner enabled: every %d minute(s), lifetime %d minute(s)",
                SPAWN_INTERVAL_MINUTES,
                GATE_LIFETIME_MINUTES
        );
    }

    public static void shutdown() {
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
    }

    @Nonnull
    public static CompletableFuture<Boolean> spawnRandomGateNearPlayer(@Nonnull Player player, boolean isTestSpawn) {
        World world = player.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(false);
        }

        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return CompletableFuture.completedFuture(false);
        }

        // store.getComponent must run on the world thread; defer via world.execute()
        String blockId = pickRandomPortalBlock();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        world.execute(() -> {
            if (!ref.isValid()) {
                future.complete(false);
                return;
            }

            Store<EntityStore> store = ref.getStore();
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                future.complete(false);
                return;
            }

            spawnInWorldNearPlayerRefOnThread(world, playerRef, blockId, isTestSpawn, future);
        });
        return future;
    }

    private static void spawnNaturalGateTick() {
        try {
            if (plugin == null) {
                return;
            }

            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }

            List<PlayerRef> players = universe.getPlayers();
            if (players.isEmpty()) {
                return;
            }

            PlayerRef target = players.get((int) (Math.random() * players.size()));
            UUID worldUuid = target.getWorldUuid();
            if (worldUuid == null) {
                return;
            }

            World world = universe.getWorld(worldUuid);
            if (world == null) {
                return;
            }

            String blockId = pickRandomPortalBlock();
            spawnGateViaWorldDispatch(world, target, blockId, false);
        } catch (Exception ex) {
            if (plugin != null) {
                plugin.getLogger().at(Level.WARNING).withCause(ex).log("[ELPortal] Natural gate tick failed");
            }
        }
    }

    @Nonnull
    private static CompletableFuture<Boolean> spawnGateViaWorldDispatch(@Nonnull World world,
                                                                          @Nonnull PlayerRef playerRef,
                                                                          @Nonnull String blockId,
                                                                          boolean isTestSpawn) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        world.execute(() -> spawnInWorldNearPlayerRefOnThread(world, playerRef, blockId, isTestSpawn, future));
        return future;
    }

    private static void spawnInWorldNearPlayerRefOnThread(@Nonnull World world,
                                                          @Nonnull PlayerRef playerRef,
                                                          @Nonnull String blockId,
                                                          boolean isTestSpawn,
                                                          @Nonnull CompletableFuture<Boolean> future) {
            Vector3d base = playerRef.getTransform().getPosition();
            int baseX = MathUtil.floor(base.x);
            int baseY = MathUtil.floor(base.y);
            int baseZ = MathUtil.floor(base.z);

            for (int attempt = 0; attempt < PLACEMENT_ATTEMPTS; attempt++) {
                int offsetX = randomInt(-MAX_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
                int offsetZ = randomInt(-MAX_OFFSET_BLOCKS, MAX_OFFSET_BLOCKS);
                int x = baseX + offsetX;
                int y = Math.max(baseY, 1);
                int z = baseZ + offsetZ;

                WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    // chunk is not currently loaded by any player — skip
                    continue;
                }

                chunk.setBlock(x, y, z, blockId);
                announceGate(world, blockId, x, y, z, isTestSpawn);
                scheduleRemoval(world, blockId, x, y, z);
                future.complete(true);
                return;
            }

            if (plugin != null) {
                plugin.getLogger().at(Level.INFO).log(
                        "[ELPortal] No loaded chunk found near %s in world %s for gate placement",
                        playerRef.getUsername(),
                        world.getName()
                );
            }
            future.complete(false);
    }

    private static void scheduleRemoval(@Nonnull World world,
                                        @Nonnull String blockId,
                                        int x,
                                        int y,
                                        int z) {
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            world.execute(() -> {
                WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    if (plugin != null) {
                        plugin.getLogger().at(Level.INFO).log(
                                "[ELPortal] Gate expiry skipped (chunk unloaded) world=%s block=%s at %d %d %d",
                                world.getName(),
                                blockId,
                                x,
                                y,
                                z
                        );
                    }
                    return;
                }

                chunk.setBlock(x, y, z, "air");
                if (plugin != null) {
                    plugin.getLogger().at(Level.INFO).log(
                            "[ELPortal] Gate expired and removed world=%s block=%s at %d %d %d",
                            world.getName(),
                            blockId,
                            x,
                            y,
                            z
                    );
                }
            });
        }, GATE_LIFETIME_MINUTES, TimeUnit.MINUTES);
    }

    private static void announceGate(@Nonnull World world,
                                     @Nonnull String blockId,
                                     int x,
                                     int y,
                                     int z,
                                     boolean isTestSpawn) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        String prefix = isTestSpawn ? "[TEST] " : "";
        Message message = Message.raw(String.format(
                "%sA gate has opened in world '%s' at (%d, %d, %d)! [block=%s]",
                prefix,
                world.getName(),
                x,
                y,
                z,
                blockId
        )).color("#ffcc66");
        universe.sendMessage(message);
    }

    @Nonnull
    private static String pickRandomPortalBlock() {
        return PORTAL_BLOCK_IDS.get((int) (Math.random() * PORTAL_BLOCK_IDS.size()));
    }

    private static int randomInt(int minInclusive, int maxInclusive) {
        return minInclusive + (int) (Math.random() * (maxInclusive - minInclusive + 1));
    }
}