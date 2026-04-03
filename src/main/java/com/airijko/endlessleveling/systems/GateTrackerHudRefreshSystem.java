package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.ui.GateTrackerHud;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GateTrackerHudRefreshSystem extends TickingSystem<EntityStore> {

    // Minimum real-time gap between refreshes per player (250 ms).
    // NOTE: The TickingSystem second parameter is systemIndex (registry position), NOT
    // a tick counter, so modulo-based throttling is wrong and was removed.
    private static final long REFRESH_INTERVAL_NANOS = 250_000_000L;
    private static final long DIAG_LOG_EVERY_NANOS = 5_000_000_000L;

    // Per-player last-refresh timestamp, shared across all per-store tick invocations.
    private static final ConcurrentHashMap<UUID, Long> LAST_REFRESH_NANOS = new ConcurrentHashMap<>();

    private static volatile long lastDiagLogNanos = 0L;

    @Override
    public void tick(float deltaSeconds, int systemIndex, @Nonnull Store<EntityStore> store) {
        if (store == null || store.isShutdown()) {
            return;
        }

        long now = System.nanoTime();

        // Diagnostic log fires BEFORE the activeHuds guard so we can confirm
        // this tick system is alive even when no HUDs are open.
        if (now - lastDiagLogNanos >= DIAG_LOG_EVERY_NANOS) {
            lastDiagLogNanos = now;
            logDirect("[GateTrackDiag] tick alive store=%s activeHuds=%d",
                    Integer.toHexString(System.identityHashCode(store)),
                    GateTrackerHud.getActiveHudUuids().size());
        }

        if (!GateTrackerHud.hasActiveHuds()) {
            return;
        }

        for (UUID uuid : GateTrackerHud.getActiveHudUuids()) {
            if (uuid == null) {
                continue;
            }
            Long lastRefresh = LAST_REFRESH_NANOS.get(uuid);
            if (lastRefresh != null && now - lastRefresh < REFRESH_INTERVAL_NANOS) {
                continue;
            }
            LAST_REFRESH_NANOS.put(uuid, now);
            // Use the no-store fallback: resolves store via ref.getStore() internally.
            // This is proven to work (same path as the /gate track command).
            GateTrackerHud.refreshHudNow(uuid);
        }
    }

    private static void logDirect(@Nonnull String message, Object... args) {
        String formatted = (args == null || args.length == 0)
                ? message
                : String.format(Locale.ROOT, message, args);
        System.out.println(formatted);
    }
}