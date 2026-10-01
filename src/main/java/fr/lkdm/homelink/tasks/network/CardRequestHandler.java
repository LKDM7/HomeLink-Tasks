package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.recipe.RecipeResolver;
import fr.lkdm.homelink.tasks.server.TaskManager;
import fr.lkdm.homelink.tasks.task.ActivityEntry;
import fr.lkdm.homelink.tasks.task.CardTransitions;
import fr.lkdm.homelink.tasks.task.DependencyGraph;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Applies card-level requests, once the server has agreed to them.
 *
 * <p>Nothing here ever advances a crafting counter. A player moves cards, edits them and
 * chooses recipes; only a finished batch adds production.</p>
 */
final class CardRequestHandler {
    private CardRequestHandler() { }

    static void apply(ServerPlayer player, TaskPackets.CardRequest request) {
        TaskManager manager = TaskManager.get(player.server);
        TaskSubscriptions subscriptions = TaskSubscriptions.of(player.server);
        var accepted = manager.requests().claim(player.getUUID(), request.operationId(), player.server.overworld().getGameTime());
        if (accepted != fr.lkdm.homelink.tasks.server.RequestLedger.Result.ACCEPTED) {
            if (accepted == fr.lkdm.homelink.tasks.server.RequestLedger.Result.THROTTLED)
                subscriptions.notice(player, TaskPackets.Notice.TOO_FAST, request.card().orElse(null));
            return;
        }
        TaskBoard board = manager.data().board(request.board()).orElse(null);
        if (board == null || !BoardPermissions.canView(board, player.getUUID())) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
            return;
        }
        long tick = player.serverLevel().getGameTime();
        if (request.command() == TaskPackets.CardCommand.CREATE_MANUAL
                || request.command() == TaskPackets.CardCommand.CREATE_CRAFT) {
            create(player, manager, subscriptions, board, request, tick);
            return;
        }
        TaskCard card = request.card().flatMap(board::card).orElse(null);
        if (card == null) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
            return;
        }
        // The client echoes the revision it was editing, so a simultaneous edit conflicts openly.
        if (request.expectedRevision() != card.revision()) {
            subscriptions.notice(player, TaskPackets.Notice.CONFLICT, card.id());
            subscriptions.refresh(player);
            return;
        }
        try {
            if (mutate(player, manager, subscriptions, board, card, request, tick)) {
                board.touch();
                manager.data().setDirty();
                subscriptions.broadcast(board);
            }
        } catch (IllegalArgumentException | IllegalStateException malformed) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, card.id());
        }
    }

    private static void create(ServerPlayer player, TaskManager manager, TaskSubscriptions subscriptions,
                               TaskBoard board, TaskPackets.CardRequest request, long tick) {
        if (!BoardPermissions.canEditCards(board, player.getUUID())) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
            return;
        }
        boolean crafting = request.command() == TaskPackets.CardCommand.CREATE_CRAFT;
        String title = request.text().strip();
        if (title.isEmpty() || title.length() > TaskCard.MAX_TITLE) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
            return;
        }
        TaskCard card;
        try {
            card = new TaskCard(UUID.randomUUID(), board.id(),
                    crafting ? TaskType.CRAFT : TaskType.MANUAL, title, player.getUUID(), tick);
        } catch (IllegalArgumentException malformed) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
            return;
        }
        if (crafting && !attachObjective(player, subscriptions, card, request, tick)) return;
        int position = board.cards().size();
        card.setNodePosition(position % 4 * 140 - 210, position / 4 * 64 - 80);
        card.setOrder(board.column(TaskStatus.TODO).size());
        try {
            board.addCard(card);
        } catch (IllegalStateException limit) {
            subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, null);
            return;
        }
        manager.indexCard(card);
        if (crafting && card.assignees().isEmpty()
                && card.objective().map(CraftObjective::policy).orElse(null) == ContributionPolicy.ASSIGNEES_ONLY) {
            // Nobody assigned in assignees-only mode means nobody contributes; say so rather than hide it.
            subscriptions.notice(player, TaskPackets.Notice.NO_CONTRIBUTORS, card.id());
        }
        subscriptions.broadcast(board);
    }

    private static boolean attachObjective(ServerPlayer player, TaskSubscriptions subscriptions, TaskCard card,
                                           TaskPackets.CardRequest request, long tick) {
        ItemStack target = request.item();
        if (request.quantity() < 1 || request.quantity() > fr.lkdm.homelink.tasks.server.TaskServerConfig.MAX_QUANTITY.get()) {
            subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, card.id());
            return false;
        }
        ResourceLocation recipeId = request.recipeId().orElse(null);
        if (target.isEmpty() || recipeId == null) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, card.id());
            return false;
        }
        // Resolved against the running server, so a client cannot name a recipe that is not loaded.
        var descriptor = RecipeResolver.describe(player.server, recipeId).orElse(null);
        if (descriptor == null) {
            subscriptions.notice(player, TaskPackets.Notice.RECIPE_UNSUPPORTED, card.id());
            return false;
        }
        if (!ItemStack.isSameItemSameComponents(target, descriptor.result())) {
            subscriptions.notice(player, TaskPackets.Notice.RECIPE_UNSUPPORTED, card.id());
            return false;
        }
        try {
            CraftObjective objective = new CraftObjective(target, request.quantity(), recipeId,
                    request.flag() ? ContributionPolicy.ALL_CONTRIBUTORS : ContributionPolicy.ASSIGNEES_ONLY, tick);
            card.setObjective(objective);
            return true;
        } catch (IllegalArgumentException outOfBounds) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, card.id());
            return false;
        }
    }

    private static boolean mutate(ServerPlayer player, TaskManager manager, TaskSubscriptions subscriptions,
                                  TaskBoard board, TaskCard card, TaskPackets.CardRequest request, long tick) {
        boolean editor = BoardPermissions.canEditCards(board, player.getUUID());
        switch (request.command()) {
            case MOVE -> {
                if (!BoardPermissions.canMoveCard(board, card, player.getUUID())) return denied(player, subscriptions, card);
                TaskStatus previous = card.status();
                var outcome = CardTransitions.apply(card, request.status(), tick, player.getUUID());
                if (outcome == CardTransitions.Outcome.CRAFT_INCOMPLETE) {
                    subscriptions.notice(player, TaskPackets.Notice.CRAFT_INCOMPLETE, card.id());
                    subscriptions.refresh(player);
                    return false;
                }
                if (outcome == CardTransitions.Outcome.CRAFT_ALREADY_STARTED) {
                    subscriptions.notice(player, TaskPackets.Notice.CRAFT_STARTED, card.id());
                    subscriptions.refresh(player);
                    return false;
                }
                board.insertCard(card, previous, request.quantity());
                return true;
            }
            case REORDER -> {
                if (!BoardPermissions.canMoveCard(board, card, player.getUUID())) return denied(player, subscriptions, card);
                board.insertCard(card, card.status(), request.quantity());
            }
            case NODE -> {
                if (!BoardPermissions.canMoveCard(board, card, player.getUUID())) return denied(player, subscriptions, card);
                card.setNodePosition(Math.clamp(request.nodeX(), -Short.MAX_VALUE, Short.MAX_VALUE),
                        Math.clamp(request.nodeY(), -Short.MAX_VALUE, Short.MAX_VALUE));
            }
            case CLAIM -> {
                if (!BoardPermissions.canClaim(board, card, player.getUUID())) return denied(player, subscriptions, card);
                card.assign(player.getUUID());
                card.log().record(new ActivityEntry(ActivityEntry.Kind.ASSIGNED,
                        Optional.of(player.getUUID()), tick, 0));
                CardTransitions.apply(card, TaskStatus.IN_PROGRESS, tick, player.getUUID());
            }
            case RENAME -> {
                if (!editor) return denied(player, subscriptions, card);
                card.setTitle(request.text());
            }
            case DESCRIBE -> {
                if (!editor) return denied(player, subscriptions, card);
                card.setDescription(request.text());
            }
            case PRIORITY -> {
                if (!editor) return denied(player, subscriptions, card);
                card.setPriority(request.priority());
            }
            case ASSIGN, UNASSIGN -> {
                UUID member = request.target().orElse(null);
                if (!editor || member == null || board.roleOf(member).isEmpty()) {
                    return denied(player, subscriptions, card);
                }
                boolean changed = request.command() == TaskPackets.CardCommand.ASSIGN
                        ? card.assign(member) : card.unassign(member);
                if (changed) {
                    card.log().record(new ActivityEntry(ActivityEntry.Kind.ASSIGNED,
                            Optional.of(player.getUUID()), tick, 0));
                }
                return changed;
            }
            case ARCHIVE -> {
                if (!editor) return denied(player, subscriptions, card);
                card.setArchived(request.flag());
                if (request.flag()) manager.unindexCard(card); else manager.indexCard(card);
            }
            case DELETE -> {
                if (!editor) return denied(player, subscriptions, card);
                manager.unindexCard(card);
                board.removeCard(card.id());
                manager.data().purgeInaccessible(player.getUUID());
            }
            case SET_PARENT, ADD_DEPENDENCY, REMOVE_DEPENDENCY ->
                    { return graph(player, subscriptions, board, card, request); }
            case SELECT_RECIPE -> {
                if (!editor) return denied(player, subscriptions, card);
                if (hasExpandedIngredients(board, card)) return denied(player, subscriptions, card);
                CraftObjective objective = card.objective().orElse(null);
                ResourceLocation recipeId = request.recipeId().orElse(null);
                var descriptor = recipeId == null ? null : RecipeResolver.describe(player.server, recipeId).orElse(null);
                if (objective == null || recipeId == null
                        || descriptor == null || !objective.accepts(descriptor.result())) {
                    subscriptions.notice(player, TaskPackets.Notice.RECIPE_UNSUPPORTED, card.id());
                    return false;
                }
                objective.selectRecipe(recipeId);
                card.touch();
            }
            case LOCK_RECIPE -> {
                if (!editor || card.objective().isEmpty()) return denied(player, subscriptions, card);
                card.objective().orElseThrow().setLockedToRecipe(request.flag());
                card.touch();
            }
            case SET_POLICY -> {
                if (!editor || card.objective().isEmpty()) return denied(player, subscriptions, card);
                card.objective().orElseThrow().setPolicy(request.flag()
                        ? ContributionPolicy.ALL_CONTRIBUTORS : ContributionPolicy.ASSIGNEES_ONLY);
                card.touch();
            }
            case SET_QUANTITY -> {
                if (!editor || card.objective().isEmpty()) return denied(player, subscriptions, card);
                if (hasExpandedIngredients(board, card)) return denied(player, subscriptions, card);
                if (card.objective().orElseThrow().complete()) return denied(player, subscriptions, card);
                if (request.quantity() > fr.lkdm.homelink.tasks.server.TaskServerConfig.MAX_QUANTITY.get())
                    return denied(player, subscriptions, card);
                try {
                    // Raising or lowering the target never discards what was already produced.
                    card.objective().orElseThrow().setTargetQuantity(request.quantity());
                } catch (IllegalArgumentException outOfBounds) {
                    return denied(player, subscriptions, card);
                }
                CardTransitions.applyProduction(card, tick);
                card.touch();
            }
            case REPEAT -> { return repeat(player, manager, subscriptions, board, card, tick); }
            case EXPAND_INGREDIENT -> {
                if (!editor || card.objective().isEmpty()) return denied(player, subscriptions, card);
                var parentRecipe = RecipeResolver.describe(player.server, card.objective().orElseThrow().recipeId()).orElse(null);
                var childRecipe = request.recipeId().flatMap(id -> RecipeResolver.describe(player.server, id)).orElse(null);
                if (parentRecipe == null || childRecipe == null) return denied(player, subscriptions, card);
                try {
                    TaskCard child = fr.lkdm.homelink.tasks.recipe.RecipeExpansion.create(board, card, parentRecipe,
                            request.quantity(), request.item(), childRecipe, player.getUUID(), tick,
                            fr.lkdm.homelink.tasks.server.TaskServerConfig.MAX_QUANTITY.get());
                    manager.indexCard(child);
                    if (request.flag()) {
                        if (!manager.data().setPinned(player.getUUID(), child.id(), true,
                                fr.lkdm.homelink.tasks.server.TaskServerConfig.PIN_LIMIT.get()))
                            subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, child.id());
                        subscriptions.sendPinned(player);
                    }
                    card.touch();
                } catch (IllegalArgumentException | IllegalStateException invalid) {
                    subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, card.id());
                    return false;
                }
            }
            default -> { return denied(player, subscriptions, card); }
        }
        return true;
    }

    private static boolean hasExpandedIngredients(TaskBoard board, TaskCard parent) {
        return board.cards().stream().anyMatch(child -> child.derivedIngredient() >= 0
                && child.parent().filter(parent.id()::equals).isPresent());
    }

    /**
     * Copies a finished crafting card into a new, empty objective.
     *
     * <p>Restarting never rewinds the finished card's counter: the work already done
     * stays recorded, and the repetition starts at zero on its own card.</p>
     */
    private static boolean repeat(ServerPlayer player, TaskManager manager, TaskSubscriptions subscriptions,
                                  TaskBoard board, TaskCard card, long tick) {
        CraftObjective source = card.objective().orElse(null);
        if (!BoardPermissions.canEditCards(board, player.getUUID()) || source == null || !source.complete()
                || source.targetQuantity() > fr.lkdm.homelink.tasks.server.TaskServerConfig.MAX_QUANTITY.get()) {
            return denied(player, subscriptions, card);
        }
        TaskCard copy = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, card.title(),
                player.getUUID(), tick);
        CraftObjective objective = new CraftObjective(source.target(), source.targetQuantity(),
                source.recipeId(), source.policy(), tick);
        objective.setLockedToRecipe(source.lockedToRecipe());
        copy.setObjective(objective);
        copy.setDescription(card.description());
        copy.setPriority(card.priority());
        copy.setNodePosition(Math.clamp(card.nodeX() + 24, -32767, 32767), Math.clamp(card.nodeY() + 24, -32767, 32767));
        copy.setOrder(board.column(TaskStatus.TODO).size());
        card.assignees().forEach(copy::assign);
        try {
            board.addCard(copy);
        } catch (IllegalStateException limit) {
            subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, card.id());
            return false;
        }
        manager.indexCard(copy);
        return true;
    }

    private static boolean graph(ServerPlayer player, TaskSubscriptions subscriptions, TaskBoard board,
                                 TaskCard card, TaskPackets.CardRequest request) {
        if (!BoardPermissions.canEditCards(board, player.getUUID())) return denied(player, subscriptions, card);
        UUID other = request.target().orElse(null);
        if (request.command() == TaskPackets.CardCommand.REMOVE_DEPENDENCY) {
            return other != null && card.removeDependency(other);
        }
        // A link to a missing or foreign card, or one that would close a cycle, is refused.
        if (other != null && board.card(other).isEmpty()) return denied(player, subscriptions, card);
        if (request.command() == TaskPackets.CardCommand.SET_PARENT) {
            if (other != null && DependencyGraph.wouldCycle(board, card.id(), other, true)) {
                subscriptions.notice(player, TaskPackets.Notice.DEPENDENCY_CYCLE, card.id());
                return false;
            }
            card.setParent(other);
            return true;
        }
        if (other == null || DependencyGraph.wouldCycle(board, card.id(), other, false)) {
            subscriptions.notice(player, TaskPackets.Notice.DEPENDENCY_CYCLE, card.id());
            return false;
        }
        try {
            return card.addDependency(other);
        } catch (IllegalStateException limit) {
            subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, card.id());
            return false;
        }
    }

    private static boolean denied(ServerPlayer player, TaskSubscriptions subscriptions, TaskCard card) {
        subscriptions.notice(player, TaskPackets.Notice.DENIED, card.id());
        return false;
    }
}
