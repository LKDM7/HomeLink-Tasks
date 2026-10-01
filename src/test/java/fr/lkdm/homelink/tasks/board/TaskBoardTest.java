package fr.lkdm.homelink.tasks.board;

import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskBoardTest {
    @Test void archivedCardsCannotBypassSnapshotAndPersistenceLimit() {
        UUID owner = UUID.randomUUID(); var board = new TaskBoard(UUID.randomUUID(), "Project", owner, 0);
        for (int index = 0; index < TaskBoard.MAX_CARDS; index++) {
            var card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL, "Archived", owner, index);
            card.setArchived(true); board.addCard(card);
        }
        assertThrows(IllegalStateException.class, () -> board.addCard(new TaskCard(UUID.randomUUID(), board.id(),
                TaskType.MANUAL, "Overflow", owner, 300)));
        assertEquals(TaskBoard.MAX_CARDS, board.cards().size());
    }
    @Test void movingAndReorderingLeavesOneContiguousMembership() {
        UUID owner = UUID.randomUUID(); var board = new TaskBoard(UUID.randomUUID(), "Project", owner, 0);
        var a = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL, "a", owner, 1);
        var b = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL, "b", owner, 2);
        var c = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL, "c", owner, 3);
        board.addCard(a); board.addCard(b); board.addCard(c);
        board.insertCard(c, TaskStatus.TODO, 0);
        assertEquals(c, board.column(TaskStatus.TODO).getFirst());
        b.setStatus(TaskStatus.IN_PROGRESS); board.insertCard(b, TaskStatus.TODO, 40);
        assertEquals(2, board.column(TaskStatus.TODO).size()); assertEquals(1, board.column(TaskStatus.IN_PROGRESS).size());
        assertEquals(0, c.order()); assertEquals(1, a.order()); assertEquals(0, b.order());
    }
    @Test void ownershipTransferKeepsExactlyOneOwner() {
        UUID old = UUID.randomUUID(), next = UUID.randomUUID(); var board = new TaskBoard(UUID.randomUUID(), "Project", old, 0);
        board.setMember(next, BoardRole.MEMBER); board.transferOwnership(next);
        assertEquals(next, board.owner()); assertEquals(BoardRole.EDITOR, board.roleOf(old).orElseThrow());
        assertEquals(1, board.members().values().stream().filter(role -> role == BoardRole.OWNER).count());
        assertThrows(IllegalArgumentException.class, () -> board.transferOwnership(UUID.randomUUID()));
    }
    @Test void creatorAloneCannotMoveUnassignedCraftAsMember() {
        UUID owner = UUID.randomUUID(), member = UUID.randomUUID(); var board = new TaskBoard(UUID.randomUUID(), "Project", owner, 0);
        board.setMember(member, BoardRole.MEMBER);
        var craft = new TaskCard(UUID.randomUUID(), board.id(), TaskType.CRAFT, "craft", member, 1);
        assertFalse(BoardPermissions.canMoveCard(board, craft, member));
        craft.assign(member); assertTrue(BoardPermissions.canMoveCard(board, craft, member));
    }
}
