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
import com.hypixel.hytale.server.core.universe.Universe;
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
    private final Ref<EntityStore> targetEntityRef;
    private final Map<String, Object> lastUiState = new HashMap<>();
    private final AtomicBoolean built = new AtomicBoolean(false);

    public GateTrackerHud(@Nonnull PlayerRef playerRef, @Nullable Ref<EntityStore> targetEntityRef) {
        super(playerRef);
        this.targetPlayerRef = playerRef;
        this.targetEntityRef = targetEntityRef;
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
        // Write initial label values directly into the build packet so the HUD
        // shows real data immediately rather than sitting on .ui defaults.
        computeHudLabels(uuid, uiCommandBuilder);
    }

    public void refreshHud() {
        if (!built.get()) {
            return;
        }
        UUID uuid = targetPlayerRef.getUuid();
        if (uuid == null || !targetPlayerRef.isValid()) {
            unregister(uuid);
            return;
        }
        UICommandBuilder builder = new UICommandBuilder();
        // Force a full HUD payload refresh so dynamic fields like player coordinates
        // are always re-sent on refresh ticks.
        lastUiState.clear();
        computeHudLabels(uuid, builder);
        update(false, builder);
    }

    public static OpenStatus open(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (uuid == null || !playerRef.isValid()) {
            return OpenStatus.PLAYER_INVALID;
        }

        synchronized (getHudLock(uuid)) {
            GateTrackerHud newHud = new GateTrackerHud(playerRef, player.getReference());
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
        return hud.resolveLiveEntityRef();
    }

    public static boolean isHudInStore(@Nullable UUID uuid, @Nullable Store<EntityStore> store) {
        if (uuid == null || store == null) {
            return false;
        }
        GateTrackerHud hud = ACTIVE_HUDS.get(uuid);
        if (hud == null || !hud.built.get()) {
            return false;
        }
        Ref<EntityStore> ref = hud.resolveLiveEntityRef();
        return ref != null && ref.getStore() == store;
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
        hud.refreshHud();
    }

    public static void refreshPlayerCoordsNow(@Nullable UUID uuid) {
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
        hud.refreshPlayerCoordsOnly();
    }

    /**
     * Writes the current tracker state into {@code uiCommandBuilder} using
     * {@link #setTextIfChanged} for all label fields. Returns {@code true} if any
     * field changed (caller should call {@code update()} if desired). Safe to call
     * from {@link #build} without a subsequent {@code update()} since the values
     * go directly into the build packet.
     */
    private boolean computeHudLabels(@Nullable UUID playerUuid, @Nonnull UICommandBuilder uiCommandBuilder) {
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
            changed |= setTextIfChanged(uiCommandBuilder, "#TrackerPlayerCoords.Text", "You: " + resolvePlayerCoords());
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
                "You: " + resolvePlayerCoords());
        return changed;
    }

    private void refreshPlayerCoordsOnly() {
        if (!built.get()) {
            return;
        }
        UICommandBuilder builder = new UICommandBuilder();
        if (setTextIfChanged(builder, "#TrackerPlayerCoords.Text", "You: " + resolvePlayerCoords())) {
            update(false, builder);
        }
    }

    @Nonnull
    private String resolvePlayerCoords() {
        Ref<EntityStore> ref = resolveLiveEntityRef();
        if (ref == null || !ref.isValid()) {
            return "(<untracked>)";
        }
        Store<EntityStore> store = ref.getStore();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return "(<untracked>)";
        }
        return String.format(
                "(%4d, %3d, %4d)",
                (int) Math.floor(transform.getPosition().getX()),
                (int) Math.floor(transform.getPosition().getY()),
                (int) Math.floor(transform.getPosition().getZ()));
    }

    @Nullable
    private Ref<EntityStore> resolveLiveEntityRef() {
        PlayerRef refreshed = resolveLivePlayerRef();
        if (refreshed != null) {
            Ref<EntityStore> refreshedRef = refreshed.getReference();
            if (refreshedRef != null && refreshedRef.isValid()) {
                return refreshedRef;
            }
        }

        if (targetEntityRef != null && targetEntityRef.isValid()) {
            return targetEntityRef;
        }
        Ref<EntityStore> fallback = targetPlayerRef.getReference();
        return fallback != null && fallback.isValid() ? fallback : null;
    }

    @Nullable
    private PlayerRef resolveLivePlayerRef() {
        UUID playerUuid = targetPlayerRef.getUuid();
        if (playerUuid == null) {
            return null;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return null;
        }
        PlayerRef refreshed = universe.getPlayer(playerUuid);
        if (refreshed == null) {
            return null;
        }
        Ref<EntityStore> refreshedRef = refreshed.getReference();
        return refreshedRef != null && refreshedRef.isValid() ? refreshed : null;
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