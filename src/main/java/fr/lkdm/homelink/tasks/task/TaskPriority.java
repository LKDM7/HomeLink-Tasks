package fr.lkdm.homelink.tasks.task;

/** Relative urgency shown on a card; it never affects crafting progress. */
public enum TaskPriority {
    /** Can wait. */
    LOW,
    /** Default. */
    NORMAL,
    /** Should be done soon. */
    HIGH,
    /** Blocking the project. */
    URGENT
}
