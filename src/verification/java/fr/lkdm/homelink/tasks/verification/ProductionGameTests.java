package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.production.ProductionLog;
import fr.lkdm.homecore.api.production.ProductionReceipt;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.server.TaskManager;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The real path from a finished batch to a card, on a running server.
 *
 * <p>These tests publish on the same {@link ProductionLog} the Electronics Workbench
 * publishes on, so what is exercised here is the production channel itself, not a stand
 * in for it. A receipt is the only thing that ever moves a counter.</p>
 */
@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ProductionGameTests {
    private static final ResourceLocation WORKBENCH =
            ResourceLocation.fromNamespaceAndPath("homecore", "electronics_workbench");
    private static final ResourceLocation RECIPE =
            ResourceLocation.fromNamespaceAndPath("homecore", "homelink_circuit_board");

    private static TaskCard prepare(GameTestHelper helper, UUID producer, int wanted) {
        var server = helper.getLevel().getServer();
        TaskManager.start(server);
        TaskSavedData data = TaskSavedData.get(server);
        long tick = server.overworld().getGameTime();
        TaskBoard board = new TaskBoard(UUID.randomUUID(), "Atelier", producer, tick);
        board.setMember(UUID.randomUUID(), BoardRole.MEMBER);
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Câbles", producer, tick);
        card.setObjective(new CraftObjective(new ItemStack(Items.COPPER_INGOT), wanted, RECIPE,
                ContributionPolicy.ALL_CONTRIBUTORS, tick));
        board.addCard(card);
        data.addBoard(board);
        TaskManager.get(server).indexCard(card);
        return card;
    }

    private static ProductionReceipt receipt(GameTestHelper helper, UUID producer, int quantity) {
        var log = DashboardAPI.production(helper.getLevel().getServer());
        long tick = helper.getLevel().getGameTime();
        var started = log.startStamp();
        return new ProductionReceipt(UUID.randomUUID(), producer, WORKBENCH, Optional.of(RECIPE),
                new ItemStack(Items.COPPER_INGOT, quantity), tick, tick, log.nextSequence(), Optional.empty(), Optional.of(started));
    }

    /**
     * A batch published on the real production channel advances the card and finishes it.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aFinishedBatchAdvancesTheCard(GameTestHelper helper) {
        UUID producer = UUID.randomUUID();
        TaskCard card = prepare(helper, producer, 64);
        var log = DashboardAPI.production(helper.getLevel().getServer());

        log.publish(receipt(helper, producer, 16));
        helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 16,
                "a batch of sixteen credits sixteen finished items");
        helper.assertTrue(card.status() == TaskStatus.IN_PROGRESS,
                "the first admissible batch starts the card");

        log.publish(receipt(helper, producer, 48));
        helper.succeedIf(() -> {
            helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 64, "the objective is reached");
            helper.assertTrue(card.status() == TaskStatus.DONE, "the card finishes on its own");
        });
    }

    /**
     * The same receipt delivered twice credits once.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aReplayedReceiptCreditsOnce(GameTestHelper helper) {
        UUID producer = UUID.randomUUID();
        TaskCard card = prepare(helper, producer, 64);
        var log = DashboardAPI.production(helper.getLevel().getServer());
        ProductionReceipt batch = receipt(helper, producer, 16);
        log.publish(batch);
        log.publish(batch);
        helper.succeedIf(() -> helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 16,
                "a re-emitted receipt must not credit a second time"));
    }

    /**
     * Two identical cards share one batch instead of both gaining all of it.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void twoCardsShareOneBatch(GameTestHelper helper) {
        UUID producer = UUID.randomUUID();
        TaskCard first = prepare(helper, producer, 64);
        TaskCard second = prepare(helper, producer, 64);
        DashboardAPI.production(helper.getLevel().getServer()).publish(receipt(helper, producer, 16));
        helper.succeedIf(() -> {
            int total = first.objective().orElseThrow().completedQuantity()
                    + second.objective().orElseThrow().completedQuantity();
            helper.assertTrue(total == 16, "sixteen produced items add sixteen in total, not sixteen to each");
        });
    }

    /**
     * Holding, moving or receiving an item is not production and credits nothing.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void possessionCreditsNothing(GameTestHelper helper) {
        UUID producer = UUID.randomUUID();
        TaskCard card = prepare(helper, producer, 64);
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        // Giving the player a full stack of the wanted item must not move the counter at all.
        player.getInventory().add(new ItemStack(Items.COPPER_INGOT, 64));
        helper.succeedIf(() -> {
            helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 0,
                    "possessing an item is never production");
            helper.assertTrue(card.status() == TaskStatus.TODO, "and it never moves the card");
        });
    }

    /**
     * A batch started before the card existed is not credited retroactively.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void anOlderBatchIsNotCreditedRetroactively(GameTestHelper helper) {
        UUID producer = UUID.randomUUID();
        TaskCard card = prepare(helper, producer, 64);
        var log = DashboardAPI.production(helper.getLevel().getServer());
        long activation = card.objective().orElseThrow().activationTick();
        long older = Math.max(0L, activation - 100L);
        log.publish(new ProductionReceipt(UUID.randomUUID(), producer, WORKBENCH, Optional.of(RECIPE),
                new ItemStack(Items.COPPER_INGOT, 16), older, older + 20, log.nextSequence(), Optional.empty()));
        helper.succeedIf(() -> helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 0,
                "work started before the card must not count towards it"));
    }
}
