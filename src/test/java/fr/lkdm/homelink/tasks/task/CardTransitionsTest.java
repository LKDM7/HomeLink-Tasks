package fr.lkdm.homelink.tasks.task;

import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CardTransitionsTest {
    private static final ResourceLocation RECIPE = ResourceLocation.parse("homecore:cable");

    private final UUID owner = UUID.randomUUID();
    private final UUID editor = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID viewer = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();
    private TaskBoard board;

    @BeforeEach void setUp() {
        board = new TaskBoard(UUID.randomUUID(), "Atelier", owner, 0L);
        board.setMember(editor, BoardRole.EDITOR);
        board.setMember(member, BoardRole.MEMBER);
        board.setMember(viewer, BoardRole.VIEWER);
    }

    private TaskCard manual() {
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL, "Build the workshop", owner, 0L);
        board.addCard(card);
        return card;
    }

    private TaskCard craft(int target, int credited) {
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "Cables", owner, 0L);
        var objective = new CraftObjective(new ItemStack(Items.COPPER_INGOT), target, RECIPE,
                ContributionPolicy.ALL_CONTRIBUTORS, 0L);
        if (credited > 0) objective.credit(owner, credited);
        card.setObjective(objective);
        board.addCard(card);
        return card;
    }

    @Test void aManualTaskMovesFreelyAndOnlyAPlayerEverFinishesIt() {
        TaskCard card = manual();
        assertEquals(CardTransitions.Outcome.APPLIED,
                CardTransitions.apply(card, TaskStatus.IN_PROGRESS, 10L, member));
        assertEquals(CardTransitions.Outcome.APPLIED, CardTransitions.apply(card, TaskStatus.DONE, 20L, member));
        assertEquals(TaskStatus.DONE, card.status());
        assertEquals(CardTransitions.Outcome.UNCHANGED, CardTransitions.apply(card, TaskStatus.DONE, 21L, member));
    }

    @Test void anUnstartedCraftCardMovesBetweenTheFirstTwoColumns() {
        TaskCard card = craft(64, 0);
        assertEquals(CardTransitions.Outcome.APPLIED,
                CardTransitions.apply(card, TaskStatus.IN_PROGRESS, 10L, owner));
        assertEquals(CardTransitions.Outcome.APPLIED, CardTransitions.apply(card, TaskStatus.TODO, 11L, owner));
    }

    @Test void draggingCannotForgeAnIncompleteCraftIntoDone() {
        TaskCard card = craft(64, 16);
        var outcome = CardTransitions.apply(card, TaskStatus.DONE, 10L, owner);
        assertEquals(CardTransitions.Outcome.CRAFT_INCOMPLETE, outcome);
        assertTrue(outcome.refused());
        assertNotEquals(TaskStatus.DONE, card.status());
        assertEquals(16, card.objective().orElseThrow().completedQuantity(), "a refused move never touches the counter");
    }

    @Test void aStartedCraftCardDoesNotGoBackToToDo() {
        TaskCard card = craft(64, 16);
        CardTransitions.apply(card, TaskStatus.IN_PROGRESS, 10L, owner);
        var outcome = CardTransitions.apply(card, TaskStatus.TODO, 11L, owner);
        assertEquals(CardTransitions.Outcome.CRAFT_ALREADY_STARTED, outcome);
        assertEquals(TaskStatus.IN_PROGRESS, card.status());
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
    }

    @Test void aMoveNeverResetsTheCounterEvenWhenAccepted() {
        TaskCard card = craft(64, 16);
        CardTransitions.apply(card, TaskStatus.IN_PROGRESS, 10L, owner);
        assertEquals(16, card.objective().orElseThrow().completedQuantity());
    }

    @Test void aCompletedObjectiveMayBeDroppedIntoDone() {
        TaskCard card = craft(16, 16);
        assertEquals(CardTransitions.Outcome.APPLIED, CardTransitions.apply(card, TaskStatus.DONE, 10L, owner));
    }

    @Test void theAutomaticCompletionHappensOnlyOnce() {
        TaskCard card = craft(16, 16);
        assertTrue(CardTransitions.applyProduction(card, 10L));
        assertEquals(TaskStatus.DONE, card.status());
        assertFalse(CardTransitions.applyProduction(card, 11L), "a replayed credit re-finishes nothing");
    }

    @Test void archivingIsAPropertyAndNotAFourthColumn() {
        TaskCard card = manual();
        CardTransitions.apply(card, TaskStatus.IN_PROGRESS, 10L, owner);
        card.setArchived(true);
        assertTrue(card.archived());
        assertEquals(TaskStatus.IN_PROGRESS, card.status());
        assertTrue(board.column(TaskStatus.IN_PROGRESS).isEmpty(), "an archived card leaves the board view");
        assertEquals(0, board.activeCardCount());
    }

    @Test void aViewerMovesNothingAndAnOutsiderSeesNothing() {
        TaskCard card = manual();
        assertFalse(BoardPermissions.canMoveCard(board, card, viewer));
        assertFalse(BoardPermissions.canEditCards(board, viewer));
        assertTrue(BoardPermissions.canView(board, viewer));
        assertFalse(BoardPermissions.canView(board, outsider));
        assertFalse(BoardPermissions.canMoveCard(board, card, outsider));
    }

    @Test void aMemberMovesTheirOwnCardsButNotSomebodyElsesWork() {
        TaskCard foreign = manual();
        assertFalse(BoardPermissions.canMoveCard(board, foreign, member));
        foreign.assign(member);
        assertTrue(BoardPermissions.canMoveCard(board, foreign, member));
        assertTrue(BoardPermissions.canMoveCard(board, foreign, editor));
    }

    @Test void iWillTakeCareOfItAppliesOnlyToAnUnassignedCard() {
        TaskCard card = manual();
        assertTrue(BoardPermissions.canClaim(board, card, member));
        assertFalse(BoardPermissions.canClaim(board, card, viewer));
        card.assign(editor);
        assertFalse(BoardPermissions.canClaim(board, card, member));
    }

    @Test void removingAMemberEndsTheirAccessImmediately() {
        TaskCard card = manual();
        assertTrue(BoardPermissions.canView(board, member));
        assertTrue(board.removeMember(member));
        assertFalse(BoardPermissions.canView(board, member));
        assertFalse(BoardPermissions.canPin(board, member));
        assertFalse(BoardPermissions.canContribute(board, card, member));
        assertThrows(IllegalArgumentException.class, () -> board.removeMember(owner));
    }

    @Test void deletingACardLeavesNothingPointingAtIt() {
        TaskCard parent = manual();
        TaskCard child = manual();
        child.setParent(parent.id());
        child.addDependency(parent.id());
        assertTrue(board.removeCard(parent.id()).isPresent());
        assertTrue(child.parent().isEmpty());
        assertFalse(child.dependencies().contains(parent.id()));
    }

    @Test void aCardNeitherParentsNorDependsOnItself() {
        TaskCard card = manual();
        assertThrows(IllegalArgumentException.class, () -> card.setParent(card.id()));
        assertThrows(IllegalArgumentException.class, () -> card.addDependency(card.id()));
    }

    @Test void everyAcceptedMutationBumpsTheRevision() {
        TaskCard card = manual();
        long start = card.revision();
        card.setTitle("Renamed");
        assertEquals(start + 1, card.revision());
        assertFalse(card.unassign(outsider), "a no-op changes nothing");
        assertEquals(start + 1, card.revision());
    }
}
