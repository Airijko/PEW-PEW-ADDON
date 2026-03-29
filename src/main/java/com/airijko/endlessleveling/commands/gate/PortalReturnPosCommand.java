package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class PortalReturnPosCommand extends AbstractCommand {

    private static final List<String> RETURN_PORTAL_BLOCK_IDS = List.of(
            "Portal_Return",
            "Return_Portal"
    );

    private static final int WORLD_MIN_Y = 0;
    private static final int WORLD_MAX_Y = 319;
    private static final int MAX_LIST_LINES = 20;

    public PortalReturnPosCommand() {
        super("returnpos", "Show coordinates of return portal blocks in your current world");
        this.addAliases("portalreturnpos", "returnportalpos", "rportalpos");
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
                Vector3d playerPos = resolvePlayerPosition(player);
                List<Hit> hits = scanReturnPortals(world);
                if (hits.isEmpty()) {
                    context.sendMessage(Message.raw("No return portal blocks found in loaded chunks.").color("#ffcc66"));
                    future.complete(null);
                    return;
                }

                context.sendMessage(Message.raw("Return portals found: " + hits.size()).color("#6cff78"));

                if (playerPos != null) {
                    Hit nearest = hits.stream()
                            .min(Comparator.comparingDouble(hit -> distanceSquared(playerPos, hit)))
                            .orElse(null);
                    if (nearest != null) {
                        context.sendMessage(Message.raw(String.format(Locale.ROOT,
                                "Nearest: %s @ (%d, %d, %d) dist=%.2f",
                                nearest.blockId,
                                nearest.x,
                                nearest.y,
                                nearest.z,
                                Math.sqrt(distanceSquared(playerPos, nearest)))).color("#8fd3ff"));
                    }
                }

                int lines = Math.min(MAX_LIST_LINES, hits.size());
                for (int i = 0; i < lines; i++) {
                    Hit hit = hits.get(i);
                    String suffix = playerPos == null
                            ? ""
                            : String.format(Locale.ROOT, " dist=%.2f", Math.sqrt(distanceSquared(playerPos, hit)));
                    context.sendMessage(Message.raw(String.format(Locale.ROOT,
                            "[%d] %s @ (%d, %d, %d)%s",
                            i + 1,
                            hit.blockId,
                            hit.x,
                            hit.y,
                            hit.z,
                            suffix)).color("#dddddd"));
                }

                if (hits.size() > lines) {
                    context.sendMessage(Message.raw("... " + (hits.size() - lines) + " more").color("#999999"));
                }
            } catch (Exception ex) {
                context.sendMessage(Message.raw("Failed to scan return portals.").color("#ff6666"));
            }
            future.complete(null);
        });

        return future;
    }

    @Nullable
    private static Vector3d resolvePlayerPosition(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }

        Store<EntityStore> store = ref.getStore();
        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
        return transform == null ? null : transform.getPosition();
    }

    @Nullable
    private static PlayerRef resolvePlayerRef(@Nonnull Player player) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) {
            return null;
        }

        Store<EntityStore> store = ref.getStore();
        return store.getComponent(ref, PlayerRef.getComponentType());
    }

    @Nonnull
    private static List<Hit> scanReturnPortals(@Nonnull World world) {
        List<Hit> hits = new ArrayList<>();

        for (Long chunkIndexObj : world.getChunkStore().getChunkIndexes()) {
            if (chunkIndexObj == null) {
                continue;
            }

            long chunkIndex = chunkIndexObj;
            WorldChunk chunk = world.getChunkIfInMemory(chunkIndex);
            if (chunk == null) {
                continue;
            }

            int chunkX = ChunkUtil.xOfChunkIndex(chunkIndex);
            int chunkZ = ChunkUtil.zOfChunkIndex(chunkIndex);
            int minX = chunkX << 5;
            int minZ = chunkZ << 5;

            for (int y = WORLD_MIN_Y; y <= WORLD_MAX_Y; y++) {
                for (int x = minX; x < minX + 32; x++) {
                    for (int z = minZ; z < minZ + 32; z++) {
                        int blockIntId = chunk.getBlock(x, y, z);
                        if (blockIntId == 0) {
                            continue;
                        }

                        BlockType blockType = BlockType.getAssetMap().getAsset(blockIntId);
                        if (blockType == null || blockType.getId() == null) {
                            continue;
                        }

                        String blockId = blockType.getId();
                        if (isReturnPortalId(blockId)) {
                            hits.add(new Hit(blockId, x, y, z));
                        }
                    }
                }
            }
        }

        hits.sort(Comparator.comparingInt((Hit h) -> h.y)
                .thenComparingInt(h -> h.x)
                .thenComparingInt(h -> h.z)
                .thenComparing(h -> h.blockId));
        return hits;
    }

    private static boolean isReturnPortalId(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }

        for (String exact : RETURN_PORTAL_BLOCK_IDS) {
            if (exact.equals(blockId)) {
                return true;
            }
        }

        String normalized = blockId.toLowerCase(Locale.ROOT);
        return normalized.contains("portal_return") || normalized.contains("return_portal");
    }

    private static double distanceSquared(@Nonnull Vector3d pos, @Nonnull Hit hit) {
        double dx = pos.x - (hit.x + 0.5D);
        double dy = pos.y - (hit.y + 1.5D);
        double dz = pos.z - (hit.z + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static final class Hit {
        private final String blockId;
        private final int x;
        private final int y;
        private final int z;

        private Hit(@Nonnull String blockId, int x, int y, int z) {
            this.blockId = blockId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}
