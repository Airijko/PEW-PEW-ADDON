package com.airijko.endlessleveling.systems;

import com.airijko.endlessleveling.ui.GateTrackerHud;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;

public final class GateTrackerHudRefreshSystem extends TickingSystem<EntityStore> {

    // Refresh every 5 game ticks (~250 ms at 20 TPS). Coordinates and status
    // are sampled on the correct per-player entity-store thread via isHudInStore.
    private static final int REFRESH_EVERY_TICKS = 5;
    private static final int MAX_REFRESHES_PER_PASS = 48;

    @Override
    public void tick(float deltaSeconds, int tickCount, @Nonnull Store<EntityStore> store) {
        if (store == null || store.isShutdown() || !GateTrackerHud.hasActiveHuds()) {
            return;
        }
        if (tickCount % REFRESH_EVERY_TICKS != 0) {
            return;
        }

        int refreshed = 0;
        for (UUID uuid : GateTrackerHud.getActiveHudUuids()) {
            // isHudInStore ensures we only touch HUDs whose player lives in this
            // store, so resolvePlayerCoords() accesses the component on the right thread.
            if (uuid == null || !GateTrackerHud.isHudInStore(uuid, store)) {
                continue;
            }
            GateTrackerHud.refreshHudNow(uuid);
            refreshed++;
            if (refreshed >= MAX_REFRESHES_PER_PASS) {
                break;
            }
        }

        // Fallback: if store matching filtered everything (store identity can vary in
        // some runtime paths), still push coordinate updates so the HUD remains live.
        if (refreshed == 0) {
            for (UUID uuid : GateTrackerHud.getActiveHudUuids()) {
                if (uuid == null) {
                    continue;
                }
                GateTrackerHud.refreshHudNow(uuid);
                refreshed++;
                if (refreshed >= MAX_REFRESHES_PER_PASS) {
                    break;
                }
            }
        }
    }
}