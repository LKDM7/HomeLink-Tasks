package fr.lkdm.homelink.tasks.server;

import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.production.ReceiptDeduplicator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Everything this mod keeps between two runs of a server.
 *
 * <p>Boards, cards, roles, assignments, ordering, the mind map graph, objectives,
 * progress, contributions, revisions, network attachments, personal pins and the memory
 * of already credited batches all live here, in the Overworld's saved data. None of it
 * depends on a block being loaded.</p>
 *
 * <p>Pins are per player and per world: connecting to another server shows that
 * server's pins, never the previous one's.</p>
 */
public final class TaskSavedData extends SavedData {
    /** Identifier of the saved data file. */
    public static final String FILE_ID = "homelink_tasks";
    /** Largest number of cards one player may pin. */
    public static final int MAX_PINS = 5;
    /** Largest number of boards one player may own. */
    public static final int MAX_BOARDS_PER_OWNER = 32;

    private final Map<UUID, TaskBoard> boards = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> pins = new LinkedHashMap<>();
    private final Map<UUID, UUID> tracked = new LinkedHashMap<>();
    private final ReceiptDeduplicator deduplicator = new ReceiptDeduplicator();
    private final Map<UUID, String> knownPlayers = new LinkedHashMap<>();
    public Map<UUID, String> knownPlayers() { return Map.copyOf(knownPlayers); }
    public void recordPlayer(UUID id, String name) {
        if (knownPlayers.size() >= 1024 && !knownPlayers.containsKey(id)) return;
        if (!name.equals(knownPlayers.put(id, name))) setDirty();
    }

    private TaskSavedData() { }

    /** Returns the saved data of a running server, creating it on first use.
     * @param server running server
     * @return persistent task data
     */
    public static TaskSavedData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) throw new IllegalStateException("Task data requires the server thread");
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(TaskSavedData::new, TaskSavedData::load), FILE_ID);
    }

    private static TaskSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TaskSavedData data = new TaskSavedData();
        int version = tag.getInt("Version");
        // Version 0 is a file written before the format was numbered; nothing to migrate yet.
        if (version > TaskStorageFormat.VERSION) throw new IllegalStateException("Tasks save format is newer than this mod; refusing data loss");
        ListTag known = tag.getList("KnownPlayers", Tag.TAG_COMPOUND);
        for (int index = 0; index < known.size() && index < 1024; index++) {
            CompoundTag entry = known.getCompound(index);
            if (entry.hasUUID("Id")) data.knownPlayers.put(entry.getUUID("Id"), entry.getString("Name"));
        }
        ListTag boards = tag.getList("Boards", Tag.TAG_COMPOUND);
        for (int index = 0; index < boards.size(); index++) {
            TaskStorageFormat.readBoard(boards.getCompound(index), registries)
                    .ifPresent(board -> data.boards.put(board.id(), board));
        }
        ListTag pins = tag.getList("Pins", Tag.TAG_COMPOUND);
        for (int index = 0; index < pins.size(); index++) {
            CompoundTag entry = pins.getCompound(index);
            if (!entry.hasUUID("Player")) continue;
            List<UUID> cards = new ArrayList<>();
            ListTag pinned = entry.getList("Cards", Tag.TAG_INT_ARRAY);
            for (int slot = 0; slot < pinned.size() && cards.size() < MAX_PINS; slot++) {
                cards.add(NbtUtils.loadUUID(pinned.get(slot)));
            }
            data.pins.put(entry.getUUID("Player"), cards);
        }
        ListTag tracked = tag.getList("Tracked", Tag.TAG_COMPOUND);
        for (int index = 0; index < tracked.size(); index++) {
            CompoundTag entry = tracked.getCompound(index);
            if (entry.hasUUID("Player") && entry.hasUUID("Card")) {
                data.tracked.put(entry.getUUID("Player"), entry.getUUID("Card"));
            }
        }
        List<UUID> receipts = new ArrayList<>();
        ListTag credited = tag.getList("CreditedBatches", Tag.TAG_INT_ARRAY);
        for (int index = 0; index < credited.size(); index++) receipts.add(NbtUtils.loadUUID(credited.get(index)));
        data.deduplicator.restore(receipts);
        data.prune();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Version", TaskStorageFormat.VERSION);
        ListTag known = new ListTag();
        knownPlayers.forEach((id, name) -> {
            CompoundTag entry = new CompoundTag(); entry.putUUID("Id", id); entry.putString("Name", name); known.add(entry);
        });
        tag.put("KnownPlayers", known);
        ListTag boardTags = new ListTag();
        for (TaskBoard board : boards.values()) boardTags.add(TaskStorageFormat.writeBoard(board, registries));
        tag.put("Boards", boardTags);
        ListTag pinTags = new ListTag();
        pins.forEach((player, cards) -> {
            if (cards.isEmpty()) return;
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            ListTag pinned = new ListTag();
            cards.forEach(card -> pinned.add(NbtUtils.createUUID(card)));
            entry.put("Cards", pinned);
            pinTags.add(entry);
        });
        tag.put("Pins", pinTags);
        ListTag trackedTags = new ListTag();
        tracked.forEach((player, card) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Player", player);
            entry.putUUID("Card", card);
            trackedTags.add(entry);
        });
        tag.put("Tracked", trackedTags);
        ListTag credited = new ListTag();
        deduplicator.entries().forEach(transaction -> credited.add(NbtUtils.createUUID(transaction)));
        tag.put("CreditedBatches", credited);
        return tag;
    }

    /** Returns every board.
     * @return immutable snapshot
     */
    public List<TaskBoard> boards() { return List.copyOf(boards.values()); }

    /** Looks a board up.
     * @param board board identity
     * @return board, or empty
     */
    public Optional<TaskBoard> board(UUID board) {
        return Optional.ofNullable(boards.get(Objects.requireNonNull(board, "board")));
    }

    /** Returns the boards a player is a member of.
     * @param player player identity
     * @return boards the player may open
     */
    public List<TaskBoard> boardsFor(UUID player) {
        Objects.requireNonNull(player, "player");
        return boards.values().stream().filter(board -> board.roleOf(player).isPresent()).toList();
    }

    /** Adds a board, refusing to exceed an owner's bound.
     * @param board board to add
     */
    public void addBoard(TaskBoard board) {
        Objects.requireNonNull(board, "board");
        long owned = boards.values().stream().filter(other -> other.owner().equals(board.owner())).count();
        if (!boards.containsKey(board.id()) && owned >= MAX_BOARDS_PER_OWNER) {
            throw new IllegalStateException("Board limit reached for this owner");
        }
        boards.put(board.id(), board);
        setDirty();
    }

    /**
     * Deletes a board and everything that pointed at it.
     *
     * <p>Pins and tracking choices referring to its cards are dropped too, so nothing
     * keeps showing a project that no longer exists.</p>
     *
     * @param board board identity
     * @return whether a board was deleted
     */
    public boolean removeBoard(UUID board) {
        TaskBoard removed = boards.remove(Objects.requireNonNull(board, "board"));
        if (removed == null) return false;
        Set<UUID> cards = new LinkedHashSet<>();
        removed.cards().forEach(card -> cards.add(card.id()));
        pins.values().forEach(pinned -> pinned.removeAll(cards));
        tracked.values().removeIf(cards::contains);
        setDirty();
        return true;
    }

    /**
     * Drops everything a player may no longer see.
     *
     * <p>Called when access is revoked, so the data stops being delivered rather than
     * being hidden on the client.</p>
     *
     * @param player player whose access changed
     */
    public void purgeInaccessible(UUID player) {
        Objects.requireNonNull(player, "player");
        List<UUID> pinned = pins.get(player);
        int previousSize = pinned == null ? 0 : pinned.size();
        if (pinned != null) {
            pinned.removeIf(card -> !visible(player, card));
            if (pinned.isEmpty()) pins.remove(player);
        }
        UUID choice = tracked.get(player);
        if (choice != null && !visible(player, choice)) tracked.remove(player);
        if (previousSize != pins(player).size() || choice != null && !visible(player, choice)) setDirty();
    }

    private boolean visible(UUID player, UUID card) {
        return boards.values().stream()
                .anyMatch(board -> board.roleOf(player).isPresent() && board.card(card).isPresent());
    }

    /** Returns the cards a player pinned to their own HUD.
     * @param player player identity
     * @return immutable pinned card identities, in pin order
     */
    public List<UUID> pins(UUID player) {
        return List.copyOf(pins.getOrDefault(Objects.requireNonNull(player, "player"), List.of()));
    }

    /**
     * Pins or unpins a card for one player only.
     *
     * <p>A pin is personal: it never appears on another player's HUD, and it never
     * changes which card a finished batch is credited to.</p>
     *
     * @param player player identity
     * @param card card identity
     * @param pinned whether the card should be pinned
     * @param limit effective pin limit, clamped to 1..{@value #MAX_PINS}
     * @return whether the pins changed
     */
    public boolean setPinned(UUID player, UUID card, boolean pinned, int limit) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(card, "card");
        List<UUID> current = pins.computeIfAbsent(player, ignored -> new ArrayList<>());
        boolean changed;
        if (pinned) {
            if (current.contains(card)) return false;
            if (current.size() >= Math.clamp(limit, 1, MAX_PINS)) return false;
            changed = current.add(card);
        } else {
            changed = current.remove(card);
        }
        if (current.isEmpty()) pins.remove(player);
        if (changed) setDirty();
        return changed;
    }

    /** Returns the card a player deliberately chose to track, which is not their pins.
     * @param player player identity
     * @return tracked card identity, or empty
     */
    public Optional<UUID> trackedCard(UUID player) {
        return Optional.ofNullable(tracked.get(Objects.requireNonNull(player, "player")));
    }

    /** Chooses or clears the card a player's own crafts are credited to first.
     * @param player player identity
     * @param card card identity, or null to clear
     */
    public void setTrackedCard(UUID player, UUID card) {
        Objects.requireNonNull(player, "player");
        if (card == null) tracked.remove(player); else tracked.put(player, card);
        setDirty();
    }

    /** Returns the memory of already credited batches.
     * @return deduplication memory
     */
    public ReceiptDeduplicator deduplicator() { return deduplicator; }

    /** Returns the pins of every player, for inspection.
     * @return immutable view
     */
    public Map<UUID, List<UUID>> allPins() { return Collections.unmodifiableMap(pins); }

    /** Drops pins and tracking choices pointing at cards that no longer exist. */
    public void prune() {
        pins.values().forEach(pinned -> pinned.removeIf(card -> boards.values().stream()
                .noneMatch(board -> board.card(card).isPresent())));
        pins.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        tracked.values().removeIf(card -> boards.values().stream()
                .noneMatch(board -> board.card(card).isPresent()));
    }
}
