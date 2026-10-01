package fr.lkdm.homelink.tasks.recipe;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homecore.api.recipe.RecipeIngredient;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecipeExpansionTest {
    private final UUID owner = UUID.randomUUID();
    private final TaskBoard board = new TaskBoard(UUID.randomUUID(), "Workshop", owner, 10);
    private final RecipeDescriptor parentRecipe = new RecipeDescriptor(ResourceLocation.parse("test:sticks"),
            ResourceLocation.parse("minecraft:crafting_table"), List.of(new RecipeIngredient(Ingredient.of(Items.OAK_PLANKS), 2)), new ItemStack(Items.STICK, 4), 64, Optional.empty());
    private final RecipeDescriptor childRecipe = new RecipeDescriptor(ResourceLocation.parse("test:planks"),
            ResourceLocation.parse("minecraft:crafting_table"), List.of(new RecipeIngredient(Ingredient.of(Items.OAK_LOG), 1)), new ItemStack(Items.OAK_PLANKS, 4), 64, Optional.empty());
    private TaskCard parent() {
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Sticks", owner, 10);
        card.setObjective(new CraftObjective(new ItemStack(Items.STICK), 10, parentRecipe.recipeId(), ContributionPolicy.ASSIGNEES_ONLY, 10));
        card.assign(owner); board.addCard(card); return card;
    }
    @Test void remainingQuantityUsesRealYieldAndStartsAtZero() {
        TaskCard parent = parent(); parent.objective().orElseThrow().credit(owner, 4);
        TaskCard child = RecipeExpansion.create(board, parent, parentRecipe, 0, new ItemStack(Items.OAK_PLANKS), childRecipe, owner, 20);
        assertEquals(4, child.objective().orElseThrow().targetQuantity());
        assertEquals(0, child.objective().orElseThrow().completedQuantity());
        assertTrue(child.assignees().contains(owner)); assertEquals(parent.id(), child.parent().orElseThrow());
    }
    @Test void secondExpansionPreservesExistingWork() {
        var parent = parent();
        var child = RecipeExpansion.create(board, parent, parentRecipe, 0, new ItemStack(Items.OAK_PLANKS), childRecipe, owner, 20);
        child.objective().orElseThrow().credit(owner, 2);
        assertThrows(IllegalStateException.class, () -> RecipeExpansion.create(board, parent, parentRecipe, 0, new ItemStack(Items.OAK_PLANKS), childRecipe, owner, 21));
        assertEquals(2, child.objective().orElseThrow().completedQuantity()); assertEquals(2, board.cards().size());
    }
    @Test void cycleToAncestorOutputIsRejected() {
        var parent = parent();
        var cycle = new RecipeDescriptor(ResourceLocation.parse("test:cycle"), parentRecipe.station(),
                List.of(new RecipeIngredient(Ingredient.of(Items.STICK), 1)), new ItemStack(Items.OAK_PLANKS), 64, Optional.empty());
        var child = RecipeExpansion.create(board, parent, parentRecipe, 0, new ItemStack(Items.OAK_PLANKS), cycle, owner, 20);
        assertThrows(IllegalArgumentException.class, () -> RecipeExpansion.create(board, child, cycle, 0, new ItemStack(Items.STICK), parentRecipe, owner, 30));
    }
    @Test void expandedPlanCountsLogsInsteadOfParentPlanksPlusLogs() {
        var parent = parent();
        RecipeExpansion.create(board, parent, parentRecipe, 0, new ItemStack(Items.OAK_PLANKS), childRecipe, owner, 20);
        var plan = ProjectMaterialPlan.build(board, id -> Optional.of(id.equals(parentRecipe.recipeId()) ? parentRecipe : childRecipe));
        assertFalse(plan.incomplete()); assertEquals(1, plan.needs().size());
        assertTrue(plan.needs().getFirst().ingredient().test(new ItemStack(Items.OAK_LOG)));
        assertEquals(2, plan.needs().getFirst().quantity());
    }
    @Test void archivedDerivedCardRestoresParentMaterialBoundary() {
        var parent = parent();
        var child = RecipeExpansion.create(board, parent, parentRecipe, 0, new ItemStack(Items.OAK_PLANKS), childRecipe, owner, 20);
        child.setArchived(true);
        var plan = ProjectMaterialPlan.build(board, id -> Optional.of(parentRecipe));
        assertEquals(1, plan.needs().size()); assertEquals(6, plan.needs().getFirst().quantity());
        assertTrue(plan.needs().getFirst().ingredient().test(new ItemStack(Items.OAK_PLANKS)));
    }
    @Test void missingRecipeRetainsBoardAndMarksPlanIncomplete() {
        parent(); var plan = ProjectMaterialPlan.build(board, id -> Optional.empty());
        assertTrue(plan.incomplete()); assertTrue(plan.needs().isEmpty()); assertEquals(1, board.cards().size());
    }
    @Test void reducedServerLimitRejectsExpansionWithoutAddingACard() {
        var parent = parent();
        assertThrows(IllegalStateException.class, () -> RecipeExpansion.create(board, parent, parentRecipe, 0,
                new ItemStack(Items.OAK_PLANKS), childRecipe, owner, 20, 5));
        assertEquals(1, board.cards().size());
        assertEquals(0, parent.objective().orElseThrow().completedQuantity());
    }
}
