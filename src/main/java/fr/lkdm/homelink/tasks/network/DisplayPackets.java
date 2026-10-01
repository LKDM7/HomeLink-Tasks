package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.HomeLinkTasks;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Minimal private screen summaries, separate from GUI subscriptions and chunk NBT. */
public final class DisplayPackets {
    public static final int MAX_DISPLAYS = 8;
    public static final int MAX_CARDS = 12;
    private DisplayPackets() { }

    public record Card(String title, TaskStatus status, int completed, int target) { }

    public record View(UUID device, String title, int total, int done, List<Card> cards) {
        public View {
            cards = List.copyOf(cards);
            if (title.length() > TaskBoard.MAX_TITLE || cards.size() > MAX_CARDS) {
                throw new IllegalArgumentException("Oversized display view");
            }
        }

        public static View of(UUID device, TaskBoard board) {
            List<Card> cards = board.cards().stream().filter(card -> !card.archived())
                    .sorted(java.util.Comparator.comparing((fr.lkdm.homelink.tasks.task.TaskCard card) -> card.status())
                            .thenComparingInt(fr.lkdm.homelink.tasks.task.TaskCard::order)
                            .thenComparing(fr.lkdm.homelink.tasks.task.TaskCard::id))
                    .limit(MAX_CARDS).map(card -> new Card(card.title(), card.status(),
                            card.objective().map(objective -> objective.completedQuantity()).orElse(0),
                            card.objective().map(objective -> objective.targetQuantity()).orElse(0))).toList();
            int done = (int) board.cards().stream().filter(card -> !card.archived() && card.status() == TaskStatus.DONE).count();
            return new View(device, board.title(), board.activeCardCount(), done, cards);
        }

        private static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of((buffer, view) -> {
            buffer.writeUUID(view.device());
            buffer.writeUtf(view.title(), TaskBoard.MAX_TITLE);
            buffer.writeVarInt(view.total());
            buffer.writeVarInt(view.done());
            buffer.writeVarInt(view.cards().size());
            for (Card card : view.cards()) {
                buffer.writeUtf(card.title(), TaskBoard.MAX_TITLE);
                buffer.writeEnum(card.status());
                buffer.writeVarInt(card.completed());
                buffer.writeVarInt(card.target());
            }
        }, buffer -> {
            UUID device = buffer.readUUID();
            String title = buffer.readUtf(TaskBoard.MAX_TITLE);
            int total = buffer.readVarInt();
            int done = buffer.readVarInt();
            int count = ByteBufCodecs.readCount(buffer, MAX_CARDS);
            List<Card> cards = new ArrayList<>(count);
            for (int index = 0; index < count; index++) cards.add(new Card(buffer.readUtf(TaskBoard.MAX_TITLE),
                    buffer.readEnum(TaskStatus.class), buffer.readVarInt(), buffer.readVarInt()));
            return new View(device, title, total, done, cards);
        });
    }

    public record Request(List<BlockPos> positions) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(HomeLinkTasks.id("display_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((buffer, request) -> {
            buffer.writeVarInt(request.positions().size());
            request.positions().forEach(buffer::writeBlockPos);
        }, buffer -> {
            int count = ByteBufCodecs.readCount(buffer, MAX_DISPLAYS);
            List<BlockPos> positions = new ArrayList<>(count);
            for (int index = 0; index < count; index++) positions.add(buffer.readBlockPos());
            return new Request(positions);
        });
        public Request {
            positions = positions.stream().map(BlockPos::immutable).distinct().toList();
            if (positions.size() > MAX_DISPLAYS) throw new IllegalArgumentException("Too many displays");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public enum State { LIVE, LOADING, NO_PROJECT, PRIVATE, NETWORK_DENIED, NETWORK_MISMATCH, UNAVAILABLE }

    public record Snapshot(BlockPos pos, Optional<View> view, State state) implements CustomPacketPayload {
        public Snapshot(BlockPos pos, Optional<View> view) {
            this(pos, view, view.isPresent() ? State.LIVE : State.UNAVAILABLE);
        }
        public static final Type<Snapshot> TYPE = new Type<>(HomeLinkTasks.id("display_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((buffer, snapshot) -> {
            buffer.writeBlockPos(snapshot.pos());
            buffer.writeOptional(snapshot.view(), (target, value) -> View.CODEC.encode(buffer, value));
            buffer.writeEnum(snapshot.state());
        }, buffer -> new Snapshot(buffer.readBlockPos(), buffer.readOptional(source -> View.CODEC.decode(buffer)), buffer.readEnum(State.class)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
