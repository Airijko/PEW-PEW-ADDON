package com.airijko.endlessleveling.commands;

import com.hypixel.hytale.builtin.portals.resources.PortalWorld;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class MajorDungeonsPortalCommand extends AbstractCommand {

    private static final String PORTAL_BLOCK_D01 = "EL_MajorDungeonPortal_D01";
    private static final String PORTAL_BLOCK_D02 = "EL_MajorDungeonPortal_D02";
    private static final String PORTAL_BLOCK_D03 = "EL_MajorDungeonPortal_D03";

    public MajorDungeonsPortalCommand() {
        super("mdportal", "Give Major Dungeons portal items (requires MajorDungeons)");
        this.addAliases("majorportal", "dungeonportal");

        this.addSubCommand(new SpawnSinglePortalSubCommand("d1", PORTAL_BLOCK_D01, "Spawn Major Dungeon I portal"));
        this.addSubCommand(new SpawnSinglePortalSubCommand("d2", PORTAL_BLOCK_D02, "Spawn Major Dungeon II portal"));
        this.addSubCommand(new SpawnSinglePortalSubCommand("d3", PORTAL_BLOCK_D03, "Spawn Major Dungeon III portal"));
        this.addSubCommand(new GiveAllPortalsSubCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        if (!(context.sender() instanceof Player)) {
            context.sendMessage(Message.raw("This command is player-only. Use /mdportal d1|d2|d3|all").color("#ff9900"));
            return CompletableFuture.completedFuture(null);
        }

        context.sendMessage(Message.raw("Usage: /mdportal d1|d2|d3|all").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<Void> spawnPortal(@Nonnull CommandContext context,
                                                       @Nonnull String blockId,
                                                       int xOffset) {
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
        try {
            world.execute(() -> {
                Ref<EntityStore> ref = player.getReference();
                if (ref == null || !ref.isValid()) {
                    context.sendMessage(Message.raw("Your player reference is not available right now.").color("#ff6666"));
                    future.complete(null);
                    return;
                }

                Store<EntityStore> store = ref.getStore();
                PortalWorld portalWorld = store.getResource(PortalWorld.getResourceType());
                if (!portalWorld.exists()) {
                    context.sendMessage(Message.raw("This command only works in fragment worlds.").color("#ff6666"));
                    future.complete(null);
                    return;
                }

                TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                if (transform == null || transform.getPosition() == null) {
                    context.sendMessage(Message.raw("Could not resolve your position right now.").color("#ff6666"));
                    future.complete(null);
                    return;
                }

                Vector3d position = transform.getPosition();
                int x = MathUtil.floor(position.x) + xOffset;
                int y = MathUtil.floor(position.y);
                int z = MathUtil.floor(position.z) + 2;

                WorldChunk chunk = world.getNonTickingChunk(ChunkUtil.indexChunkFromBlock(x, z));
                if (chunk == null) {
                    context.sendMessage(Message.raw("Could not load chunk for portal placement.").color("#ff6666"));
                    future.complete(null);
                    return;
                }

                chunk.setBlock(x, y, z, blockId);

                context.sendMessage(Message.raw("Spawned portal block: " + blockId + " at " + x + ", " + y + ", " + z)
                        .color("#6cff78"));
                future.complete(null);
            });
        } catch (Exception ex) {
            context.sendMessage(Message.raw("Failed to spawn portal right now.").color("#ff6666"));
            future.complete(null);
        }

        return future;
    }

    private static final class SpawnSinglePortalSubCommand extends AbstractCommand {
        private final String blockId;

        private SpawnSinglePortalSubCommand(@Nonnull String name, @Nonnull String blockId, @Nonnull String description) {
            super(name, description);
            this.blockId = blockId;
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return spawnPortal(context, this.blockId, 0);
        }
    }

    private static final class GiveAllPortalsSubCommand extends AbstractCommand {

        private GiveAllPortalsSubCommand() {
            super("all", "Give all Major Dungeons portal items");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            return spawnPortal(context, PORTAL_BLOCK_D01, -2)
                    .thenCompose(ignored -> spawnPortal(context, PORTAL_BLOCK_D02, 0))
                    .thenCompose(ignored -> spawnPortal(context, PORTAL_BLOCK_D03, 2));
        }
    }
}
