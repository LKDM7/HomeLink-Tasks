package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homecore.registry.HomeCoreRecipes;
import fr.lkdm.homecore.registry.HomeCoreWorkbench;
import fr.lkdm.homecore.workbench.ElectronicsBlockEntity;
import fr.lkdm.homecore.workbench.ElectronicsMenu;
import fr.lkdm.homecore.workbench.recipe.ElectronicsRecipe;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.server.TaskManager;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Drives HomeCore's actual reservation, prototype and output path using datapack recipes. */
@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ElectronicsProductionGameTests {
    private record Fixture(ServerPlayer player, ElectronicsBlockEntity bench, RecipeHolder<ElectronicsRecipe> recipe, TaskCard card) { }

    @SuppressWarnings("removal")
    private static Fixture start(GameTestHelper helper) {
        var level = helper.getLevel();
        var recipe = level.getRecipeManager().getAllRecipesFor(HomeCoreRecipes.TYPE.get()).stream()
                .filter(holder -> 64 % holder.value().result().getCount() == 0)
                .filter(holder -> holder.value().materials().stream().mapToInt(material ->
                        (material.count() * (64 / holder.value().result().getCount()) + 63) / 64).sum() <= 9)
                .findFirst().orElseThrow();
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, HomeCoreWorkbench.BLOCK.get());
        var bench = (ElectronicsBlockEntity) helper.getBlockEntity(pos);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        var absolute = helper.absolutePos(pos);
        player.setPos(absolute.getX() + .5, absolute.getY() + .5, absolute.getZ() + .5);
        player.containerMenu = new ElectronicsMenu(1, player.getInventory(), bench);
        int slot = 0;
        for (var material : recipe.value().materials()) {
            int required = material.count() * (64 / recipe.value().result().getCount());
            while (required > 0) {
                int count = Math.min(64, required);
                bench.setItem(slot++, material.ingredient().getItems()[0].copyWithCount(count));
                required -= count;
            }
        }
        TaskManager.start(level.getServer());
        long tick = level.getServer().overworld().getGameTime();
        TaskBoard board = new TaskBoard(UUID.randomUUID(), "Workbench integration", player.getUUID(), tick);
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Batch", player.getUUID(), tick);
        card.setObjective(new CraftObjective(recipe.value().result(), 64, recipe.id(), ContributionPolicy.ALL_CONTRIBUTORS, tick));
        board.addCard(card);
        TaskSavedData.get(level.getServer()).addBoard(board);
        TaskManager.get(level.getServer()).indexCard(card);
        helper.assertTrue(bench.start(player, recipe, 64), "The real workbench must reserve the batch");
        return new Fixture(player, bench, recipe, card);
    }

    private static UUID transaction(Fixture fixture) {
        return fixture.bench().saveWithoutMetadata(fixture.player().registryAccess()).getCompound("Assembly").getUUID("Id");
    }
    private static void validate(GameTestHelper helper, Fixture fixture) {
        UUID id = transaction(fixture);
        for (int part = 0; part < fixture.recipe().value().assemblyLayout().size(); part++)
            helper.assertTrue(fixture.bench().place(fixture.player(), id, part, part), "Actual prototype placement rejected");
        helper.assertTrue(fixture.bench().phase() == 2, "Prototype must enter the production phase");
        fixture.player().containerMenu = fixture.player().inventoryMenu;
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void finishedBatchCredits64WhileMenuClosed(GameTestHelper helper) {
        Fixture fixture = start(helper); validate(helper, fixture);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(fixture.bench().getItem(9).getCount() == 64, "The actual output must contain 64");
            helper.assertTrue(fixture.card().objective().orElseThrow().completedQuantity() == 64, "Tasks must credit the output quantity");
            fixture.bench().removeItem(9, 16); fixture.bench().removeItem(9, 16); fixture.bench().removeItem(9, 32);
            helper.assertTrue(fixture.card().objective().orElseThrow().completedQuantity() == 64, "Output retrieval must never credit again");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void earlierBatchInTheSameTickCannotCreditANewCardAfterReload(GameTestHelper helper) {
        var fixture = start(helper);
        var manager = TaskManager.get(helper.getLevel().getServer());
        fixture.card().setArchived(true); manager.unindexCard(fixture.card());
        var board = manager.data().board(fixture.card().boardId()).orElseThrow();
        var card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Created after start",
                fixture.player().getUUID(), helper.getLevel().getGameTime());
        card.setObjective(new CraftObjective(fixture.recipe().value().result(), 64, fixture.recipe().id(),
                ContributionPolicy.ALL_CONTRIBUTORS, helper.getLevel().getGameTime()));
        board.addCard(card); manager.indexCard(card);
        validate(helper, fixture);
        var tag = fixture.bench().saveWithoutMetadata(helper.getLevel().registryAccess());
        var replacement = new ElectronicsBlockEntity(fixture.bench().getBlockPos(), fixture.bench().getBlockState());
        replacement.loadWithComponents(tag, helper.getLevel().registryAccess()); helper.getLevel().setBlockEntity(replacement);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(replacement.getItem(9).getCount() == 64, "The earlier real batch must still finalize after reload");
            helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 0, "Persisted start order refuses credit to the newer card");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void cancelledPrototypeCreditsNothing(GameTestHelper helper) {
        Fixture fixture = start(helper);
        fixture.bench().cancel(fixture.player(), transaction(fixture));
        fixture.player().containerMenu = fixture.player().inventoryMenu;
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(fixture.bench().getItem(9).isEmpty(), "Cancelled prototype must not produce");
            helper.assertTrue(fixture.card().objective().orElseThrow().completedQuantity() == 0, "Refund is not production");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void validatedBatchReloadPreservesAuthorAndTransaction(GameTestHelper helper) {
        Fixture fixture = start(helper); validate(helper, fixture);
        var tag = fixture.bench().saveWithoutMetadata(helper.getLevel().registryAccess());
        var pos = fixture.bench().getBlockPos();
        var replacement = new ElectronicsBlockEntity(pos, helper.getLevel().getBlockState(pos));
        replacement.loadWithComponents(tag, helper.getLevel().registryAccess());
        helper.getLevel().setBlockEntity(replacement);
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(replacement.getItem(9).getCount() == 64, "Reloaded reservation must finalize");
            var objective = fixture.card().objective().orElseThrow();
            helper.assertTrue(objective.completedQuantity() == 64, "Reloaded batch must contribute exactly once");
            helper.assertTrue(objective.contributions().get(fixture.player().getUUID()) == 64, "Original author must be retained");
            helper.succeed();
        });
    }
}
