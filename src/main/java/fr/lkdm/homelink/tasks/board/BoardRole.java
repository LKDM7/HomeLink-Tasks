package fr.lkdm.homelink.tasks.board;

/**
 * What a member may do on one board.
 *
 * <p>A board role never grants a HomeCore network permission. Being invited to a board
 * does not make a player able to read a storage network, and a board attached to a
 * network still checks both rights separately.</p>
 */
public enum BoardRole {
    /** Full control, including members, ownership transfer and deletion. */
    OWNER,
    /** Creates, edits, assigns, orders and archives cards. */
    EDITOR,
    /** Contributes to allowed crafts, claims a free task and moves their own cards. */
    MEMBER,
    /** Reads the board and pins cards to their own HUD, changing nothing. */
    VIEWER;

    /** Whether this role is at least as powerful as another.
     * @param other role to compare against
     * @return whether this role includes the other's abilities
     */
    public boolean atLeast(BoardRole other) { return ordinal() <= other.ordinal(); }
}
