package com.airijko.endlessleveling.listeners;

import com.airijko.endlessleveling.managers.MobWaveManager;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class WavePortalBreakBlockSystem extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    public WavePortalBreakBlockSystem() {
        super(BreakBlockEvent.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }

    @Override
    public void handle(
            int index,
            @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull BreakBlockEvent event
    ) {
        if (event.isCancelled()) {
            return;
        }

        String blockTypeId = event.getBlockType().getId();
        World world = commandBuffer.getExternalData().getWorld();
        if (world == null) {
            return;
        }

        MobWaveManager.handleNaturalWavePortalBlockBreak(world, event.getTargetBlock(), blockTypeId);
    }
}
