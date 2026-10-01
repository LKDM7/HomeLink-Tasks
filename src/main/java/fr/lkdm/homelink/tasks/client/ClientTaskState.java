package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.network.BoardView;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.PlanView;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The copy of the server's answer this client is currently drawing.
 *
 * <p>Nothing here decides anything. A refusal replaces what an optimistic drag drew, and
 * the board that arrives is always the one the server was willing to send.</p>
 */
public final class ClientTaskState {
    private static final Map<UUID, PlanView> PLANS = new LinkedHashMap<>();
    private static BoardView board;
    private static List<TaskPackets.BoardSummary> boards = List.of();
    private static List<TaskPackets.PinnedView> pinned = List.of();
    private static UUID tracked;
    private static final Map<UUID, Long> PLAN_RECEIVED = new LinkedHashMap<>();
    private static long clientTick, pinnedReceived = Long.MIN_VALUE / 2;
    private static long lastPlanRequest = Long.MIN_VALUE / 2;
    public static boolean claimPlanRequest() {
        if (clientTick - lastPlanRequest < 13) return false;
        lastPlanRequest = clientTick;
        return true;
    }
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    public static void tick() {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        if (!level.dimension().equals(dimension)) {
            PLANS.clear(); PLAN_RECEIVED.clear(); screen = null; pinnedReceived = Long.MIN_VALUE / 2;
            dimension = level.dimension();
        }
        clientTick++;
    }
    public static boolean tracking(UUID card) { return card.equals(tracked); }
    private static BlockPos screen;
    private static java.util.List<fr.lkdm.homelink.tasks.network.MemberPackets.Player> members = java.util.List.of();
    public static java.util.List<fr.lkdm.homelink.tasks.network.MemberPackets.Player> members() { return members; }
    public static String playerName(UUID id) {
        return members.stream().filter(player -> player.id().equals(id)).map(player -> player.name()).findFirst()
                .orElse(id.toString().substring(0, 8));
    }
    public static void onMembers(fr.lkdm.homelink.tasks.network.MemberPackets.Directory payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (board().filter(board -> board.id().equals(payload.board())).isEmpty()) return;
            members = payload.players();
            if (Minecraft.getInstance().screen instanceof TaskScreen tasks) tasks.dataChanged();
        });
    }

    private ClientTaskState() { }

    /** Handles a board snapshot.
     * @param payload received payload
     * @param context payload context
     */
    public static void onBoardSnapshot(TaskPackets.BoardSnapshot payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            boolean differentBoard = board == null || payload.board().isEmpty()
                    || !board.id().equals(payload.board().get().id());
            board = payload.board().orElse(null);
            boards = payload.available();
            screen = payload.screen().orElse(null);
            // A plan belongs to the board it was computed for.
            PLANS.clear();
            PLAN_RECEIVED.clear();
            if (differentBoard) members = java.util.List.of();
            Minecraft client = Minecraft.getInstance();
            if (client.screen instanceof TaskScreen tasks && tasks.readOnly()) {
                tasks.dataChanged();
                return;
            }
            if (!payload.navigate()) {
                if (board == null && client.screen instanceof TaskScreen) client.setScreen(new BoardListScreen());
                else if (client.screen instanceof TaskScreen tasks) tasks.dataChanged();
                return;
            }
            if (board != null) {
                BoardScreen.openOrRefresh();
                return;
            }
            // No board to show: offer the projects this player may open, rather than nothing.
            if (!(client.screen instanceof BoardListScreen)) client.setScreen(new BoardListScreen());
        });
    }

    /** Handles a fresh availability plan.
     * @param payload received payload
     * @param context payload context
     */
    public static void onCardPlan(TaskPackets.CardPlan payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (card(payload.plan().card()).isPresent()) {
                PLANS.put(payload.plan().card(), payload.plan());
                PLAN_RECEIVED.put(payload.plan().card(), clientTick);
            }
        });
    }

    /** Handles the player's own pinned cards.
     * @param payload received payload
     * @param context payload context
     */
    public static void onPinnedTasks(TaskPackets.PinnedTasks payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            pinned = payload.pinned();
            pinnedReceived = clientTick;
            tracked = payload.tracked().orElse(null);
            if (Minecraft.getInstance().screen instanceof CardDetailScreen detail) detail.dataChanged();
        });
    }

    /** Handles a refusal or conflict notice.
     * @param payload received payload
     * @param context payload context
     */
    public static void onNotice(TaskPackets.NoticeMessage payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player == null) return;
            TaskScreen.notice(Component.translatable("message.homelink_tasks.notice." + payload.notice().name().toLowerCase(java.util.Locale.ROOT)));
            if (client.screen instanceof TaskScreen tasks) tasks.requestFailed();
            client.player.displayClientMessage(Component.translatable(
                    "message.homelink_tasks.notice." + payload.notice().name().toLowerCase(java.util.Locale.ROOT)),
                    true);
        });
    }

    /** Returns the board currently open, when there is one.
     * @return board snapshot, or empty
     */
    public static Optional<BoardView> board() { return Optional.ofNullable(board); }

    /** A remote consultation waits for a fresh authorized snapshot. */
    public static void beginConsultation() {
        board = null; screen = null; PLANS.clear(); PLAN_RECEIVED.clear();
    }

    /** Returns the boards this player may open.
     * @return board summaries
     */
    public static List<TaskPackets.BoardSummary> boards() { return boards; }

    /** Returns this player's own pinned cards.
     * @return pinned cards, in pin order
     */
    public static List<TaskPackets.PinnedView> pinned() {
        if (clientTick - pinnedReceived <= 25) return pinned;
        return pinned.stream().map(view -> new TaskPackets.PinnedView(view.card(), view.board(), view.title(), view.status(),
                view.target(), view.completed(), view.wanted(), fr.lkdm.homelink.tasks.stock.AvailabilityState.UNVERIFIED,
                view.storageConfigured())).toList();
    }

    /** Returns the screen the board was opened from, when there was one.
     * @return screen position, or empty
     */
    public static Optional<BlockPos> screen() { return Optional.ofNullable(screen); }

    /** Returns the last plan received for a card.
     * @param card card identity
     * @return plan, or empty while none has arrived
     */
    public static Optional<PlanView> plan(UUID card) {
        return clientTick - PLAN_RECEIVED.getOrDefault(card, Long.MIN_VALUE / 2) > 25
                ? Optional.empty() : Optional.ofNullable(PLANS.get(card));
    }

    /** Looks a card up in the open board.
     * @param card card identity
     * @return card view, or empty
     */
    public static Optional<CardView> card(UUID card) {
        return board().flatMap(open -> open.cards().stream().filter(view -> view.id().equals(card)).findFirst());
    }

    /** Forgets everything, for example on disconnect. */
    public static void clear() {
        board = null;
        boards = List.of();
        pinned = List.of();
        tracked = null;
        screen = null;
        members = java.util.List.of();
        PLANS.clear();
        PLAN_RECEIVED.clear(); clientTick = 0; pinnedReceived = Long.MIN_VALUE / 2;
        lastPlanRequest = Long.MIN_VALUE / 2; dimension = null;
    }
}
