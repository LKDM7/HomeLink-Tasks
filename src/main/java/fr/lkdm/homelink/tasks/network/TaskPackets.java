package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.HomeLinkTasks;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Every message exchanged between a client and this mod's server side.
 *
 * <p>All payloads are bounded and typed. A mutation carries the revision the client
 * believed it was editing, so two players moving the same card at once get an explicit
 * conflict and a fresh state rather than a lost edit or a duplicated card.</p>
 *
 * <p>No payload carries an acting identity: the server always uses the authenticated
 * player of the connection. A client can name a target, never who it is.</p>
 */
public final class TaskPackets {
    /** Largest number of boards one list carries. */
    public static final int MAX_BOARDS = 64;
    /** Largest number of pinned cards one HUD update carries. */
    public static final int MAX_PINNED = 5;

    private TaskPackets() { }

    private static CustomPacketPayload.Type<?> payloadType(String path) {
        return new CustomPacketPayload.Type<>(HomeLinkTasks.id(path));
    }

    /** Board-level operations a client may ask for. */
    public enum BoardCommand {
        /** Open a board this player may see, or refresh it. */ OPEN,
        /** Create a new board owned by the asking player. */ CREATE,
        /** Stop the GUI subscription without changing pins. */ CLOSE,
        /** Select the board shown by a nearby screen the player may configure. */ SELECT_DISPLAY,
        /** Rename an existing board. */ RENAME,
        /** Replace a board's description. */ DESCRIBE,
        /** Archive or reopen a board. */ ARCHIVE,
        /** Delete a board, after the client has confirmed. */ DELETE,
        /** Add a member or change their role. */ SET_MEMBER,
        /** Remove a member and drop their subscriptions and pins. */ REMOVE_MEMBER,
        /** Transfer ownership to another member. */ TRANSFER_OWNERSHIP,
        /** Attach this board to the network of the screen being used, or detach it. */ ATTACH_NETWORK
    }

    /** Card-level operations a client may ask for. */
    public enum CardCommand {
        /** Create a free task. */ CREATE_MANUAL,
        /** Create a crafting objective. */ CREATE_CRAFT,
        /** Rename a card. */ RENAME,
        /** Replace a card's description. */ DESCRIBE,
        /** Move a card to another column. */ MOVE,
        /** Change a card's position within its column. */ REORDER,
        /** Move a mind map node. */ NODE,
        /** Change a card's priority. */ PRIORITY,
        /** Assign a player to a card. */ ASSIGN,
        /** Remove an assignment. */ UNASSIGN,
        /** Claim an unassigned card and start it. */ CLAIM,
        /** Archive or reopen a card. */ ARCHIVE,
        /** Delete a card, after the client has confirmed. */ DELETE,
        /** Set or clear the parent card of a subtask. */ SET_PARENT,
        /** Declare that this card waits for another. */ ADD_DEPENDENCY,
        /** Stop waiting for another card. */ REMOVE_DEPENDENCY,
        /** Choose another reference recipe for planning. */ SELECT_RECIPE,
        /** Restrict contributions to the selected recipe, or reopen them. */ LOCK_RECIPE,
        /** Change who may contribute. */ SET_POLICY,
        /** Change the wanted quantity without discarding progress. */ SET_QUANTITY,
        /** Copy a finished card into a new, empty objective. */ REPEAT,
        /** Expand one explicitly selected ingredient into a zero-progress child. */ EXPAND_INGREDIENT
    }

    /** Personal choices that affect only the asking player. */
    public enum PersonalCommand {
        /** Pin a card to this player's own HUD. */ PIN,
        /** Remove a pin. */ UNPIN,
        /** Credit this player's own crafts to a card first. */ TRACK,
        /** Stop tracking a card. */ UNTRACK,
        /** Ask for a fresh availability calculation for a visible card. */ REQUEST_PLAN
    }

    /** Short outcomes a server sends back for a refused or conflicting request. */
    public enum Notice {
        /** The request needed a right the player does not hold. */ DENIED,
        /** The card changed since the client read it; a fresh state follows. */ CONFLICT,
        /** A crafting card cannot be dropped into Done while items are missing. */ CRAFT_INCOMPLETE,
        /** A crafting card that already produced something does not go back to To do. */ CRAFT_STARTED,
        /** The requested dependency would create a cycle. */ DEPENDENCY_CYCLE,
        /** A bound was reached: boards, cards, members, assignees, dependencies or pins. */ LIMIT_REACHED,
        /** The request arrived faster than the server serves them. */ TOO_FAST,
        /** The recipe cannot be described, so no plan can be shown. */ RECIPE_UNSUPPORTED,
        /** Nobody is assigned while the card only accepts assignees. */ NO_CONTRIBUTORS
    }

    /**
     * A board a player may open, as listed on the welcome screen.
     *
     * @param id board identity
     * @param title board label
     * @param archived whether the board is archived
     * @param cardCount number of cards that are not archived
     * @param doneCount number of those cards that are finished
     * @param memberCount number of members
     * @param role the listing player's own role
     */
    public record BoardSummary(UUID id, String title, boolean archived, int cardCount, int doneCount,
                               int memberCount, BoardRole role) {
        /** Validates the summary. */
        public BoardSummary {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(role, "role");
            title = Objects.requireNonNull(title, "title");
        }

        /** Summarises a board for one of its members.
         * @param board board to summarise
         * @param viewer listing player
         * @return summary
         */
        public static BoardSummary of(TaskBoard board, UUID viewer) {
            int done = 0;
            for (TaskCard card : board.cards()) {
                if (!card.archived() && card.status() == TaskStatus.DONE) done++;
            }
            return new BoardSummary(board.id(), board.title(), board.archived(), board.activeCardCount(), done,
                    board.members().size(), board.roleOf(viewer).orElse(BoardRole.VIEWER));
        }

        static final StreamCodec<RegistryFriendlyByteBuf, BoardSummary> CODEC = StreamCodec.of(
                (buffer, summary) -> {
                    buffer.writeUUID(summary.id());
                    buffer.writeUtf(summary.title(), TaskBoard.MAX_TITLE);
                    buffer.writeBoolean(summary.archived());
                    buffer.writeVarInt(summary.cardCount());
                    buffer.writeVarInt(summary.doneCount());
                    buffer.writeVarInt(summary.memberCount());
                    buffer.writeEnum(summary.role());
                },
                buffer -> new BoardSummary(buffer.readUUID(), buffer.readUtf(TaskBoard.MAX_TITLE),
                        buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readEnum(BoardRole.class)));
    }

    /**
     * A card kept on one player's own HUD.
     *
     * @param card card identity
     * @param board owning board
     * @param title card label
     * @param status column the card sits in
     * @param target wanted item for a crafting card
     * @param completed finished items already credited
     * @param wanted finished items wanted
     * @param availability availability state computed for this player
     */
    public record PinnedView(UUID card, UUID board, String title, TaskStatus status, ItemStack target,
                             int completed, int wanted,
                             fr.lkdm.homelink.tasks.stock.AvailabilityState availability, boolean storageConfigured) {
        /** Validates the pinned view. */
        public PinnedView {
            Objects.requireNonNull(card, "card");
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(availability, "availability");
            title = Objects.requireNonNull(title, "title");
        }

        static final StreamCodec<RegistryFriendlyByteBuf, PinnedView> CODEC = StreamCodec.of(
                (buffer, view) -> {
                    buffer.writeUUID(view.card());
                    buffer.writeUUID(view.board());
                    buffer.writeUtf(view.title(), TaskCard.MAX_TITLE);
                    buffer.writeEnum(view.status());
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, view.target());
                    buffer.writeVarInt(view.completed());
                    buffer.writeVarInt(view.wanted());
                    buffer.writeEnum(view.availability());
                    buffer.writeBoolean(view.storageConfigured());
                },
                buffer -> new PinnedView(buffer.readUUID(), buffer.readUUID(), buffer.readUtf(TaskCard.MAX_TITLE),
                        buffer.readEnum(TaskStatus.class), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                        buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readEnum(fr.lkdm.homelink.tasks.stock.AvailabilityState.class), buffer.readBoolean()));
    }

    /**
     * Server to client: the boards a player may open and, optionally, the one to show.
     *
     * @param board board to open, absent when only the list is sent
     * @param available boards this player may open
     * @param screen position of the screen that triggered the opening, when there was one
     */
    public record BoardSnapshot(Optional<BoardView> board, List<BoardSummary> available,
                                Optional<net.minecraft.core.BlockPos> screen, boolean navigate) implements CustomPacketPayload {
        public BoardSnapshot(Optional<BoardView> board, List<BoardSummary> available,
                             Optional<net.minecraft.core.BlockPos> screen) {
            this(board, available, screen, true);
        }
        /** Wire identifier. */
        public static final CustomPacketPayload.Type<BoardSnapshot> TYPE = cast(payloadType("board_snapshot"));
        /** Wire codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, BoardSnapshot> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeOptional(payload.board(), (target, value) -> BoardView.CODEC.encode(buffer, value));
                    buffer.writeVarInt(payload.available().size());
                    payload.available().forEach(summary -> BoardSummary.CODEC.encode(buffer, summary));
                    buffer.writeOptional(payload.screen(), (target, value) -> buffer.writeBlockPos(value));
                    buffer.writeBoolean(payload.navigate());
                },
                buffer -> {
                    Optional<BoardView> board = buffer.readOptional(source -> BoardView.CODEC.decode(buffer));
                    int count = ByteBufCodecs.readCount(buffer, MAX_BOARDS);
                    List<BoardSummary> available = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) available.add(BoardSummary.CODEC.decode(buffer));
                    return new BoardSnapshot(board, available,
                            buffer.readOptional(source -> buffer.readBlockPos()), buffer.readBoolean());
                });

        /** Validates and copies the list. */
        public BoardSnapshot {
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(screen, "screen");
            available = List.copyOf(Objects.requireNonNull(available, "available"));
            if (available.size() > MAX_BOARDS) throw new IllegalArgumentException("Too many boards listed");
        }

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server to client: a fresh availability calculation for one card.
     *
     * @param plan the receiving player's own plan
     */
    public record CardPlan(PlanView plan) implements CustomPacketPayload {
        /** Wire identifier. */
        public static final CustomPacketPayload.Type<CardPlan> TYPE = cast(payloadType("card_plan"));
        /** Wire codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, CardPlan> CODEC = StreamCodec.of(
                (buffer, payload) -> PlanView.CODEC.encode(buffer, payload.plan()),
                buffer -> new CardPlan(PlanView.CODEC.decode(buffer)));

        /** Validates the payload. */
        public CardPlan { Objects.requireNonNull(plan, "plan"); }

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server to client: the cards this player pinned, for their own HUD.
     *
     * @param pinned pinned cards, in pin order
     */
    public record PinnedTasks(List<PinnedView> pinned, Optional<UUID> tracked) implements CustomPacketPayload {
        public PinnedTasks(List<PinnedView> pinned) { this(pinned, Optional.empty()); }
        /** Wire identifier. */
        public static final CustomPacketPayload.Type<PinnedTasks> TYPE = cast(payloadType("pinned_tasks"));
        /** Wire codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, PinnedTasks> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeVarInt(payload.pinned().size());
                    payload.pinned().forEach(view -> PinnedView.CODEC.encode(buffer, view));
                    buffer.writeOptional(payload.tracked(), (buf, id) -> buf.writeUUID(id));
                },
                buffer -> {
                    int count = ByteBufCodecs.readCount(buffer, MAX_PINNED);
                    List<PinnedView> pinned = new ArrayList<>(count);
                    for (int index = 0; index < count; index++) pinned.add(PinnedView.CODEC.decode(buffer));
                    return new PinnedTasks(pinned, buffer.readOptional(buf -> buf.readUUID()));
                });

        /** Validates and copies the list. */
        public PinnedTasks {
            pinned = List.copyOf(Objects.requireNonNull(pinned, "pinned"));
            if (pinned.size() > MAX_PINNED) throw new IllegalArgumentException("Too many pinned cards");
        }

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server to client: why a request was refused, so the optimistic drawing is corrected.
     *
     * @param notice short outcome
     * @param card card the outcome concerns, when it concerns one
     */
    public record NoticeMessage(Notice notice, Optional<UUID> card) implements CustomPacketPayload {
        /** Wire identifier. */
        public static final CustomPacketPayload.Type<NoticeMessage> TYPE = cast(payloadType("notice"));
        /** Wire codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, NoticeMessage> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeEnum(payload.notice());
                    buffer.writeOptional(payload.card(), (target, value) -> buffer.writeUUID(value));
                },
                buffer -> new NoticeMessage(buffer.readEnum(Notice.class),
                        buffer.readOptional(source -> buffer.readUUID())));

        /** Validates the payload. */
        public NoticeMessage {
            Objects.requireNonNull(notice, "notice");
            Objects.requireNonNull(card, "card");
        }

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client to server: a board-level request.
     *
     * @param command what is being asked
     * @param board board concerned, absent when creating one
     * @param text new title, description or nothing
     * @param target member concerned, when the command concerns one
     * @param role role to grant, when the command grants one
     * @param flag archive or attach flag
     * @param screen screen the request came from, used to resolve a network attachment
     * @param expectedRevision revision the client believed it was editing
     */
    public record BoardRequest(BoardCommand command, Optional<UUID> board, String text, Optional<UUID> target,
                               BoardRole role, boolean flag, Optional<net.minecraft.core.BlockPos> screen,
                               long expectedRevision, UUID operationId) implements CustomPacketPayload {
        public BoardRequest(BoardCommand command, Optional<UUID> board, String text, Optional<UUID> target,
                            BoardRole role, boolean flag, Optional<net.minecraft.core.BlockPos> screen, long expectedRevision) {
            this(command, board, text, target, role, flag, screen, expectedRevision, UUID.randomUUID());
        }
        /** Wire identifier. */
        public static final CustomPacketPayload.Type<BoardRequest> TYPE = cast(payloadType("board_request"));
        /** Wire codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, BoardRequest> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeEnum(payload.command());
                    buffer.writeOptional(payload.board(), (target, value) -> buffer.writeUUID(value));
                    buffer.writeUtf(payload.text(), TaskBoard.MAX_DESCRIPTION);
                    buffer.writeOptional(payload.target(), (target, value) -> buffer.writeUUID(value));
                    buffer.writeEnum(payload.role());
                    buffer.writeBoolean(payload.flag());
                    buffer.writeOptional(payload.screen(), (target, value) -> buffer.writeBlockPos(value));
                    buffer.writeVarLong(payload.expectedRevision());
                    buffer.writeUUID(payload.operationId());
                },
                buffer -> new BoardRequest(buffer.readEnum(BoardCommand.class),
                        buffer.readOptional(source -> buffer.readUUID()),
                        buffer.readUtf(TaskBoard.MAX_DESCRIPTION),
                        buffer.readOptional(source -> buffer.readUUID()),
                        buffer.readEnum(BoardRole.class), buffer.readBoolean(),
                        buffer.readOptional(source -> buffer.readBlockPos()), buffer.readVarLong(), buffer.readUUID()));

        /** Validates the request. */
        public BoardRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(screen, "screen");
            text = Objects.requireNonNull(text, "text");
            if (text.length() > TaskBoard.MAX_DESCRIPTION) throw new IllegalArgumentException("Text too long");
        }

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client to server: a card-level request.
     *
     * @param command what is being asked
     * @param board board the card belongs to
     * @param card card concerned, absent when creating one
     * @param text new title, description or nothing
     * @param target another card or a player, when the command concerns one
     * @param status requested column
     * @param priority requested priority
     * @param quantity requested quantity or order index
     * @param nodeX requested mind map horizontal position
     * @param nodeY requested mind map vertical position
     * @param item wanted item when creating a crafting objective
     * @param recipeId reference recipe when creating or selecting one
     * @param flag lock, archive or policy flag
     * @param expectedRevision revision the client believed it was editing
     */
    public record CardRequest(CardCommand command, UUID board, Optional<UUID> card, String text,
                              Optional<UUID> target, TaskStatus status,
                              fr.lkdm.homelink.tasks.task.TaskPriority priority, int quantity,
                              int nodeX, int nodeY, ItemStack item, Optional<ResourceLocation> recipeId,
                              boolean flag, long expectedRevision, UUID operationId) implements CustomPacketPayload {
        public CardRequest(CardCommand command, UUID board, Optional<UUID> card, String text,
                           Optional<UUID> target, TaskStatus status, fr.lkdm.homelink.tasks.task.TaskPriority priority,
                           int quantity, int nodeX, int nodeY, ItemStack item, Optional<ResourceLocation> recipeId,
                           boolean flag, long expectedRevision) {
            this(command, board, card, text, target, status, priority, quantity, nodeX, nodeY, item, recipeId,
                    flag, expectedRevision, UUID.randomUUID());
        }
        /** Wire identifier. */
        public static final CustomPacketPayload.Type<CardRequest> TYPE = cast(payloadType("card_request"));
        /** Wire codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, CardRequest> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeEnum(payload.command());
                    buffer.writeUUID(payload.board());
                    buffer.writeOptional(payload.card(), (target, value) -> buffer.writeUUID(value));
                    buffer.writeUtf(payload.text(), TaskCard.MAX_DESCRIPTION);
                    buffer.writeOptional(payload.target(), (target, value) -> buffer.writeUUID(value));
                    buffer.writeEnum(payload.status());
                    buffer.writeEnum(payload.priority());
                    buffer.writeVarInt(payload.quantity());
                    buffer.writeVarInt(payload.nodeX() + Short.MAX_VALUE);
                    buffer.writeVarInt(payload.nodeY() + Short.MAX_VALUE);
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, payload.item());
                    buffer.writeOptional(payload.recipeId(), (target, value) -> buffer.writeResourceLocation(value));
                    buffer.writeBoolean(payload.flag());
                    buffer.writeVarLong(payload.expectedRevision());
                    buffer.writeUUID(payload.operationId());
                },
                buffer -> new CardRequest(buffer.readEnum(CardCommand.class), buffer.readUUID(),
                        buffer.readOptional(source -> buffer.readUUID()),
                        buffer.readUtf(TaskCard.MAX_DESCRIPTION),
                        buffer.readOptional(source -> buffer.readUUID()),
                        buffer.readEnum(TaskStatus.class),
                        buffer.readEnum(fr.lkdm.homelink.tasks.task.TaskPriority.class),
                        buffer.readVarInt(), buffer.readVarInt() - Short.MAX_VALUE,
                        buffer.readVarInt() - Short.MAX_VALUE,
                        ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
                        buffer.readOptional(source -> buffer.readResourceLocation()),
                        buffer.readBoolean(), buffer.readVarLong(), buffer.readUUID()));

        /** Validates the request. */
        public CardRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(card, "card");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(recipeId, "recipeId");
            text = Objects.requireNonNull(text, "text");
            if (text.length() > TaskCard.MAX_DESCRIPTION) throw new IllegalArgumentException("Text too long");
        }

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Client to server: a choice that affects only the asking player.
     *
     * @param command what is being asked
     * @param board board the card belongs to
     * @param card card concerned
     */
    public record PersonalRequest(PersonalCommand command, UUID board, UUID card) implements CustomPacketPayload {
        /** Wire identifier. */
        public static final CustomPacketPayload.Type<PersonalRequest> TYPE = cast(payloadType("personal_request"));
        /** Wire codec. */
        public static final StreamCodec<RegistryFriendlyByteBuf, PersonalRequest> CODEC = StreamCodec.of(
                (buffer, payload) -> {
                    buffer.writeEnum(payload.command());
                    buffer.writeUUID(payload.board());
                    buffer.writeUUID(payload.card());
                },
                buffer -> new PersonalRequest(buffer.readEnum(PersonalCommand.class), buffer.readUUID(),
                        buffer.readUUID()));

        /** Validates the request. */
        public PersonalRequest {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(board, "board");
            Objects.requireNonNull(card, "card");
        }

        @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> cast(
            CustomPacketPayload.Type<?> value) {
        return (CustomPacketPayload.Type<T>) value;
    }
}
