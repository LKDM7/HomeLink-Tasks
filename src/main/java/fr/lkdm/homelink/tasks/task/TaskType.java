package fr.lkdm.homelink.tasks.task;

/**
 * The two kinds of card this version supports.
 *
 * <p>Nothing else completes a card: placing or breaking a block, holding an item,
 * taking one out of storage or picking one up never moves a card on its own.</p>
 */
public enum TaskType {
    /** A free task whose status only a player changes. */
    MANUAL,
    /** A crafting objective whose progress comes from batches the server saw finish. */
    CRAFT
}
