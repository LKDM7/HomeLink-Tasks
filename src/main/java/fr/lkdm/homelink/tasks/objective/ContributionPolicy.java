package fr.lkdm.homelink.tasks.objective;

/** Who may make a crafting objective progress. */
public enum ContributionPolicy {
    /**
     * Only players assigned to the card. With nobody assigned, nobody contributes, and
     * the card says so instead of silently accepting everyone's work.
     */
    ASSIGNEES_ONLY,
    /** Every board member allowed to contribute, chosen explicitly. */
    ALL_CONTRIBUTORS
}
