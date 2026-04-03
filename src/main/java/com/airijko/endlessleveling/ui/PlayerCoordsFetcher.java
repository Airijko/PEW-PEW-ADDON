package com.airijko.endlessleveling.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Modular utility for reading a player's live world position from TransformComponent.
 *
 * <p>Safe to call from any thread. If called from outside the player's entity store
 * thread, the component system returns the last cached position value.
 */
public final class PlayerCoordsFetcher {

    private PlayerCoordsFetcher() {
    }

    /**
     * Fetches the current world position of the player identified by {@code playerRef}.
     *
     * <p>Uses {@code store} if provided (preferred: must be the player's own entity store thread),
     * otherwise falls back to resolving the store from {@code playerRef.getReference().getStore()}.
     *
     * @return the live position, or {@code null} if the entity ref or transform is unavailable.
     */
    @Nullable
    public static Vector3d fetchPosition(@Nonnull PlayerRef playerRef, @Nullable Store<EntityStore> store) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        Store<EntityStore> effectiveStore = (store != null) ? store : ref.getStore();
        if (effectiveStore == null || effectiveStore.isShutdown()) {
            return null;
        }
        TransformComponent transform = effectiveStore.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            return null;
        }
        return transform.getPosition();
    }

    /**
     * Formats a world position as a compact block-coordinate string, e.g. {@code ( 188, 116,  868)}.
     * Returns {@code "(<untracked>)"} when {@code pos} is {@code null}.
     */
    @Nonnull
    public static String formatCoords(@Nullable Vector3d pos) {
        if (pos == null) {
            return "(<untracked>)";
        }
        return String.format(Locale.ROOT, "(%4d, %3d, %4d)",
                (int) Math.floor(pos.getX()),
                (int) Math.floor(pos.getY()),
                (int) Math.floor(pos.getZ()));
    }
}
