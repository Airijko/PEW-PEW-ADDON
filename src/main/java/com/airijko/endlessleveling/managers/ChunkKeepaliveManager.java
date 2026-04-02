package com.airijko.endlessleveling.managers;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Soft chunk keepalive for transient content (gates/portals).
 *
 * This does not rely on a hard chunk-ticket API; instead it periodically touches
 * tracked chunks via async chunk loads while the content is expected to exist.
 */
public final class ChunkKeepaliveManager {

    private static final long KEEPALIVE_INTERVAL_SECONDS = 2L;

    private static final Map<String, ChunkRef> TRACKED = new ConcurrentHashMap<>();
    private static volatile ScheduledFuture<?> keepaliveTask;

    private ChunkKeepaliveManager() {
    }

    public static void register(@Nonnull String key, @Nonnull UUID worldUuid, long chunkIndex) {
        TRACKED.put(key, new ChunkRef(worldUuid, chunkIndex));
        requestChunkLoad(worldUuid, chunkIndex);
        ensureTaskRunning();
    }

    public static void unregister(@Nonnull String key) {
        TRACKED.remove(key);
        stopTaskIfIdle();
    }

    public static void shutdown() {
        ScheduledFuture<?> task = keepaliveTask;
        keepaliveTask = null;
        if (task != null) {
            task.cancel(false);
        }
        TRACKED.clear();
    }

    private static void ensureTaskRunning() {
        if (keepaliveTask != null) {
            return;
        }

        synchronized (ChunkKeepaliveManager.class) {
            if (keepaliveTask != null) {
                return;
            }
            keepaliveTask = HytaleServer.SCHEDULED_EXECUTOR.scheduleWithFixedDelay(
                    ChunkKeepaliveManager::tick,
                    KEEPALIVE_INTERVAL_SECONDS,
                    KEEPALIVE_INTERVAL_SECONDS,
                    TimeUnit.SECONDS
            );
        }
    }

    private static void stopTaskIfIdle() {
        if (!TRACKED.isEmpty()) {
            return;
        }

        synchronized (ChunkKeepaliveManager.class) {
            if (!TRACKED.isEmpty()) {
                return;
            }
            ScheduledFuture<?> task = keepaliveTask;
            keepaliveTask = null;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static void tick() {
        if (TRACKED.isEmpty()) {
            stopTaskIfIdle();
            return;
        }

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        for (Map.Entry<String, ChunkRef> entry : new ArrayList<>(TRACKED.entrySet())) {
            ChunkRef ref = entry.getValue();
            if (ref == null) {
                continue;
            }

            requestChunkLoad(ref.worldUuid(), ref.chunkIndex());
        }
    }

    private static void requestChunkLoad(@Nonnull UUID worldUuid, long chunkIndex) {
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        World world = universe.getWorld(worldUuid);
        if (world == null) {
            return;
        }

        world.getNonTickingChunkAsync(chunkIndex);
    }

    private record ChunkRef(UUID worldUuid, long chunkIndex) {
    }
}
