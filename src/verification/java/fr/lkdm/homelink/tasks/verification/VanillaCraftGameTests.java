package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.server.TaskManager;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Real crafting through the game's own menus, not a simulation of it.
 *
 * <p>These tests put ingredients in a real player's crafting grid and take the result the
 * way a player does, so the game fires its own craft event. What is measured is the
 * quantity Minecraft actually produced.</p>
 */
@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VanillaCraftGameTests {
    private static final ResourceLocation PLANKS =
            ResourceLocation.fromNamespaceAndPath("minecraft", "oak_planks");

    /**
     * Returns a real server player in the test level.
     *
     * <p>The helper marks this for removal, but a real { ServerPlayer} is exactly what
     * this check needs: the craft event only fires for one.</p>
     */
    @SuppressWarnings("removal")
    private static ServerPlayer realPlayer(GameTestHelper helper) {
        return helper.makeMockServerPlayerInLevel();
    }

    private static TaskCard prepare(GameTestHelper helper, ServerPlayer player, int wanted) {
        var server = helper.getLevel().getServer();
        TaskManager.start(server);
        long tick = server.overworld().getGameTime();
        TaskBoard board = new TaskBoard(UUID.randomUUID(), "Atelier bois", player.getUUID(), tick);
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Planches",
                player.getUUID(), tick);
        card.setObjective(new CraftObjective(new ItemStack(Items.OAK_PLANKS), wanted, PLANKS,
                ContributionPolicy.ALL_CONTRIBUTORS, tick));
        board.addCard(card);
        TaskSavedData.get(server).addBoard(board);
        TaskManager.get(server).indexCard(card);
        return card;
    }

    /** Puts a stack in the 2×2 grid of the player's own inventory and notifies the menu. */
    private static void putInGrid(ServerPlayer player, ItemStack stack) {
        player.containerMenu = player.inventoryMenu;
        player.inventoryMenu.getSlot(1).set(stack);
        player.inventoryMenu.getSlot(1).setChanged();
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * A single craft credits exactly what the recipe produced, not one item per callback.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aSingleCraftCreditsTheRecipeYield(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        putInGrid(player, new ItemStack(Items.OAK_LOG, 1));
        // Taking the result is the moment the planks really exist.
        player.inventoryMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.succeedIf(() -> helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 4,
                "one oak log yields four planks, so four is credited, not one"));
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fullInventoryCannotForgeShiftClickProduction(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        for (int slot = 0; slot < player.getInventory().items.size(); slot++)
            player.getInventory().items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
        putInGrid(player, new ItemStack(Items.OAK_LOG, 3));
        player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 0, "No space means no finished shift-click craft");
        helper.assertTrue(player.inventoryMenu.getSlot(1).getItem().getCount() == 3, "The ingredients were not consumed");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void productionThrownFromResultCountsOnce(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        putInGrid(player, new ItemStack(Items.OAK_LOG, 1));
        player.inventoryMenu.clicked(0, 1, ClickType.THROW, player);
        helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 4, "A result thrown from the crafting slot still produces four planks");
        helper.assertTrue(player.getInventory().countItem(Items.OAK_PLANKS) == 0, "The produced items are not in the inventory");
        helper.assertTrue(player.inventoryMenu.getSlot(1).getItem().isEmpty(), "The production consumed the log");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void almostFullInventoryCreditsOnlyTheCraftThatFits(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        for (int slot = 0; slot < player.getInventory().items.size(); slot++)
            player.getInventory().items.set(slot, new ItemStack(Items.COBBLESTONE, 64));
        player.getInventory().items.set(9, new ItemStack(Items.OAK_PLANKS, 60));
        putInGrid(player, new ItemStack(Items.OAK_LOG, 3));
        player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 4, "Only the four planks that fit were crafted");
        helper.assertTrue(player.getInventory().countItem(Items.OAK_PLANKS) == 64, "Sixty existing planks earn no credit");
        helper.assertTrue(player.inventoryMenu.getSlot(1).getItem().getCount() == 2, "Two uncrafted logs remain");
        helper.succeed();
    }

    /**
     * A shift-click crafts repeatedly, and the total credited matches what was produced.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aShiftClickCreditsEveryPlankOnce(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        putInGrid(player, new ItemStack(Items.OAK_LOG, 3));
        player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.succeedIf(() -> {
            int credited = card.objective().orElseThrow().completedQuantity();
            int carried = player.getInventory().countItem(Items.OAK_PLANKS);
            helper.assertTrue(credited == 12,
                    "three logs yield twelve planks, credited once each, but got " + credited);
            helper.assertTrue(carried == credited,
                    "the credited amount must match what the player really received");
            helper.assertTrue(card.status() == TaskStatus.IN_PROGRESS, "the card starts on real production");
        });
    }

    /**
     * Crafting something else never advances the objective.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void craftingAnotherItemCreditsNothing(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        putInGrid(player, new ItemStack(Items.SPRUCE_LOG, 2));
        player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.succeedIf(() -> helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 0,
                "spruce planks are not oak planks and must not advance this card"));
    }

    /**
     * A card locked to one recipe is still advanced by a real table craft of that recipe.
     *
     * <p>This checks that the receipt really carries the recipe the result slot recorded,
     * rather than assuming it does.</p>
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aLockedCardIsAdvancedByItsOwnRecipe(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        card.objective().orElseThrow().setLockedToRecipe(true);
        putInGrid(player, new ItemStack(Items.OAK_LOG, 1));
        player.inventoryMenu.clicked(0, 0, ClickType.QUICK_MOVE, player);
        helper.succeedIf(() -> {
            int credited = card.objective().orElseThrow().completedQuantity();
            helper.assertTrue(credited == 4,
                    "the receipt must name the recipe the result slot used, but credited " + credited);
        });
    }

    /**
     * A grid holding no valid recipe produces nothing, and a preview is not a craft.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aPreviewIsNotACraft(GameTestHelper helper) {
        ServerPlayer player = realPlayer(helper);
        TaskCard card = prepare(helper, player, 64);
        // Filling the grid shows a result but takes nothing: no items exist yet.
        putInGrid(player, new ItemStack(Items.OAK_LOG, 1));
        helper.succeedIf(() -> {
            helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 0,
                    "seeing a result in the grid is not producing it");
            helper.assertTrue(card.status() == TaskStatus.TODO, "and it must not move the card");
        });
    }
}
