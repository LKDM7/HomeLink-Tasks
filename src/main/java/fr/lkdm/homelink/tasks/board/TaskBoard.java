package fr.lkdm.homelink.tasks.board;

import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One project: its members, its cards and, optionally, the network it reads stock from.
 *
 * <p>A board lives in the mod's own saved data, not in a block entity, so breaking the
 * screen that displayed it destroys nothing. An unattached board still works: its
 * availability hints are then computed from the viewing player's own inventory.</p>
 *
 * <p>Attaching a board to a HomeNetwork grants no permission by itself. Reading stock
 * through it still requires the player's own HomeCore rights on that network.</p>
 */
public final class TaskBoard {
    /** Largest title length accepted. */
    public static final int MAX_TITLE = 80;
    /** Largest description length accepted. */
    public static final int MAX_DESCRIPTION = 2000;
    /** Largest number of members one board accepts. */
    public static final int MAX_MEMBERS = 32;
    /** Largest number of cards one board exposes, including archives. */
    public static final int MAX_CARDS = 256;

    private final UUID id;
    private UUID owner;
    private final long createdTick;
    private final Map<UUID, BoardRole> members = new LinkedHashMap<>();
    private final Map<UUID, TaskCard> cards = new LinkedHashMap<>();
    private final List<TaskCard> savedOverflow = new ArrayList<>();

    /** Keeps legacy cards beyond the current wire bound for the next save.
     * @param card valid legacy card belonging to this board
     */
    public void preserveOverflowCard(TaskCard card) {
        if (!id.equals(card.boardId())) throw new IllegalArgumentException("Wrong board");
        savedOverflow.add(card);
    }

    /** Returns legacy cards kept for persistence but not exposed or tracked.
     * @return preserved legacy entries
     */
    public List<TaskCard> savedOverflowCards() { return List.copyOf(savedOverflow); }

    private String title;
    private String description = "";
    private UUID networkId;
    private boolean archived;
    private long revision = 1;

    /**
     * Creates a board owned by its creator.
     *
     * @param id stable board identity
     * @param title label, 1 to {@value #MAX_TITLE} characters
     * @param owner owning player
     * @param createdTick server tick of creation
     */
    public TaskBoard(UUID id, String title, UUID owner, long createdTick) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        if (createdTick < 0) throw new IllegalArgumentException("Creation tick must not be negative");
        this.createdTick = createdTick;
        this.title = validText(title, MAX_TITLE, "title");
        members.put(owner, BoardRole.OWNER);
    }

    private static String validText(String value, int max, String what) {
        String trimmed = Objects.requireNonNull(value, what).strip();
        if (trimmed.isEmpty() || trimmed.length() > max) {
            throw new IllegalArgumentException("A " + what + " holds 1 to " + max + " characters");
        }
        return trimmed;
    }

    /** Returns the stable board identity.
     * @return board UUID
     */
    public UUID id() { return id; }

    /** Returns the owning player.
     * @return owner identity
     */
    public UUID owner() { return owner; }

    /** Returns the server tick the board was created at.
     * @return creation tick
     */
    public long createdTick() { return createdTick; }

    /** Returns the current revision, bumped by every accepted mutation.
     * @return revision number
     */
    public long revision() { return revision; }

    /** Returns the board label.
     * @return title
     */
    public String title() { return title; }

    /** Returns the board description.
     * @return description, possibly empty
     */
    public String description() { return description; }

    /** Whether the board is archived.
     * @return whether the board is archived
     */
    public boolean archived() { return archived; }

    /** Returns the HomeNetwork this board reads stock from, when it has one.
     * @return network identity, or empty for an inventory-only board
     */
    public Optional<UUID> networkId() { return Optional.ofNullable(networkId); }

    /** Returns every member and their role.
     * @return immutable view of memberships
     */
    public Map<UUID, BoardRole> members() { return Collections.unmodifiableMap(members); }

    /** Returns the role a player holds on this board.
     * @param player player identity
     * @return role, or empty when the player is not a member
     */
    public Optional<BoardRole> roleOf(UUID player) {
        return Optional.ofNullable(members.get(Objects.requireNonNull(player, "player")));
    }

    /** Marks one accepted mutation. */
    public void touch() { revision++; }

    public void transferOwnership(UUID player) {
        if (!members.containsKey(player)) throw new IllegalArgumentException("New owner must be a member");
        if (owner.equals(player)) return;
        members.put(owner, BoardRole.EDITOR);
        owner = player;
        members.put(owner, BoardRole.OWNER);
        touch();
    }

    /** Rebuilds contiguous order in the affected column after a move or reorder. */
    public void insertCard(TaskCard moved, TaskStatus previous, int requestedOrder) {
        List<TaskCard> destination = new ArrayList<>(column(moved.status()));
        destination.remove(moved);
        destination.add(Math.clamp(requestedOrder, 0, destination.size()), moved);
        for (int index = 0; index < destination.size(); index++) destination.get(index).setOrder(index);
        if (previous != moved.status()) {
            List<TaskCard> source = column(previous);
            for (int index = 0; index < source.size(); index++) source.get(index).setOrder(index);
        }
    }

    /** Renames the board.
     * @param value new label
     */
    public void setTitle(String value) { title = validText(value, MAX_TITLE, "title"); touch(); }

    /** Replaces the description.
     * @param value new description, at most {@value #MAX_DESCRIPTION} characters
     */
    public void setDescription(String value) {
        String text = Objects.requireNonNull(value, "description").strip();
        if (text.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("Description too long");
        description = text;
        touch();
    }

    /** Archives or reopens the board.
     * @param value whether the board is archived
     */
    public void setArchived(boolean value) { archived = value; touch(); }

    /**
     * Attaches the board to a HomeNetwork, or detaches it.
     *
     * <p>Only the caller's already validated HomeCore rights make this legitimate; this
     * method records a decision and grants nothing.</p>
     *
     * @param network network identity, or null to work from player inventories only
     */
    public void setNetworkId(UUID network) { networkId = network; touch(); }

    /**
     * Adds or changes a member role. Ownership is not transferred this way.
     *
     * @param player player identity
     * @param role role to grant
     * @return whether membership changed
     */
    public boolean setMember(UUID player, BoardRole role) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(role, "role");
        if (owner.equals(player) != (role == BoardRole.OWNER)) {
            throw new IllegalArgumentException("Only the owner holds the OWNER role");
        }
        if (!members.containsKey(player) && members.size() >= MAX_MEMBERS) {
            throw new IllegalStateException("Board member limit reached");
        }
        if (members.get(player) == role) return false;
        members.put(player, role);
        touch();
        return true;
    }

    /**
     * Removes a member. The caller is responsible for also dropping their subscriptions
     * and pins, so revoked access stops delivering data rather than merely hiding it.
     *
     * @param player player identity
     * @return whether membership changed
     */
    public boolean removeMember(UUID player) {
        Objects.requireNonNull(player, "player");
        if (owner.equals(player)) throw new IllegalArgumentException("The owner cannot be removed");
        if (members.remove(player) == null) return false;
        touch();
        return true;
    }

    /** Adds a card to the board.
     * @param card card to add
     */
    public void addCard(TaskCard card) {
        Objects.requireNonNull(card, "card");
        if (!id.equals(card.boardId())) throw new IllegalArgumentException("Card belongs to another board");
        if (!cards.containsKey(card.id()) && cards.size() >= MAX_CARDS) {
            throw new IllegalStateException("Board card limit reached");
        }
        cards.put(card.id(), card);
        touch();
    }

    /**
     * Removes a card and detaches every reference to it.
     *
     * <p>Nothing is left pointing at a deleted card: a dependency on it disappears and a
     * subtask of it is reattached to the board root.</p>
     *
     * @param card card identity
     * @return removed card, or empty
     */
    public Optional<TaskCard> removeCard(UUID card) {
        TaskCard removed = cards.remove(Objects.requireNonNull(card, "card"));
        if (removed == null) return Optional.empty();
        for (TaskCard other : cards.values()) {
            other.removeDependency(card);
            if (other.parent().filter(card::equals).isPresent()) other.setParent(null);
        }
        touch();
        return Optional.of(removed);
    }

    /** Looks a card up.
     * @param card card identity
     * @return card, or empty
     */
    public Optional<TaskCard> card(UUID card) {
        return Optional.ofNullable(cards.get(Objects.requireNonNull(card, "card")));
    }

    /** Returns every card, including archived ones.
     * @return immutable snapshot in insertion order
     */
    public List<TaskCard> cards() { return List.copyOf(cards.values()); }

    /** Returns the number of cards that are not archived.
     * @return active card count
     */
    public int activeCardCount() {
        return (int) cards.values().stream().filter(card -> !card.archived()).count();
    }

    /**
     * Returns the unarchived cards of one column, in display order.
     *
     * @param status column to list
     * @return ordered cards
     */
    public List<TaskCard> column(TaskStatus status) {
        Objects.requireNonNull(status, "status");
        List<TaskCard> column = new ArrayList<>();
        for (TaskCard card : cards.values()) {
            if (!card.archived() && card.status() == status) column.add(card);
        }
        column.sort(Comparator.comparingInt(TaskCard::order).thenComparing(TaskCard::createdTick).thenComparing(TaskCard::id));
        return List.copyOf(column);
    }

    /** Restores the persisted revision without recording a mutation.
     * @param restored persisted revision
     */
    public void restoreRevision(long restored) { revision = Math.max(1, restored); }
}
