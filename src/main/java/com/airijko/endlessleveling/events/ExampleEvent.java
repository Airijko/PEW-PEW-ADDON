package com.airijko.endlessleveling.events;

import com.airijko.endlessleveling.managers.ExampleFeatureManager;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;

public class ExampleEvent {

    public static void onPlayerReady(PlayerReadyEvent event) {
        if (!ExampleFeatureManager.get().isExampleEventsEnabled()) {
            return;
        }

        Player player = event.getPlayer();
        player.sendMessage(Message.raw("Welcome " + player.getDisplayName()));
    }

}