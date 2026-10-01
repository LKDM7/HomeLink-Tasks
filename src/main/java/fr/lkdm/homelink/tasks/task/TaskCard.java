package fr.lkdm.homelink.tasks.task;

import fr.lkdm.homelink.tasks.objective.CraftObjective;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * One card of a board, in either view.
 *
 * <p>The Kanban board and the mind map show the same cards, not two databases: a node
 * of the mind map is this object, so a title, an assignment or a credited batch appears
 * in both at once.</p>
 *
 * <p>Server-owned and mutated only on the server thread. Every mutation bumps
 * {@link #revision()}, which a client echoes back so two simultaneous edits produce an
 * explicit conflict instead of a lost update or a duplicated card.</p>
 */
public final class TaskCard {
    /** Largest title length accepted. */
    public static final int MAX_TITLE = 80;
    /** Largest description length accepted. */
    public static final int MAX_DESCRIPTION = 2000;
    /** Largest number of assignees one card accepts. */
    public static final int MAX_ASSIGNEES = 32;
    /** Largest number of dependencies one card declares. */
    public static final int MAX_DEPENDENCIES = 16;

    private final UUID id;
    private final UUID boardId;
    private final TaskType type;
    private final UUID creator;
    private final long createdTick;
    private final ActivityLog log = new ActivityLog();
    private final Set<UUID> assignees = new LinkedHashSet<>();
    private final Set<UUID> dependencies = new LinkedHashSet<>();

    private String title;
    private String description = "";
    private TaskPriority priority = TaskPriority.NORMAL;
    private TaskStatus status = TaskStatus.TODO;
    private boolean archived;
    private int order;
    private int nodeX;
    private int nodeY;
    private UUID parent;
    private CraftObjective objective;
    private long revision = 1;

    /**
     * Creates a card in the first column.
     *
     * @param id stable card identity
     * @param boardId owning board
     * @param type manual task or crafting objective
     * @param title label, 1 to {@value #MAX_TITLE} characters
     * @param creator player who created it
     * @param createdTick server tick of creation
     */
    public TaskCard(UUID id, UUID boardId, TaskType type, String title, UUID creator, long createdTick) {
        this.id = Objects.requireNonNull(id, "id");
        this.boardId = Objects.requireNonNull(boardId, "boardId");
        this.type = Objects.requireNonNull(type, "type");
        this.creator = Objects.requireNonNull(creator, "creator");
        if (createdTick < 0) throw new IllegalArgumentException("Creation tick must not be negative");
        this.createdTick = createdTick;
        this.title = validTitle(title);
        log.record(new ActivityEntry(ActivityEntry.Kind.CREATED, Optional.of(creator), createdTick, 0));
    }

    private static String validTitle(String value) {
        String trimmed = Objects.requireNonNull(value, "title").strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE) {
            throw new IllegalArgumentException("A title holds 1 to " + MAX_TITLE + " characters");
        }
        return trimmed;
    }

    /** Returns the stable card identity.
     * @return card UUID
     */
    public UUID id() { return id; }

    /** Returns the owning board.
     * @return board UUID
     */
    public UUID boardId() { return boardId; }

    /** Returns whether this is a manual task or a crafting objective.
     * @return card type
     */
    public TaskType type() { return type; }

    /** Returns the player who created the card.
     * @return creator identity
     */
    public UUID creator() { return creator; }

    /** Returns the server tick the card was created at.
     * @return creation tick
     */
    public long createdTick() { return createdTick; }

    /** Returns the current revision, bumped by every accepted mutation.
     * @return revision number
     */
    public long revision() { return revision; }

    /** Returns the card's bounded history.
     * @return activity log
     */
    public ActivityLog log() { return log; }

    /** Returns the card label.
     * @return title
     */
    public String title() { return title; }

    /** Returns the card description.
     * @return description, possibly empty
     */
    public String description() { return description; }

    /** Returns the column the card sits in.
     * @return current status
     */
    public TaskStatus status() { return status; }

    /** Returns the card's urgency.
     * @return priority
     */
    public TaskPriority priority() { return priority; }

    /** Whether the card is archived. Archiving is a property, not a fourth column.
     * @return whether the card is archived
     */
    public boolean archived() { return archived; }

    /** Returns the position within its column.
     * @return order index
     */
    public int order() { return order; }

    /** Returns the mind map horizontal position.
     * @return node x coordinate
     */
    public int nodeX() { return nodeX; }

    /** Returns the mind map vertical position.
     * @return node y coordinate
     */
    public int nodeY() { return nodeY; }

    /** Returns the parent card in the mind map, when the card is a subtask.
     * @return parent identity, or empty
     */
    public Optional<UUID> parent() { return Optional.ofNullable(parent); }

    /** Returns the cards this one waits for. A dependency is not a parent relation.
     * @return immutable dependency identities
     */
    public Set<UUID> dependencies() { return Collections.unmodifiableSet(dependencies); }

    /** Returns the assigned players.
     * @return immutable assignee identities
     */
    public Set<UUID> assignees() { return Collections.unmodifiableSet(assignees); }

    /** Returns the crafting objective of a CRAFT card.
     * @return objective, or empty for a manual task
     */
    public Optional<CraftObjective> objective() { return Optional.ofNullable(objective); }

    /** Marks one accepted mutation. */
    public void touch() { revision++; }
    private net.minecraft.nbt.CompoundTag unresolvedObjective;
    public Optional<net.minecraft.nbt.CompoundTag> unresolvedObjective() {
        return Optional.ofNullable(unresolvedObjective).map(net.minecraft.nbt.CompoundTag::copy);
    }
    public void preserveUnresolvedObjective(net.minecraft.nbt.CompoundTag tag) { unresolvedObjective = tag.copy(); }
    private int derivedIngredient = -1;
    public int derivedIngredient() { return derivedIngredient; }
    public void setDerivedIngredient(int index) {
        if (index < -1 || index >= 9) throw new IllegalArgumentException("Invalid derived ingredient");
        derivedIngredient = index;
        touch();
    }

    /** Renames the card.
     * @param value new label
     */
    public void setTitle(String value) { title = validTitle(value); touch(); }

    /** Replaces the description.
     * @param value new description, at most {@value #MAX_DESCRIPTION} characters
     */
    public void setDescription(String value) {
        String text = Objects.requireNonNull(value, "description").strip();
        if (text.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("Description too long");
        description = text;
        touch();
    }

    /** Changes the urgency shown on the card.
     * @param value new priority
     */
    public void setPriority(TaskPriority value) { priority = Objects.requireNonNull(value, "priority"); touch(); }

    /** Archives or reopens the card without changing its column.
     * @param value whether the card is archived
     */
    public void setArchived(boolean value) { archived = value; touch(); }

    /** Sets the position within its column.
     * @param value order index, never negative
     */
    public void setOrder(int value) { int next = Math.max(0, value); if (order != next) { order = next; touch(); } }

    /** Moves the mind map node.
     * @param x horizontal position
     * @param y vertical position
     */
    public void setNodePosition(int x, int y) { nodeX = x; nodeY = y; touch(); }

    /** Sets or clears the parent card.
     * @param value parent identity, or null to detach
     */
    public void setParent(UUID value) {
        if (id.equals(value)) throw new IllegalArgumentException("A card cannot be its own parent");
        parent = value;
        touch();
    }

    /** Adds a dependency on another card.
     * @param other card this one waits for
     * @return whether the dependency was added
     */
    public boolean addDependency(UUID other) {
        Objects.requireNonNull(other, "other");
        if (id.equals(other)) throw new IllegalArgumentException("A card cannot depend on itself");
        if (dependencies.size() >= MAX_DEPENDENCIES) throw new IllegalStateException("Too many dependencies");
        boolean added = dependencies.add(other);
        if (added) touch();
        return added;
    }

    /** Removes a dependency.
     * @param other card to stop waiting for
     * @return whether the dependency existed
     */
    public boolean removeDependency(UUID other) {
        boolean removed = dependencies.remove(Objects.requireNonNull(other, "other"));
        if (removed) touch();
        return removed;
    }

    /** Assigns a player to the card.
     * @param player player to assign
     * @return whether the assignment changed
     */
    public boolean assign(UUID player) {
        Objects.requireNonNull(player, "player");
        if (assignees.size() >= MAX_ASSIGNEES) throw new IllegalStateException("Too many assignees");
        boolean added = assignees.add(player);
        if (added) touch();
        return added;
    }

    /** Removes an assignment.
     * @param player player to unassign
     * @return whether the assignment changed
     */
    public boolean unassign(UUID player) {
        boolean removed = assignees.remove(Objects.requireNonNull(player, "player"));
        if (removed) touch();
        return removed;
    }

    /**
     * Sets the column directly.
     *
     * <p>Callers apply {@link CardTransitions} first: this method records the outcome of
     * a decision, it does not make one.</p>
     *
     * @param value new status
     */
    public void setStatus(TaskStatus value) { status = Objects.requireNonNull(value, "status"); touch(); }

    /** Attaches the crafting objective of a CRAFT card.
     * @param value objective, never null for a CRAFT card
     */
    public void setObjective(CraftObjective value) {
        if (type != TaskType.CRAFT) throw new IllegalStateException("Only a CRAFT card carries an objective");
        objective = Objects.requireNonNull(value, "objective");
        touch();
    }

    /** Restores persisted state without recording a new history entry.
     * @param restoredStatus persisted column
     * @param restoredRevision persisted revision
     */
    public void restoreState(TaskStatus restoredStatus, long restoredRevision) {
        status = Objects.requireNonNull(restoredStatus, "status");
        revision = Math.max(1, restoredRevision);
    }
}
