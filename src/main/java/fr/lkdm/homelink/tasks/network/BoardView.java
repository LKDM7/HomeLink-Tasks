package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The copy of a board a client is allowed to draw.
 *
 * <p>Built only for a player who is a member. The Kanban board and the mind map read the
 * same cards from this one snapshot, so the two views can never drift apart.</p>
 *
 * @param id board identity
 * @param title board label
 * @param description board description
 * @param owner owning player
 * @param archived whether the board is archived
 * @param attached whether the board reads stock from a network
 * @param viewerRole the receiving player's own role
 * @param members member roles
 * @param cards the board's cards
 * @param revision board revision
 */
public record BoardView(UUID id, String title, String description, UUID owner, boolean archived,
                        boolean attached, BoardRole viewerRole, Map<UUID, BoardRole> members,
                        List<CardView> cards, long revision) {
    /** Validates and copies the collections. */
    public BoardView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(viewerRole, "viewerRole");
        title = Objects.requireNonNull(title, "title");
        description = Objects.requireNonNull(description, "description");
        members = Map.copyOf(Objects.requireNonNull(members, "members"));
        cards = List.copyOf(Objects.requireNonNull(cards, "cards"));
        if (title.length() > TaskBoard.MAX_TITLE || description.length() > TaskBoard.MAX_DESCRIPTION
                || members.size() > TaskBoard.MAX_MEMBERS || cards.size() > CardView.MAX_CARDS) {
            throw new IllegalArgumentException("Board view exceeds its bounds");
        }
    }

    /**
     * Builds the snapshot for one authorised viewer.
     *
     * @param board server-owned board
     * @param viewer player the snapshot is built for
     * @return wire copy, or empty when the player may not see the board
     */
    public static java.util.Optional<BoardView> of(TaskBoard board, UUID viewer) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(viewer, "viewer");
        if (!BoardPermissions.canView(board, viewer)) return java.util.Optional.empty();
        List<CardView> cards = new ArrayList<>();
        for (TaskCard card : board.cards()) {
            if (cards.size() >= CardView.MAX_CARDS) break;
            cards.add(CardView.of(card));
        }
        // Whether a network is attached is enough; its identity is not a board's business to publish.
        return java.util.Optional.of(new BoardView(board.id(), board.title(), board.description(), board.owner(),
                board.archived(), board.networkId().isPresent(), board.roleOf(viewer).orElseThrow(),
                board.members(), cards, board.revision()));
    }

    /** Wire codec for one board snapshot. */
    public static final StreamCodec<RegistryFriendlyByteBuf, BoardView> CODEC = StreamCodec.of(
            BoardView::encode, BoardView::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, BoardView view) {
        buffer.writeUUID(view.id());
        buffer.writeUtf(view.title(), TaskBoard.MAX_TITLE);
        buffer.writeUtf(view.description(), TaskBoard.MAX_DESCRIPTION);
        buffer.writeUUID(view.owner());
        buffer.writeBoolean(view.archived());
        buffer.writeBoolean(view.attached());
        buffer.writeEnum(view.viewerRole());
        buffer.writeVarInt(view.members().size());
        view.members().forEach((player, role) -> {
            buffer.writeUUID(player);
            buffer.writeEnum(role);
        });
        buffer.writeVarInt(view.cards().size());
        view.cards().forEach(card -> CardView.CODEC.encode(buffer, card));
        buffer.writeVarLong(view.revision());
    }

    private static BoardView decode(RegistryFriendlyByteBuf buffer) {
        UUID id = buffer.readUUID();
        String title = buffer.readUtf(TaskBoard.MAX_TITLE);
        String description = buffer.readUtf(TaskBoard.MAX_DESCRIPTION);
        UUID owner = buffer.readUUID();
        boolean archived = buffer.readBoolean();
        boolean attached = buffer.readBoolean();
        BoardRole viewerRole = buffer.readEnum(BoardRole.class);
        int memberCount = ByteBufCodecs.readCount(buffer, TaskBoard.MAX_MEMBERS);
        Map<UUID, BoardRole> members = new LinkedHashMap<>(memberCount);
        for (int index = 0; index < memberCount; index++) {
            members.put(buffer.readUUID(), buffer.readEnum(BoardRole.class));
        }
        int cardCount = ByteBufCodecs.readCount(buffer, CardView.MAX_CARDS);
        List<CardView> cards = new ArrayList<>(cardCount);
        for (int index = 0; index < cardCount; index++) cards.add(CardView.CODEC.decode(buffer));
        return new BoardView(id, title, description, owner, archived, attached, viewerRole, members, cards,
                buffer.readVarLong());
    }
}
