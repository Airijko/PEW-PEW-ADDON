package com.airijko.endlessleveling.commands.gate;

import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.airijko.endlessleveling.managers.GateInstancePersistenceManager;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class GateInstancesCommand extends AbstractCommand {

    private static final int MAX_LIST_LINES = 40;

    public GateInstancesCommand() {
        super("instances", "List and delete persisted gate-instance entries by numeric ID");
        this.addAliases("instance", "gates", "gateinstances");
        this.addSubCommand(new ListSubCommand());
        this.addSubCommand(new DeleteByIdSubCommand());
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
        context.sendMessage(Message.raw("Usage: /gate dungeon instances <list|delete <id>>").color("#ffcc66"));
        return CompletableFuture.completedFuture(null);
    }

    private static final class ListSubCommand extends AbstractCommand {

        private ListSubCommand() {
            super("list", "List persisted gate instances with numeric IDs");
            this.addAliases("ls");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            List<GateInstancePersistenceManager.StoredGateInstance> instances =
                    GateInstancePersistenceManager.listSavedInstancesById();

            if (instances.isEmpty()) {
                context.sendMessage(Message.raw("No persisted gate instances found.").color("#ffcc66"));
                return CompletableFuture.completedFuture(null);
            }

            context.sendMessage(Message.raw("Persisted gate instances: " + instances.size()).color("#8fd3ff"));
            int displayCount = Math.min(MAX_LIST_LINES, instances.size());
            for (int index = 0; index < displayCount; index++) {
                GateInstancePersistenceManager.StoredGateInstance entry = instances.get(index);
                String line = String.format(
                        "#%d gate=%s instance=%s rank=%s range=%d-%d boss=%d deathLocks=%d",
                        entry.entryId,
                        entry.gateKey,
                        entry.instanceWorldName,
                        entry.rankLetter,
                        entry.minLevel,
                        entry.maxLevel,
                        entry.bossLevel,
                        entry.deathLockedPlayerUuids == null ? 0 : entry.deathLockedPlayerUuids.size());
                context.sendMessage(Message.raw(line).color("#d9f0ff"));
            }

            if (instances.size() > displayCount) {
                context.sendMessage(Message.raw(
                                "Showing first " + displayCount + " entries. Use /gate dungeon instances delete <id> to remove one.")
                        .color("#ffcc66"));
            }

            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class DeleteByIdSubCommand extends AbstractCommand {

        private final RequiredArg<Integer> idArg =
                this.withRequiredArg("id", "Numeric gate instance ID", Objects.requireNonNull(ArgTypes.INTEGER));

        private DeleteByIdSubCommand() {
            super("delete", "Delete a persisted gate instance entry by numeric ID");
            this.addAliases("remove", "del", "rm");
        }

        @Nullable
        @Override
        protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {
            Integer id = idArg.get(context);
            if (id == null || id <= 0) {
                context.sendMessage(Message.raw("Invalid id. Use a positive integer.").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            GateInstancePersistenceManager.StoredGateInstance entry =
                    GateInstancePersistenceManager.getSavedInstanceById(id);
            if (entry == null) {
                context.sendMessage(Message.raw("No persisted gate instance found for id " + id + ".").color("#ff6666"));
                return CompletableFuture.completedFuture(null);
            }

            String blockId = entry.blockId == null || entry.blockId.isBlank() ? "<unknown>" : entry.blockId;
            PortalLeveledInstanceRouter.cleanupGateInstanceByIdentity(entry.gateKey, blockId);
            context.sendMessage(Message.raw(
                    "Deleted gate instance id " + id + " (gate=" + entry.gateKey + ").").color("#6cff78"));
            return CompletableFuture.completedFuture(null);
        }
    }
}
