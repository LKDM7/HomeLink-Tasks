package fr.lkdm.homelink.tasks.task;

import fr.lkdm.homelink.tasks.board.TaskBoard;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The relations between cards of one board, and the rules that keep them sane.
 *
 * <p>Two different relations live here and are never mixed up. A parent relation says a
 * card is a subtask of another; a dependency says a card waits for another to finish.
 * Both are checked for cycles, because neither a subtask of itself nor a card waiting
 * for itself can ever be worked on.</p>
 *
 * <p>An unfinished dependency earns a card a "waiting" badge. It is not a fourth column,
 * and it never rejects a real batch: production is credited whatever the badges say.</p>
 *
 * <p>A manual parent is not finished because its children are — somebody still has to
 * say the work is done. A crafting parent advances only when its own item is produced,
 * never because a component card was completed.</p>
 */
public final class DependencyGraph {
    /** Largest number of cards one traversal visits. */
    public static final int MAX_VISITED = 512;

    private DependencyGraph() { }

    /**
     * Tests whether adding a link would close a cycle.
     *
     * @param board board both cards belong to
     * @param from card the link starts at
     * @param to card the link points to
     * @param parentRelation true for a parent link, false for a dependency
     * @return whether the link must be refused
     */
    public static boolean wouldCycle(TaskBoard board, UUID from, UUID to, boolean parentRelation) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) return true;
        // Walking from the target back up: reaching the source means the link would close a loop.
        Set<UUID> visited = new HashSet<>();
        ArrayDeque<UUID> pending = new ArrayDeque<>();
        pending.add(to);
        while (!pending.isEmpty() && visited.size() < MAX_VISITED) {
            UUID current = pending.remove();
            if (!visited.add(current)) continue;
            if (current.equals(from)) return true;
            TaskCard card = board.card(current).orElse(null);
            if (card == null) continue;
            if (parentRelation) card.parent().ifPresent(pending::add);
            else pending.addAll(card.dependencies());
        }
        // A traversal that ran out of budget is treated as unsafe rather than allowed.
        return visited.size() >= MAX_VISITED;
    }

    /**
     * Whether a card is waiting for a dependency that is not finished.
     *
     * <p>Used only to show a badge. It never prevents a real batch from being credited.</p>
     *
     * @param board board the card belongs to
     * @param card card to inspect
     * @return whether at least one dependency is unfinished
     */
    public static boolean waiting(TaskBoard board, TaskCard card) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(card, "card");
        for (UUID dependency : card.dependencies()) {
            TaskCard other = board.card(dependency).orElse(null);
            if (other != null && !other.archived() && other.status() != TaskStatus.DONE) return true;
        }
        return false;
    }

    /**
     * Counts the board's overall progress without counting a card twice.
     *
     * <p>A subtask is a card in its own right and is counted once, as itself. Its parent
     * is counted once too, on its own merit, so a summary never inflates a project by
     * adding a parent and its children as if they were separate work.</p>
     *
     * @param board board to summarise
     * @return finished and total unarchived card counts
     */
    public static Progress progress(TaskBoard board) {
        Objects.requireNonNull(board, "board");
        int total = 0;
        int done = 0;
        Set<UUID> counted = new HashSet<>();
        for (TaskCard card : board.cards()) {
            if (card.archived() || !counted.add(card.id())) continue;
            total++;
            if (card.status() == TaskStatus.DONE) done++;
        }
        return new Progress(done, total);
    }

    /**
     * A board's overall progress.
     *
     * @param done finished cards
     * @param total cards that are not archived
     */
    public record Progress(int done, int total) {
        /** Validates the counts. */
        public Progress {
            if (done < 0 || total < 0 || done > total) throw new IllegalArgumentException("Invalid progress");
        }

        /** Returns the share of finished cards.
         * @return ratio between zero and one, zero for an empty board
         */
        public float ratio() { return total == 0 ? 0F : (float) done / total; }
    }
}
