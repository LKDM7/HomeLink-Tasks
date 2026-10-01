package fr.lkdm.homelink.tasks.recipe;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** One-level expansion of a chosen alternative; never invents collection progress. */
public final class RecipeExpansion {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 128;
    private RecipeExpansion() { }

    public static TaskCard create(TaskBoard board, TaskCard parent, RecipeDescriptor parentRecipe, int ingredient,
                                  ItemStack variant, RecipeDescriptor childRecipe, UUID creator, long tick) {
        return create(board, parent, parentRecipe, ingredient, variant, childRecipe, creator, tick, CraftObjective.MAX_QUANTITY);
    }

    public static TaskCard create(TaskBoard board, TaskCard parent, RecipeDescriptor parentRecipe, int ingredient,
                                  ItemStack variant, RecipeDescriptor childRecipe, UUID creator, long tick, int quantityLimit) {
        var objective = parent.objective().orElseThrow();
        if (ingredient < 0 || ingredient >= parentRecipe.ingredients().size() || objective.complete()
                || !objective.accepts(parentRecipe.result())
                || !parentRecipe.ingredients().get(ingredient).ingredient().test(variant)
                || !ItemStack.isSameItemSameComponents(variant, childRecipe.result())) {
            throw new IllegalArgumentException("Incompatible recipe expansion");
        }
        int depth = 0;
        TaskCard ancestor = parent;
        var visited = new HashSet<UUID>();
        while (ancestor != null) {
            if (!visited.add(ancestor.id()) || ++depth > MAX_DEPTH) throw new IllegalStateException("Expansion depth exceeded");
            if (ancestor.objective().map(craft -> craft.accepts(variant)).orElse(false)) {
                throw new IllegalArgumentException("Recipe cycle");
            }
            ancestor = ancestor.parent().flatMap(board::card).orElse(null);
        }
        if (board.cards().stream().filter(card -> card.derivedIngredient() >= 0 && !card.archived()).count() >= MAX_NODES) {
            throw new IllegalStateException("Expansion node limit exceeded");
        }
        for (TaskCard existing : board.cards()) {
            if (existing.parent().filter(parent.id()::equals).isPresent() && existing.derivedIngredient() == ingredient) {
                throw new IllegalStateException("Ingredient already expanded; existing work is preserved");
            }
        }
        long needed = (long) parentRecipe.operationsFor(objective.remaining()) * parentRecipe.ingredients().get(ingredient).count();
        if (needed < 1 || needed > Math.min(quantityLimit, CraftObjective.MAX_QUANTITY)) throw new IllegalStateException("Component quantity exceeds limit");
        TaskCard child = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT,
                variant.getHoverName().getString(), creator, tick);
        child.setObjective(new CraftObjective(variant, (int) needed, childRecipe.recipeId(), objective.policy(), tick));
        child.setParent(parent.id());
        child.setDerivedIngredient(ingredient);
        child.setNodePosition(Math.clamp(parent.nodeX() + 140, -32767, 32767),
                Math.clamp(parent.nodeY() + ingredient * 64, -32767, 32767));
        child.setOrder(board.column(fr.lkdm.homelink.tasks.task.TaskStatus.TODO).size());
        parent.assignees().forEach(child::assign);
        board.addCard(child);
        return child;
    }
}
