package fr.lkdm.homelink.tasks.stock;

import fr.lkdm.homecore.api.recipe.RecipeIngredient;
import fr.lkdm.homecore.api.stock.StockAccess;
import fr.lkdm.homecore.api.stock.StockAvailability;
import fr.lkdm.homecore.api.stock.StockEntry;
import fr.lkdm.homecore.api.stock.StockSourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The worked examples the planner has to reproduce exactly, plus the overlapping cases
 * a per-ingredient count gets wrong.
 */
class IngredientAvailabilityPlannerTest {
    @Test void projectCardsShareOneInventoryAndStockAllocation() {
        var result = IngredientAvailabilityPlanner.planDemand(List.of(Ingredient.of(Items.COPPER_INGOT), Ingredient.of(Items.COPPER_INGOT)),
                new int[] { 10, 10 }, inventory(Items.COPPER_INGOT, 6), storage(StockAvailability.COMPLETE, Map.of(Items.COPPER_INGOT, 4)));
        assertEquals(AvailabilityState.MISSING, result.state());
        assertEquals(6, result.ingredients().stream().mapToInt(IngredientAvailability::fromInventory).sum());
        assertEquals(4, result.ingredients().stream().mapToInt(IngredientAvailability::fromStock).sum());
        assertEquals(10, result.ingredients().stream().mapToInt(IngredientAvailability::missing).sum());
    }

    @Test void projectDemandCanExceedSingleRecipeSlotLimit() {
        var result = IngredientAvailabilityPlanner.planDemand(List.of(Ingredient.of(Items.COPPER_INGOT)), new int[] { 1000 },
                inventory(Items.COPPER_INGOT, 64), StockContext.inventoryOnly(100));
        assertEquals(1000, result.ingredients().getFirst().required());
        assertEquals(936, result.ingredients().getFirst().missing());
    }
    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");

    private static List<ItemStack> inventory(Item item, int count) {
        return count == 0 ? List.of() : List.of(new ItemStack(item, count));
    }

    private static StockContext storage(StockAvailability availability, Map<Item, Integer> contents) {
        List<StockEntry> entries = new ArrayList<>();
        int position = 0;
        for (var content : contents.entrySet()) {
            entries.add(new StockEntry(new ItemStack(content.getKey()),
                    Map.of(new StockSourceId(OVERWORLD, new BlockPos(position++, 64, 0)), (long) content.getValue())));
        }
        return new StockContext(entries, availability, StockAccess.READ_AND_WITHDRAW, true, 100L);
    }

    private static AvailabilityPlan plan(List<ItemStack> inventory, StockContext stock) {
        return IngredientAvailabilityPlanner.plan(
                List.of(new RecipeIngredient(Ingredient.of(Items.COPPER_INGOT), 10)), 1, inventory, stock);
    }

    @Test void tenInInventoryAndNothingInStorageIsReady() {
        var result = plan(inventory(Items.COPPER_INGOT, 10), storage(StockAvailability.COMPLETE, Map.of()));
        assertEquals(AvailabilityState.READY, result.state());
        var copper = result.ingredients().getFirst();
        assertEquals(10, copper.required());
        assertEquals(10, copper.fromInventory());
        assertEquals(0, copper.fromStock());
        assertEquals(0, copper.missing());
    }

    @Test void sixInInventoryAndFourVerifiedInStorageIsInStorage() {
        var result = plan(inventory(Items.COPPER_INGOT, 6),
                storage(StockAvailability.COMPLETE, Map.of(Items.COPPER_INGOT, 4)));
        assertEquals(AvailabilityState.IN_STORAGE, result.state());
        var copper = result.ingredients().getFirst();
        assertEquals(6, copper.fromInventory());
        assertEquals(4, copper.fromStock());
        assertEquals(0, copper.missing());
    }

    @Test void sixAndTwoOverACompleteScopeIsAProvenShortfall() {
        var result = plan(inventory(Items.COPPER_INGOT, 6),
                storage(StockAvailability.COMPLETE, Map.of(Items.COPPER_INGOT, 2)));
        assertEquals(AvailabilityState.MISSING, result.state());
        var copper = result.ingredients().getFirst();
        assertEquals(8, copper.allocated());
        assertEquals(2, copper.missing());
    }

    @Test void sixWithAConfiguredButUnreadableStorageIsUnverified() {
        var offline = new StockContext(List.of(), StockAvailability.UNAVAILABLE,
                StockAccess.READ_ONLY, true, 100L);
        var result = plan(inventory(Items.COPPER_INGOT, 6), offline);
        assertEquals(AvailabilityState.UNVERIFIED, result.state(), "unknown is not zero");
        assertEquals(6, result.ingredients().getFirst().fromInventory());
        assertEquals(4, result.ingredients().getFirst().missing());
    }

    @Test void tenInInventoryStaysReadyEvenWithStorageOffline() {
        var offline = new StockContext(List.of(), StockAvailability.UNAVAILABLE,
                StockAccess.READ_ONLY, true, 100L);
        assertEquals(AvailabilityState.READY, plan(inventory(Items.COPPER_INGOT, 10), offline).state());
    }

    @Test void withoutAnyStorageAShortfallIsRedAndNotAPermanentGrey() {
        var result = plan(inventory(Items.COPPER_INGOT, 6), StockContext.inventoryOnly(100L));
        assertEquals(AvailabilityState.MISSING, result.state());
        assertFalse(result.storageConfigured());
        assertEquals(4, result.ingredients().getFirst().missing());
    }

    @Test void oneOakPlankCannotSatisfyBothTheSpecificAndTheLooseRequirement() {
        var requirements = List.of(
                new RecipeIngredient(Ingredient.of(Items.OAK_PLANKS), 1),
                new RecipeIngredient(Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS), 1));
        var onlyOneOak = IngredientAvailabilityPlanner.plan(requirements, 1,
                inventory(Items.OAK_PLANKS, 1), StockContext.inventoryOnly(1L));
        assertEquals(AvailabilityState.MISSING, onlyOneOak.state());
        assertEquals(1, onlyOneOak.ingredients().stream().mapToInt(IngredientAvailability::missing).sum());
    }

    @Test void theSolverFindsTheAssignmentAGreedyChoiceWouldMiss() {
        // Spending the single oak plank on the loose requirement would fail a solvable plan.
        var requirements = List.of(
                new RecipeIngredient(Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS), 1),
                new RecipeIngredient(Ingredient.of(Items.OAK_PLANKS), 1));
        var result = IngredientAvailabilityPlanner.plan(requirements, 1,
                List.of(new ItemStack(Items.OAK_PLANKS, 1), new ItemStack(Items.SPRUCE_PLANKS, 1)),
                StockContext.inventoryOnly(1L));
        assertEquals(AvailabilityState.READY, result.state());
        assertTrue(result.ingredients().stream().allMatch(entry -> entry.missing() == 0));
    }

    @Test void aTagLikeRequirementAcceptsAnyMatchingWood() {
        var requirements = List.of(new RecipeIngredient(
                Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS), 4));
        var result = IngredientAvailabilityPlanner.plan(requirements, 1,
                inventory(Items.SPRUCE_PLANKS, 4), StockContext.inventoryOnly(1L));
        assertEquals(AvailabilityState.READY, result.state(), "spruce satisfies a requirement that accepts any plank");
    }

    @Test void stockKeepsTwoVariantsOfOneItemApart() {
        // A requirement follows its own vanilla predicate, but the stock the plan draws on
        // keeps components in its identity, so two variants are never merged into one pool.
        ItemStack named = new ItemStack(Items.IRON_PICKAXE);
        named.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("Drill"));
        var plain = new StockEntry(new ItemStack(Items.IRON_PICKAXE),
                Map.of(new StockSourceId(OVERWORLD, new BlockPos(0, 64, 0)), 1L));
        var custom = new StockEntry(named,
                Map.of(new StockSourceId(OVERWORLD, new BlockPos(1, 64, 0)), 1L));
        assertNotEquals(plain.variant().getComponentsPatch(), custom.variant().getComponentsPatch());

        var context = new StockContext(List.of(plain, custom), StockAvailability.COMPLETE,
                StockAccess.READ_AND_WITHDRAW, true, 1L);
        var result = IngredientAvailabilityPlanner.plan(
                List.of(new RecipeIngredient(Ingredient.of(Items.IRON_PICKAXE), 2)), 1, List.of(), context);
        assertEquals(2, result.ingredients().getFirst().fromStock(),
                "two distinct variants remain two separate units, never one counted twice");
    }

    @Test void oneStackIsNotSpentTwiceAcrossOperations() {
        var requirements = List.of(new RecipeIngredient(Ingredient.of(Items.COPPER_INGOT), 4));
        var result = IngredientAvailabilityPlanner.plan(requirements, 3,
                inventory(Items.COPPER_INGOT, 5), StockContext.inventoryOnly(1L));
        assertEquals(12, result.ingredients().getFirst().required());
        assertEquals(5, result.ingredients().getFirst().fromInventory());
        assertEquals(7, result.ingredients().getFirst().missing());
    }

    @Test void afinishedObjectiveAsksForNothing() {
        var requirements = List.of(new RecipeIngredient(Ingredient.of(Items.COPPER_INGOT), 4));
        var result = IngredientAvailabilityPlanner.plan(requirements, 0, List.of(), StockContext.inventoryOnly(1L));
        assertTrue(result.ingredients().isEmpty());
        assertEquals(AvailabilityState.READY, result.state());
    }

    @Test void aCalculationTooLargeIsReportedUnverifiedRatherThanGuessed() {
        var requirements = List.of(new RecipeIngredient(Ingredient.of(Items.COPPER_INGOT), 500));
        var result = IngredientAvailabilityPlanner.plan(requirements, 1_000_000,
                inventory(Items.COPPER_INGOT, 1), StockContext.inventoryOnly(1L));
        assertEquals(AvailabilityState.UNVERIFIED, result.state());
    }
}
