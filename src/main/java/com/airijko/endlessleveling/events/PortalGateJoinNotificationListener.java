package com.airijko.endlessleveling.events;

import com.airijko.endlessleveling.util.PlayerChatNotifier;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sends a personal chat notification to a player when they enter a portal gate dungeon instance.
 * Mirrors the pattern of DungeonTierJoinNotificationListener in the core.
 */
public class PortalGateJoinNotificationListener {

    private static final long NOTIFICATION_DELAY_MS = 5000L;

    public void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        if (event.getPlayerRef() == null) {
            return;
        }

        Ref<EntityStore> entityRef = event.getPlayerRef();
        Store<EntityStore> store = entityRef.getStore();
        Universe universe = Universe.get();
        if (store == null || universe == null) {
            return;
        }

        PlayerRef playerRef = null;
        for (PlayerRef candidate : universe.getPlayers()) {
            Ref<EntityStore> candidateRef = candidate.getReference();
            if (candidateRef != null && candidateRef.equals(entityRef)) {
                playerRef = candidate;
                break;
            }
        }
        if (playerRef == null) {
            return;
        }

        UUID worldUuid = playerRef.getWorldUuid();
        if (worldUuid == null) {
            return;
        }

        World playerWorld = universe.getWorld(worldUuid);
        if (playerWorld == null) {
            return;
        }

        String worldName = playerWorld.getName();
        String displayName = PortalLeveledInstanceRouter.resolveGateDisplayName(worldName);
        if (displayName == null) {
            return;
        }

        final PlayerRef finalPlayerRef = playerRef;
        final String finalWorldName = worldName;
        final String finalDisplayName = displayName;
        CompletableFuture.runAsync(() -> {
            if (!finalPlayerRef.isValid()) {
                return;
            }

            int[] range = PortalLeveledInstanceRouter.getActiveInstanceRange(finalWorldName);
            Message chat;
            if (range != null) {
                chat = Message.join(
                        Message.raw("Portal gate dungeon").color("#ffc300"),
                        Message.raw("\n" + finalDisplayName).color("#66d9ff"),
                        Message.raw("\nMob Lv ").color("#ffc300"),
                        Message.raw(range[0] + "-" + range[1]).color("#6cff78"));
            } else {
                chat = Message.join(
                        Message.raw("Portal gate dungeon").color("#ffc300"),
                        Message.raw("\n" + finalDisplayName).color("#66d9ff"));
            }
            PlayerChatNotifier.send(finalPlayerRef, chat);
        }, CompletableFuture.delayedExecutor(NOTIFICATION_DELAY_MS, TimeUnit.MILLISECONDS, playerWorld));
    }
}
