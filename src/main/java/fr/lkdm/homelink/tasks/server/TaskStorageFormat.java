package fr.lkdm.homelink.tasks.server;

import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.ActivityEntry;
import fr.lkdm.homelink.tasks.task.ActivityLog;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskPriority;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Reads and writes the mod's own persistent shape.
 *
 * <p>Boards live here rather than in a block entity, so breaking the screen that showed
 * one destroys nothing. Nothing volatile is persisted: no screen, no recipe manager, no
 * item handler and no client snapshot. Stock quantities and availability colours in
 * particular are observations, recomputed on demand, never stored as an inventory and
 * never evidence of a reservation.</p>
 *
 * <p>A card whose recipe or item disappeared is kept and stays identifiable, because
 * silently deleting a player's work is worse than showing it as needing attention.</p>
 */
public final class TaskStorageFormat {
    /** Current on-disk format version. */
    public static final int VERSION = 3;

    private TaskStorageFormat() { }

    /** Writes one board and its cards.
     * @param board board to persist
     * @param registries registry access of the running server
     * @return board tag
     */
    public static CompoundTag writeBoard(TaskBoard board, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", board.id());
        tag.putString("Title", board.title());
        tag.putString("Description", board.description());
        tag.putUUID("Owner", board.owner());
        tag.putLong("CreatedTick", board.createdTick());
        tag.putLong("Revision", board.revision());
        tag.putBoolean("Archived", board.archived());
        board.networkId().ifPresent(network -> tag.putUUID("Network", network));
        ListTag members = new ListTag();
        board.members().forEach((player, role) -> {
            if (role == BoardRole.OWNER) return;
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            entry.putString("Role", role.name());
            members.add(entry);
        });
        tag.put("Members", members);
        ListTag cards = new ListTag();
        for (TaskCard card : board.cards()) cards.add(writeCard(card, registries));
        for (TaskCard card : board.savedOverflowCards()) cards.add(writeCard(card, registries));
        tag.put("Cards", cards);
        return tag;
    }

    /** Reads one board and its cards, skipping entries that no longer make sense.
     * @param tag persisted board
     * @param registries registry access of the running server
     * @return board, or empty when the entry is unusable
     */
    public static Optional<TaskBoard> readBoard(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Owner")) return Optional.empty();
        TaskBoard board;
        try {
            board = new TaskBoard(tag.getUUID("Id"), tag.getString("Title"), tag.getUUID("Owner"),
                    Math.max(0L, tag.getLong("CreatedTick")));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        board.setDescription(tag.getString("Description"));
        board.setArchived(tag.getBoolean("Archived"));
        if (tag.hasUUID("Network")) board.setNetworkId(tag.getUUID("Network"));
        ListTag members = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int index = 0; index < members.size(); index++) {
            CompoundTag entry = members.getCompound(index);
            if (!entry.hasUUID("Player")) continue;
            role(entry.getString("Role")).ifPresent(role -> {
                try {
                    board.setMember(entry.getUUID("Player"), role);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // A membership that broke an invariant is dropped, never applied half way.
                }
            });
        }
        ListTag cards = tag.getList("Cards", Tag.TAG_COMPOUND);
        for (int index = 0; index < cards.size(); index++) {
            readCard(cards.getCompound(index), board.id(), registries).ifPresent(card -> {
                try {
                    board.addCard(card);
                } catch (IllegalStateException ignored) {
                    // Legacy archives were unbounded: preserve the excess without exceeding the wire bound.
                    board.preserveOverflowCard(card);
                }
            });
        }
        board.restoreRevision(tag.getLong("Revision"));
        return Optional.of(board);
    }

    private static CompoundTag writeCard(TaskCard card, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", card.id());
        tag.putString("Type", card.type().name());
        tag.putString("Title", card.title());
        tag.putString("Description", card.description());
        tag.putUUID("Creator", card.creator());
        tag.putLong("CreatedTick", card.createdTick());
        tag.putLong("Revision", card.revision());
        tag.putString("Priority", card.priority().name());
        tag.putString("Status", card.status().name());
        tag.putBoolean("Archived", card.archived());
        tag.putInt("Order", card.order());
        tag.putInt("NodeX", card.nodeX());
        tag.putInt("NodeY", card.nodeY());
        tag.putInt("DerivedIngredient", card.derivedIngredient());
        card.parent().ifPresent(parent -> tag.putUUID("Parent", parent));
        ListTag assignees = new ListTag();
        card.assignees().forEach(assignee -> assignees.add(net.minecraft.nbt.NbtUtils.createUUID(assignee)));
        tag.put("Assignees", assignees);
        ListTag dependencies = new ListTag();
        card.dependencies().forEach(dependency -> dependencies.add(net.minecraft.nbt.NbtUtils.createUUID(dependency)));
        tag.put("Dependencies", dependencies);
        card.objective().ifPresent(objective -> tag.put("Objective", writeObjective(objective, registries)));
        if (card.objective().isEmpty()) card.unresolvedObjective().ifPresent(objective -> tag.put("Objective", objective));
        tag.put("Log", writeLog(card.log()));
        return tag;
    }

    private static Optional<TaskCard> readCard(CompoundTag tag, UUID boardId, HolderLookup.Provider registries) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Creator")) return Optional.empty();
        TaskType type = enumOf(TaskType.class, tag.getString("Type")).orElse(null);
        if (type == null) return Optional.empty();
        TaskCard card;
        try {
            card = new TaskCard(tag.getUUID("Id"), boardId, type, tag.getString("Title"),
                    tag.getUUID("Creator"), Math.max(0L, tag.getLong("CreatedTick")));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        card.setDescription(tag.getString("Description"));
        enumOf(TaskPriority.class, tag.getString("Priority")).ifPresent(card::setPriority);
        card.setArchived(tag.getBoolean("Archived"));
        card.setOrder(tag.getInt("Order"));
        card.setNodePosition(tag.getInt("NodeX"), tag.getInt("NodeY"));
        if (tag.contains("DerivedIngredient")) card.setDerivedIngredient(Math.clamp(tag.getInt("DerivedIngredient"), -1, 8));
        if (tag.hasUUID("Parent")) card.setParent(tag.getUUID("Parent"));
        readUuids(tag.getList("Assignees", Tag.TAG_INT_ARRAY)).forEach(assignee -> {
            try {
                card.assign(assignee);
            } catch (IllegalStateException ignored) {
                // Beyond the assignee bound the rest are dropped rather than failing the card.
            }
        });
        readUuids(tag.getList("Dependencies", Tag.TAG_INT_ARRAY)).forEach(dependency -> {
            try {
                card.addDependency(dependency);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // A dependency that is no longer valid is simply not restored.
            }
        });
        if (type == TaskType.CRAFT && tag.contains("Objective", Tag.TAG_COMPOUND)) {
            var objective = readObjective(tag.getCompound("Objective"), registries);
            if (objective.isPresent()) card.setObjective(objective.orElseThrow());
            else card.preserveUnresolvedObjective(tag.getCompound("Objective"));
        }
        card.log().restore(readLog(tag.getList("Log", Tag.TAG_COMPOUND)));
        enumOf(TaskStatus.class, tag.getString("Status"))
                .ifPresent(status -> card.restoreState(status, tag.getLong("Revision")));
        return Optional.of(card);
    }

    private static CompoundTag writeObjective(CraftObjective objective, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.put("Target", objective.target().save(registries));
        tag.putInt("TargetQuantity", objective.targetQuantity());
        tag.putInt("Completed", objective.completedQuantity());
        tag.putString("Recipe", objective.recipeId().toString());
        tag.putBoolean("Locked", objective.lockedToRecipe());
        tag.putString("Policy", objective.policy().name());
        tag.putLong("ActivationTick", objective.activationTick());
        objective.activationStart().ifPresent(start -> {
            tag.putUUID("ActivationEpoch", start.epoch()); tag.putLong("ActivationOrder", start.ordinal());
        });
        ListTag contributions = new ListTag();
        objective.contributions().forEach((player, amount) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            entry.putInt("Amount", amount);
            contributions.add(entry);
        });
        tag.put("Contributions", contributions);
        return tag;
    }

    private static Optional<CraftObjective> readObjective(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack target = ItemStack.parseOptional(registries, tag.getCompound("Target"));
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("Recipe"));
        // A card whose item or recipe no longer resolves stays identifiable instead of disappearing.
        if (target.isEmpty() || recipe == null) return Optional.empty();
        ContributionPolicy policy = enumOf(ContributionPolicy.class, tag.getString("Policy"))
                .orElse(ContributionPolicy.ASSIGNEES_ONLY);
        CraftObjective objective;
        try {
            objective = new CraftObjective(target, tag.getInt("TargetQuantity"), recipe, policy,
                    Math.max(0L, tag.getLong("ActivationTick")));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        objective.setLockedToRecipe(tag.getBoolean("Locked"));
        if (tag.hasUUID("ActivationEpoch") && tag.getLong("ActivationOrder") >= 0)
            objective.setActivationStart(new fr.lkdm.homecore.api.production.ProductionStart(tag.getUUID("ActivationEpoch"), tag.getLong("ActivationOrder")));
        Map<UUID, Integer> contributions = new LinkedHashMap<>();
        ListTag entries = tag.getList("Contributions", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (entry.hasUUID("Player")) contributions.put(entry.getUUID("Player"), entry.getInt("Amount"));
        }
        objective.restore(tag.getInt("Completed"), contributions);
        return Optional.of(objective);
    }

    private static ListTag writeLog(ActivityLog log) {
        ListTag entries = new ListTag();
        for (ActivityEntry entry : log.entries()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Kind", entry.kind().name());
            entry.actor().ifPresent(actor -> tag.putUUID("Actor", actor));
            tag.putLong("Tick", entry.tick());
            tag.putInt("Amount", entry.amount());
            entries.add(tag);
        }
        return entries;
    }

    private static List<ActivityEntry> readLog(ListTag entries) {
        List<ActivityEntry> log = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag tag = entries.getCompound(index);
            enumOf(ActivityEntry.Kind.class, tag.getString("Kind")).ifPresent(kind -> log.add(new ActivityEntry(kind,
                    tag.hasUUID("Actor") ? Optional.of(tag.getUUID("Actor")) : Optional.empty(),
                    Math.max(0L, tag.getLong("Tick")), Math.max(0, tag.getInt("Amount")))));
        }
        return log;
    }

    private static List<UUID> readUuids(ListTag list) {
        List<UUID> values = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            values.add(net.minecraft.nbt.NbtUtils.loadUUID(list.get(index)));
        }
        return values;
    }

    private static Optional<BoardRole> role(String name) { return enumOf(BoardRole.class, name); }

    private static <T extends Enum<T>> Optional<T> enumOf(Class<T> type, String name) {
        for (T value : type.getEnumConstants()) if (value.name().equals(name)) return Optional.of(value);
        return Optional.empty();
    }
}
