package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.managers.GateInstancePersistenceManager;
import com.airijko.endlessleveling.managers.MobWaveManager;
import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class GateClearAllCommand extends AbstractCommand {

    public GateClearAllCommand() {
        super("clearall", "Clear dungeon/wave gates and stop all active wave sessions in your current world");
        this.addAliases("clear", "purgeall", "resetworldgates");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            context.sendMessage(Message.raw("This command is player-only.").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        World world = player.getWorld();
        if (world == null) {
            context.sendMessage(Message.raw("You are not in a world right now.").color("#ff6666"));
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        world.execute(() -> {
            try {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    context.sendMessage(Message.raw("Could not resolve player state.").color("#ff6666"));
                    future.complete(null);
                    return;
                }

                Store<EntityStore> store = ref.getStore();
                PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
                if (playerRef == null) {
                    context.sendMessage(Message.raw("Could not resolve player state.").color("#ff6666"));
                    future.complete(null);
                    return;
                }

                UUID worldUuid = world.getWorldConfig() == null ? null : world.getWorldConfig().getUuid();
                int removedDungeonGates = NaturalPortalGateManager.forceRemoveAllTrackedGatesInWorld(world);
                int removedPersistedDungeonGates = 0;
                if (worldUuid != null) {
                    for (GateInstancePersistenceManager.StoredGateInstance stored
                            : GateInstancePersistenceManager.listSavedInstancesById()) {
                        ParsedGateIdentity parsed = parseGateIdentity(stored.gateKey);
                        if (parsed == null || !worldUuid.equals(parsed.worldUuid())) {
                            continue;
                        }

                        String blockId = stored.blockId == null || stored.blockId.isBlank()
                                ? "<unknown>"
                                : stored.blockId;
                        NaturalPortalGateManager.forceRemoveGateAt(world, parsed.x(), parsed.y(), parsed.z(), blockId);
                        removedPersistedDungeonGates++;
                    }
                }
                int stoppedWaves = MobWaveManager.stopAllWavesInWorld(world);
                int removedWavePortals = MobWaveManager.clearWavePortalVisualsInPlayerWorld(playerRef);

                context.sendMessage(Message.raw("/gate clearall complete").color("#6cff78"));
                context.sendMessage(Message.raw("- Dungeon gates removed: " + removedDungeonGates).color("#8fd3ff"));
                context.sendMessage(Message.raw("- Persisted dungeon gate coordinates cleared: " + removedPersistedDungeonGates).color("#8fd3ff"));
                context.sendMessage(Message.raw("- Wave sessions/countdowns stopped: " + stoppedWaves).color("#8fd3ff"));
                context.sendMessage(Message.raw("- Wave portal anchors removed: " + removedWavePortals).color("#8fd3ff"));
            } finally {
                future.complete(null);
            }
        });

        return future;
    }

    @Nullable
    private static ParsedGateIdentity parseGateIdentity(@Nullable String gateKey) {
        if (gateKey == null || gateKey.isBlank()) {
            return null;
        }

        String normalized = gateKey.startsWith("el_gate:")
                ? gateKey.substring("el_gate:".length())
                : gateKey;
        String[] parts = normalized.split(":", 4);
        if (parts.length < 4) {
            return null;
        }

        try {
            UUID worldUuid = UUID.fromString(parts[0]);
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new ParsedGateIdentity(worldUuid, x, y, z);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record ParsedGateIdentity(@Nonnull UUID worldUuid, int x, int y, int z) {
    }
}