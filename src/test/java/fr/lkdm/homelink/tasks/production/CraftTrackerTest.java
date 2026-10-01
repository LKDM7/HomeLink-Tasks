package fr.lkdm.homelink.tasks.production;

import fr.lkdm.homecore.api.production.ProductionReceipt;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CraftTrackerTest {
    @Test void batchStartedEarlierInTheSameTickNeverCreditsANewObjective() {
        var card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 100);
        UUID epoch = UUID.randomUUID();
        card.objective().orElseThrow().setActivationStart(new fr.lkdm.homecore.api.production.ProductionStart(epoch, 8));
        var old = new ProductionReceipt(UUID.randomUUID(), fabio, WORKBENCH, Optional.of(CABLE_RECIPE),
                new ItemStack(Items.COPPER_INGOT, 16), 100, 120, 10, Optional.empty(),
                Optional.of(new fr.lkdm.homecore.api.production.ProductionStart(epoch, 7)));
        assertTrue(tracker.credit(old, context()).isEmpty());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
        var later = new ProductionReceipt(UUID.randomUUID(), fabio, WORKBENCH, Optional.of(CABLE_RECIPE),
                new ItemStack(Items.COPPER_INGOT, 16), 100, 120, 11, Optional.empty(),
                Optional.of(new fr.lkdm.homecore.api.production.ProductionStart(epoch, 9)));
        assertEquals(16, tracker.credit(later, context()).getFirst().quantity());
    }

    @Test void missingStartOrderCannotCreditAStampedObjectiveInTheSameTick() {
        var card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 100);
        card.objective().orElseThrow().setActivationStart(new fr.lkdm.homecore.api.production.ProductionStart(UUID.randomUUID(), 8));
        assertTrue(tracker.credit(batch(fabio, 16, 100), context()).isEmpty());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
    }
    private static final ResourceLocation WORKBENCH = ResourceLocation.parse("homecore:electronics_workbench");
    private static final ResourceLocation CABLE_RECIPE = ResourceLocation.parse("homecore:cable");
    private static final ResourceLocation OTHER_RECIPE = ResourceLocation.parse("homecore:cable_alternative");

    private final UUID fabio = UUID.randomUUID();
    private final UUID alex = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private TaskBoard board;
    private CraftTracker tracker;
    private final List<TaskCard> cards = new ArrayList<>();
    private UUID tracked;

    @BeforeEach void setUp() {
        board = new TaskBoard(UUID.randomUUID(), "Atelier", fabio, 0L);
        board.setMember(alex, BoardRole.MEMBER);
        tracker = new CraftTracker(new ReceiptDeduplicator());
        cards.clear();
        tracked = null;
    }

    private TaskCard craftCard(int quantity, ContributionPolicy policy, long activationTick, UUID... assignees) {
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Cables", fabio, activationTick);
        card.setObjective(new CraftObjective(new ItemStack(Items.COPPER_INGOT), quantity,
                CABLE_RECIPE, policy, activationTick));
        for (UUID assignee : assignees) card.assign(assignee);
        board.addCard(card);
        cards.add(card);
        return card;
    }

    private ProductionReceipt batch(UUID producer, int quantity, long startedTick) {
        return batch(producer, quantity, startedTick, CABLE_RECIPE, Items.COPPER_INGOT);
    }

    private ProductionReceipt batch(UUID producer, int quantity, long startedTick,
                                    ResourceLocation recipe, net.minecraft.world.item.Item item) {
        return new ProductionReceipt(UUID.randomUUID(), producer, WORKBENCH, Optional.of(recipe),
                new ItemStack(item, quantity), startedTick, startedTick + 40, 0L, Optional.empty());
    }

    private CraftTracker.Context context() {
        return new CraftTracker.Context() {
            @Override public List<TaskCard> candidates(ItemStack produced) { return List.copyOf(cards); }
            @Override public Optional<TaskBoard> board(UUID boardId) {
                return board.id().equals(boardId) ? Optional.of(board) : Optional.empty();
            }
            @Override public Optional<UUID> trackedCard(UUID player) { return Optional.ofNullable(tracked); }
        };
    }

    @Test void aSixtyFourItemTaskProgressesThroughTwoContributors() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        CraftObjective objective = card.objective().orElseThrow();
        assertEquals(TaskStatus.TODO, card.status());

        tracker.credit(batch(fabio, 16, 10), context());
        assertEquals(16, objective.completedQuantity());
        assertEquals(TaskStatus.IN_PROGRESS, card.status(), "the first admissible batch starts the card");

        tracker.credit(batch(alex, 32, 20), context());
        assertEquals(48, objective.completedQuantity());

        tracker.credit(batch(fabio, 16, 30), context());
        assertEquals(64, objective.completedQuantity());
        assertEquals(TaskStatus.DONE, card.status());
        assertEquals(32, objective.contributions().get(fabio));
        assertEquals(32, objective.contributions().get(alex));
    }

    @Test void aBatchOfSixtyFourCountsSixtyFourFinishedItemsNotOneOperation() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracker.credit(batch(fabio, 64, 5), context());
        assertEquals(64, card.objective().orElseThrow().completedQuantity());
        assertEquals(TaskStatus.DONE, card.status());
    }

    @Test void anUnassignedPlayerContributesNothingInAssigneesOnlyMode() {
        TaskCard card = craftCard(64, ContributionPolicy.ASSIGNEES_ONLY, 0L, fabio);
        tracker.credit(batch(alex, 16, 10), context());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
        tracker.credit(batch(fabio, 16, 11), context());
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
    }

    @Test void withNobodyAssignedNobodyContributes() {
        TaskCard card = craftCard(64, ContributionPolicy.ASSIGNEES_ONLY, 0L);
        tracker.credit(batch(fabio, 16, 10), context());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
    }

    @Test void aPlayerOutsideTheBoardNeverContributes() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracker.credit(batch(outsider, 16, 10), context());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
    }

    @Test void twoIdenticalTasksShareOneBatchInsteadOfBothGainingIt() {
        TaskCard first = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        TaskCard second = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracker.credit(batch(fabio, 16, 10), context());
        int total = first.objective().orElseThrow().completedQuantity()
                + second.objective().orElseThrow().completedQuantity();
        assertEquals(16, total, "sixteen cables add sixteen in total, never sixteen to each");
    }

    @Test void theProducersTrackedCardIsServedFirstAndTheSurplusMovesOn() {
        TaskCard first = craftCard(8, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        TaskCard second = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracked = second.id();
        tracker.credit(batch(fabio, 10, 10), context());
        assertEquals(10, second.objective().orElseThrow().completedQuantity());
        assertEquals(0, first.objective().orElseThrow().completedQuantity());

        tracked = first.id();
        tracker.credit(batch(fabio, 12, 20), context());
        assertEquals(8, first.objective().orElseThrow().completedQuantity(), "a card takes at most what it needs");
        assertEquals(14, second.objective().orElseThrow().completedQuantity(), "the surplus goes to the next card");
    }

    @Test void aReplayedReceiptNeitherCreditsAgainNorFinishesTwice() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        var receipt = batch(fabio, 16, 10);
        assertEquals(1, tracker.credit(receipt, context()).size());
        assertTrue(tracker.credit(receipt, context()).isEmpty());
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
    }

    @Test void aBatchStartedBeforeActivationIsNotCreditedRetroactively() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 500L);
        tracker.credit(batch(fabio, 16, 400), context());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
        tracker.credit(batch(fabio, 16, 500), context());
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
    }

    @Test void anotherItemNeverAdvancesTheObjective() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracker.credit(batch(fabio, 16, 10, CABLE_RECIPE, Items.IRON_INGOT), context());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
    }

    @Test void byDefaultAnyRecipeGivingTheSameResultContributes() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracker.credit(batch(fabio, 16, 10, OTHER_RECIPE, Items.COPPER_INGOT), context());
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
    }

    @Test void lockingToTheSelectedRecipeRejectsAnother() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        card.objective().orElseThrow().setLockedToRecipe(true);
        tracker.credit(batch(fabio, 16, 10, OTHER_RECIPE, Items.COPPER_INGOT), context());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
        tracker.credit(batch(fabio, 16, 11, CABLE_RECIPE, Items.COPPER_INGOT), context());
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
    }

    @Test void anArchivedCardIsNotAdvanced() {
        TaskCard card = craftCard(64, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        card.setArchived(true);
        tracker.credit(batch(fabio, 16, 10), context());
        assertEquals(0, card.objective().orElseThrow().completedQuantity());
    }

    @Test void aFinishedCardTakesNoMoreAndCompletesOnlyOnce() {
        TaskCard card = craftCard(16, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracker.credit(batch(fabio, 16, 10), context());
        assertEquals(TaskStatus.DONE, card.status());
        long revision = card.revision();
        tracker.credit(batch(fabio, 16, 20), context());
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
        assertEquals(revision, card.revision(), "a finished card is not touched again");
    }

    @Test void progressIsGroupedRatherThanLoggedPerItem() {
        TaskCard card = craftCard(256, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        // The first batch also starts the card, so its status change separates the entries.
        tracker.credit(batch(fabio, 16, 10), context());
        tracker.credit(batch(fabio, 16, 20), context());
        tracker.credit(batch(fabio, 16, 30), context());
        var progress = card.log().entries().stream()
                .filter(entry -> entry.kind() == fr.lkdm.homelink.tasks.task.ActivityEntry.Kind.PROGRESS).toList();
        assertEquals(2, progress.size(), "consecutive credits fold into one entry, never one line per item");
        assertEquals(16, progress.getFirst().amount());
        assertEquals(32, progress.getLast().amount());
        assertTrue(card.log().size() <= fr.lkdm.homelink.tasks.task.ActivityLog.MAX_ENTRIES);
    }

    @Test void aCreditByAnotherPlayerStartsItsOwnGroupedEntry() {
        TaskCard card = craftCard(256, ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        tracker.credit(batch(fabio, 16, 10), context());
        tracker.credit(batch(fabio, 16, 20), context());
        tracker.credit(batch(alex, 16, 30), context());
        var progress = card.log().entries().stream()
                .filter(entry -> entry.kind() == fr.lkdm.homelink.tasks.task.ActivityEntry.Kind.PROGRESS).toList();
        assertEquals(Optional.of(alex), progress.getLast().actor());
        assertEquals(16, progress.getLast().amount());
    }
}
