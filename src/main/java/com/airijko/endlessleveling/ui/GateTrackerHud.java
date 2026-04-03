package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.compatibility.GateTrackerMultipleHudCompatibility;
import com.airijko.endlessleveling.managers.GateTrackerManager;
import com.airijko.endlessleveling.managers.GateTrackerManager.GateTrackerEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GateTrackerHud extends CustomUIHud {

    public static final String MULTI_HUD_SLOT = "EndlessGateTrackerHud";
    private static final Map<UUID, GateTrackerHud> ACTIVE_HUDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Object> HUD_LOCKS = new ConcurrentHashMap<>();

    private final PlayerRef targetPlayerRef;
    private final Map<String, Object> lastUiState = new HashMap<>();
    private final AtomicBoolean built = new AtomicBoolean(false);

    public GateTrackerHud(@Nonnull PlayerRef playerRef) {
        super(playerRef);
        this.targetPlayerRef = playerRef;
    }

    @Override
    protected void build(@Nonnull UICommandBuilder uiCommandBuilder) {
        UUID uuid = targetPlayerRef.getUuid();
        if (uuid == null || ACTIVE_HUDS.get(uuid) != this) {
            return;
        }
        if (!built.compareAndSet(false, true)) {
            return;
        }
        uiCommandBuilder.append("Hud/EndlessGateTrackerHud.ui");
        computeHudLabels(uuid, null, uiCommandBuilder);
    }

    public void refreshHud(@Nonnull Store<EntityStore> store) {
        if (!built.get()) {
            return;
        }
        UUID uuid = targetPlayerRef.getUuid();
        if (uuid == null || !targetPlayerRef.isValid()) {
            unregister(uuid);
            return;
        }
        UICommandBuilder builder = new UICommandBuilder();
        boolean changed = computeDynamicHudLabels(uuid, store, builder);
        if (changed) {
            update(false, builder);
        }
    }

    public static OpenStatus open(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (uuid == null || !playerRef.isValid()) {
            return OpenStatus.PLAYER_INVALID;
        }

        synchronized (getHudLock(uuid)) {
            GateTrackerHud newHud = new GateTrackerHud(playerRef);
            ACTIVE_HUDS.put(uuid, newHud);

            if (GateTrackerMultipleHudCompatibility.showHud(player, playerRef, MULTI_HUD_SLOT, newHud)) {
                return OpenStatus.OPENED;
            }

            var hudManager = player.getHudManager();
            var existingHud = hudManager.getCustomHud();
            if (existingHud != null && !(existingHud instanceof GateTrackerHud) && !(existingHud instanceof GateTrackerHudHide)) {
                ACTIVE_HUDS.remove(uuid);
                return OpenStatus.BLOCKED_BY_EXISTING_HUD;
            }

            hudManager.setCustomHud(playerRef, null);
            hudManager.setCustomHud(playerRef, newHud);
            return OpenStatus.OPENED;
        }
    }

    public static void close(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (uuid == null) {
            return;
        }

        synchronized (getHudLock(uuid)) {
            ACTIVE_HUDS.remove(uuid);

            if (GateTrackerMultipleHudCompatibility.showHud(player, playerRef, MULTI_HUD_SLOT, new GateTrackerHudHide(playerRef))) {
                return;
            }

            var hudManager = player.getHudManager();
            var existingHud = hudManager.getCustomHud();
            if (existingHud instanceof GateTrackerHud || existingHud instanceof GateTrackerHudHide) {
                hudManager.setCustomHud(playerRef, null);
            }
        }
    }

    public static void unregister(@Nullable UUID uuid) {
        if (uuid == null) {
            return;
        }
        synchronized (getHudLock(uuid)) {
            GateTrackerHud removed = ACTIVE_HUDS.remove(uuid);
            if (removed != null) {
                removed.built.set(false);
            }
        }
    }

    public static int clearAllTrackedHuds() {
        int cleared = ACTIVE_HUDS.size();
        for (GateTrackerHud hud : ACTIVE_HUDS.values()) {
            if (hud != null) {
                hud.built.set(false);
            }
        }
        ACTIVE_HUDS.clear();
        HUD_LOCKS.clear();
        return cleared;
    }

    public static boolean hasActiveHuds() {
        return !ACTIVE_HUDS.isEmpty();
    }

    @Nonnull
    public static Set<UUID> getActiveHudUuids() {
        return Set.copyOf(ACTIVE_HUDS.keySet());
    }

    @Nullable
    public static Ref<EntityStore> getHudEntityRef(@Nullable UUID uuid) {
        if (uuid == null) {
            return null;
        }
        GateTrackerHud hud = ACTIVE_HUDS.get(uuid);
        if (hud == null || !hud.built.get()) {
            return null;
        }
        // Use targetPlayerRef.getReference() directly — the same proven pattern
        // as PlayerHud — to ensure store identity matches the ticking store.
        return hud.targetPlayerRef.getReference();
    }

    public static boolean isHudInStore(@Nullable UUID uuid, @Nullable Store<EntityStore> store) {
        if (uuid == null || store == null) {
            return false;
        }
        GateTrackerHud hud = ACTIVE_HUDS.get(uuid);
        if (hud == null || !hud.built.get()) {
            return false;
        }
        // Mirror PlayerHud.isHudInStore exactly: use targetPlayerRef.getReference()
        // so the Store instance comparison is reliable from the TickingSystem thread.
        Ref<EntityStore> ref = hud.targetPlayerRef.getReference();
        return ref != null && ref.getStore() == store;
    }

    public static void refreshHudNow(@Nullable UUID uuid, @Nonnull Store<EntityStore> store) {
        if (uuid == null) {
            return;
        }
        GateTrackerHud hud = ACTIVE_HUDS.get(uuid);
        if (hud == null || !hud.built.get()) {
            return;
        }
        if (!hud.targetPlayerRef.isValid()) {
            unregister(uuid);
            return;
        }
        hud.refreshHud(store);
    }

    public static void refreshHudNow(@Nullable UUID uuid) {
        if (uuid == null) {
            return;
        }
        GateTrackerHud hud = ACTIVE_HUDS.get(uuid);
        if (hud == null || !hud.built.get()) {
            return;
        }
        if (!hud.targetPlayerRef.isValid()) {
            unregister(uuid);
            return;
        }

        Ref<EntityStore> ref = hud.targetPlayerRef.getReference();
        if (ref == null || !ref.isValid()) {
            unregister(uuid);
            return;
        }

        Store<EntityStore> liveStore = ref.getStore();
        if (liveStore == null || liveStore.isShutdown()) {
            return;
        }
        hud.refreshHud(liveStore);
    }

    /**
     * Writes the current tracker state into {@code uiCommandBuilder}. Pass the
     * ticking {@code store} from a {@link TickingSystem} so that
     * {@link TransformComponent} is read on the correct store thread. Pass
     * {@code null} during {@link #build} (world/command thread) — the method will
     * fall back to {@code ref.getStore()} which is safe at that time.
     */
    private boolean computeHudLabels(@Nullable UUID playerUuid, @Nullable Store<EntityStore> store,
            @Nonnull UICommandBuilder uiCommandBuilder) {
        if (playerUuid == null) {
            return false;
        }

        GateTrackerEntry entry = GateTrackerManager.getTrackedEntry(playerUuid);
        if (entry == null) {
            GateTrackerManager.clearTrackedEntry(playerUuid);
            boolean changed = false;
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerHeading.Text", "Tracked Gate");
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerName.Text", "Tracked gate closed");
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerMeta.Text", "No active target");
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerGateCoords.Text", "Gate: --");
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerWorld.Text", "World: --");
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerPlayerCoords.Text", "You: " + resolvePlayerCoords(store));
            return changed;
        }

        boolean changed = false;
        changed |= setTextIfChanged(uiCommandBuilder, "#TrackerHeading.Text", "Tracked Gate");
        changed |= setTextIfChanged(uiCommandBuilder, "#TrackerName.Text", entry.title());
        changed |= setTextIfChanged(uiCommandBuilder,
                "#TrackerMeta.Text",
                entry.type().label() + " | Rank " + entry.rankLetter() + " | " + entry.status());
        changed |= setTextIfChanged(uiCommandBuilder,
                "#TrackerGateCoords.Text",
                "Gate: " + formatCoords(entry.x(), entry.y(), entry.z()));
        changed |= setTextIfChanged(uiCommandBuilder,
                "#TrackerWorld.Text",
                "World: " + entry.worldName());
        changed |= setTextIfChanged(uiCommandBuilder,
                "#TrackerPlayerCoords.Text",
                "You: " + resolvePlayerCoords(store));
        return changed;
    }

    /**
     * Writes only the fields that need live refresh after build: the gate meta /
     * status line and the player's current coordinates.
     */
    private boolean computeDynamicHudLabels(@Nullable UUID playerUuid, @Nullable Store<EntityStore> store,
            @Nonnull UICommandBuilder uiCommandBuilder) {
        if (playerUuid == null) {
            return false;
        }

        GateTrackerEntry entry = GateTrackerManager.getTrackedEntry(playerUuid);
        if (entry == null) {
            GateTrackerManager.clearTrackedEntry(playerUuid);
            boolean changed = false;
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerMeta.Text", "No active target");
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerPlayerCoords.Text", "You: " + resolvePlayerCoords(store));
            return changed;
        }

        boolean changed = false;
        changed |= setTextIfChanged(uiCommandBuilder,
                "#TrackerMeta.Text",
                entry.type().label() + " | Rank " + entry.rankLetter() + " | " + entry.status());
        changed |= setTextIfChanged(uiCommandBuilder,
                "#TrackerPlayerCoords.Text",
                "You: " + resolvePlayerCoords(store));
        return changed;
    }

    /**
     * Returns the player's current position as a formatted coordinate string,
     * delegating the actual component read to {@link PlayerCoordsFetcher}.
     */
    @Nonnull
    private String resolvePlayerCoords(@Nullable Store<EntityStore> store) {
        com.hypixel.hytale.math.vector.Vector3d pos = PlayerCoordsFetcher.fetchPosition(targetPlayerRef, store);
        return PlayerCoordsFetcher.formatCoords(pos);
    }

    @Nonnull
    private static String formatCoords(@Nullable Integer x, @Nullable Integer y, @Nullable Integer z) {
        if (x == null || y == null || z == null) {
            return "(<untracked>)";
        }
        return String.format("(%4d, %3d, %4d)", x, y, z);
    }

    private boolean setTextIfChanged(@Nonnull UICommandBuilder uiCommandBuilder, @Nonnull String selector, String value) {
        String normalized = value == null ? "" : value;
        Object previous = lastUiState.get(selector);
        if (Objects.equals(previous, normalized)) {
            return false;
        }
        lastUiState.put(selector, normalized);
        uiCommandBuilder.set(selector, normalized);
        return true;
    }

    private static Object getHudLock(@Nonnull UUID uuid) {
        return HUD_LOCKS.computeIfAbsent(uuid, ignored -> new Object());
    }

    public enum OpenStatus {
        OPENED,
        BLOCKED_BY_EXISTING_HUD,
        PLAYER_INVALID
    }
}