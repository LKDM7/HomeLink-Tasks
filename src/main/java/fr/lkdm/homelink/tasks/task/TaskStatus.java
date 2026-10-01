package fr.lkdm.homelink.tasks.task;

/**
 * The column a card sits in.
 *
 * <p>Archiving is a separate property, not a fourth column: an archived card keeps the
 * status it had.</p>
 */
public enum TaskStatus {
    /** Not started. */
    TODO,
    /** Being worked on. */
    IN_PROGRESS,
    /** Finished. */
    DONE
}
