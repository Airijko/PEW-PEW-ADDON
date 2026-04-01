package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.managers.GateTrackerManager;
import com.airijko.endlessleveling.ui.GateTrackerHud;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class GateTrackerHudReadyListener {

    private static final long HUD_OPEN_DELAY_MS = 300L;

    public void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        Player player = event.getPlayer();
        Ref<EntityStore> ref = event.getPlayerRef();
        World world = player == null ? null : player.getWorld();
        if (player == null || world == null || ref == null) {
            return;
        }

        UUID playerUuid = player.getUuid();
        if (!GateTrackerManager.hasTrackedEntry(playerUuid)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            Store<EntityStore> store = ref.getStore();
            if (store == null) {
                return;
            }

            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null || !playerRef.isValid()) {
                return;
            }

            if (GateTrackerManager.getTrackedEntry(playerUuid) == null) {
                return;
            }

            GateTrackerHud.open(player, playerRef);
        }, CompletableFuture.delayedExecutor(HUD_OPEN_DELAY_MS, TimeUnit.MILLISECONDS, world));
    }
}