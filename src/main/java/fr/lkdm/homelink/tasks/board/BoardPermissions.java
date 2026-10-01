package fr.lkdm.homelink.tasks.board;

import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a board role allows, decided on the server for an authenticated player.
 *
 * <p>These checks cover the board only. A board membership is never a substitute for a
 * HomeCore network permission: reading stock through an attached board also requires
 * the player's own right on that network, checked separately.</p>
 */
public final class BoardPermissions {
    private BoardPermissions() { }

    /** Whether the player may see the board at all.
     * @param board board being read
     * @param player authenticated player
     * @return whether the player is a member
     */
    public static boolean canView(TaskBoard board, UUID player) {
        return role(board, player).isPresent();
    }

    /** Whether the player may create, edit, assign, order or archive cards.
     * @param board board being edited
     * @param player authenticated player
     * @return whether the player holds at least EDITOR
     */
    public static boolean canEditCards(TaskBoard board, UUID player) {
        return role(board, player).filter(held -> held.atLeast(BoardRole.EDITOR)).isPresent();
    }

    /** Whether the player may manage members, transfer ownership or delete the board.
     * @param board board being administered
     * @param player authenticated player
     * @return whether the player owns the board
     */
    public static boolean canManageBoard(TaskBoard board, UUID player) {
        return role(board, player).filter(held -> held == BoardRole.OWNER).isPresent();
    }

    /**
     * Whether the player may move this card between columns.
     *
     * <p>An editor moves anything. A plain member moves what belongs to them: a card they
     * are assigned to, or a manual task they created. A viewer moves nothing, however the
     * client drew the drag.</p>
     *
     * @param board owning board
     * @param card card being moved
     * @param player authenticated player
     * @return whether the move may be attempted
     */
    public static boolean canMoveCard(TaskBoard board, TaskCard card, UUID player) {
        Objects.requireNonNull(card, "card");
        Optional<BoardRole> held = role(board, player);
        if (held.isEmpty()) return false;
        if (held.get().atLeast(BoardRole.EDITOR)) return true;
        if (held.get() != BoardRole.MEMBER) return false;
        return card.assignees().contains(player) || card.type() == fr.lkdm.homelink.tasks.task.TaskType.MANUAL
                && card.creator().equals(player);
    }

    /**
     * Whether the player may claim an unassigned card for themselves.
     *
     * @param board owning board
     * @param card card being claimed
     * @param player authenticated player
     * @return whether "I will take care of it" applies
     */
    public static boolean canClaim(TaskBoard board, TaskCard card, UUID player) {
        Objects.requireNonNull(card, "card");
        return role(board, player).filter(held -> held.atLeast(BoardRole.MEMBER)).isPresent()
                && card.assignees().isEmpty() && !card.archived();
    }

    /**
     * Whether a finished batch by this player may be credited to this card.
     *
     * <p>With {@link ContributionPolicy#ASSIGNEES_ONLY} and nobody assigned, nobody
     * contributes. That is deliberate, and the card says so rather than quietly
     * accepting everyone's work.</p>
     *
     * @param board owning board
     * @param card crafting card
     * @param player player the batch is attributed to
     * @return whether the player is an eligible contributor
     */
    public static boolean canContribute(TaskBoard board, TaskCard card, UUID player) {
        Objects.requireNonNull(card, "card");
        CraftObjective objective = card.objective().orElse(null);
        if (objective == null || card.archived()) return false;
        Optional<BoardRole> held = role(board, player);
        if (held.isEmpty() || held.get() == BoardRole.VIEWER) return false;
        return objective.policy() == ContributionPolicy.ALL_CONTRIBUTORS || card.assignees().contains(player);
    }

    /** Whether the player may pin cards of this board to their own HUD.
     * @param board board being pinned from
     * @param player authenticated player
     * @return whether the player may pin, which every member may do
     */
    public static boolean canPin(TaskBoard board, UUID player) {
        return canView(board, player);
    }

    private static Optional<BoardRole> role(TaskBoard board, UUID player) {
        Objects.requireNonNull(board, "board");
        return board.roleOf(Objects.requireNonNull(player, "player"));
    }
}
