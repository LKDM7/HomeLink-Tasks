package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.server.TaskManager;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.stock.AvailabilityState;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Who is currently looking at which board, and what they are sent.
 *
 * <p>A player receives a board only while they are entitled to it. When access is
 * revoked the subscription is dropped and the data stops arriving, rather than being
 * delivered and hidden by the client.</p>
 *
 * <p>This mod keeps its own channel and its own subscriptions. It never replaces or
 * competes with HomeCore's dashboard subscription.</p>
 */
public final class TaskSubscriptions {
    private static final Map<MinecraftServer, TaskSubscriptions> INSTANCES = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final Map<UUID, UUID> openBoards = new java.util.HashMap<>();
    private final Map<UUID, ScreenContext> screenContexts = new java.util.HashMap<>();
    private record ScreenContext(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos pos) { }

    private TaskSubscriptions(MinecraftServer server) { this.server = server; }

    /** Returns the subscriptions of a running server.
     * @param server running server
     * @return server-scoped subscriptions
     */
    public static synchronized TaskSubscriptions of(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return INSTANCES.computeIfAbsent(server, TaskSubscriptions::new);
    }

    /** Drops a player's subscription, for example when they disconnect.
     * @param player player identity
     */
    public void forget(UUID player) { openBoards.remove(Objects.requireNonNull(player, "player")); }
    public void logout(UUID player) { forget(player); screenContexts.remove(player); }
    public void revoke(ServerPlayer player) {
        logout(player.getUUID());
        fr.lkdm.homelink.tasks.network.TaskTransport.send(player, new TaskPackets.BoardSnapshot(Optional.empty(),
                summaries(player, TaskSavedData.get(server)), Optional.empty(), false));
        sendPinned(player);
    }

    public fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity stockScreen(ServerPlayer player) {
        ScreenContext context = screenContexts.get(player.getUUID());
        if (context == null || !context.dimension().equals(player.level().dimension())) return null;
        return fr.lkdm.homelink.tasks.server.TaskAccess.nearbyScreen(player, context.pos(), 64);
    }

    /** Releases every subscription when the server stops.
     * @param server stopping server
     */
    public static synchronized void stop(MinecraftServer server) { INSTANCES.remove(server); }

    /**
     * Opens a board for a player, or the board list when they may not see it.
     *
     * @param player authenticated player
     * @param board board to open, or null for the list only
     * @param screen screen the request came from, when there was one
     */
    public void openBoard(ServerPlayer player, UUID board, BlockPos screen) {
        Objects.requireNonNull(player, "player");
        TaskSavedData data = TaskSavedData.get(server);
        var physical = screen == null ? null : fr.lkdm.homelink.tasks.server.TaskAccess.nearbyScreen(player, screen, 64);
        if (screen != null && (physical == null || !fr.lkdm.homelink.tasks.server.TaskAccess.canUseScreen(player, physical))) {
            notice(player, TaskPackets.Notice.DENIED, null);
            return;
        }
        if (physical == null) screenContexts.remove(player.getUUID());
        else screenContexts.put(player.getUUID(), new ScreenContext(player.level().dimension(), screen.immutable()));
        Optional<TaskBoard> target = Optional.ofNullable(board).flatMap(data::board)
                .filter(candidate -> fr.lkdm.homelink.tasks.server.TaskAccess.canView(player, candidate, physical));
        if (target.isPresent()) openBoards.put(player.getUUID(), target.get().id());
        else openBoards.remove(player.getUUID());
        if (physical != null && target.isPresent()
                && new fr.lkdm.homelink.tasks.block.TaskDisplayDevice(physical).canConfigure(player)) {
            physical.setSelectedBoard(target.get().id());
        }
        fr.lkdm.homelink.tasks.network.TaskTransport.send(player, new TaskPackets.BoardSnapshot(
                target.flatMap(candidate -> BoardView.of(candidate, player.getUUID())),
                summaries(player, data), Optional.ofNullable(screen)));
        sendPinned(player);
    }

    /** Sends a player the board they have open again, after a change.
     * @param player authenticated player
     */
    public void refresh(ServerPlayer player) {
        UUID open = openBoards.get(player.getUUID());
        TaskSavedData data = TaskSavedData.get(server);
        TaskBoard board = open == null ? null : data.board(open).orElse(null);
        var physical = stockScreen(player);
        boolean valid = board != null && BoardPermissions.canView(board, player.getUUID())
                && (!screenContexts.containsKey(player.getUUID()) || physical != null
                    && fr.lkdm.homelink.tasks.server.TaskAccess.canView(player, board, physical));
        if (!valid) openBoards.remove(player.getUUID());
        fr.lkdm.homelink.tasks.network.TaskTransport.send(player, new TaskPackets.BoardSnapshot(
                valid ? BoardView.of(board, player.getUUID()) : Optional.empty(), summaries(player, data),
                physical == null ? Optional.empty() : Optional.of(physical.getBlockPos()), false));
    }

    /**
     * Sends everyone watching a board its new state.
     *
     * <p>Each viewer gets their own snapshot, so a player who lost access simply stops
     * receiving one.</p>
     *
     * @param board board that changed
     */
    public void broadcast(TaskBoard board) {
        Objects.requireNonNull(board, "board");
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (board.id().equals(openBoards.get(player.getUUID()))) refresh(player);
            if (!TaskSavedData.get(server).pins(player.getUUID()).isEmpty()) sendPinned(player);
        }
    }

    /** Rechecks permissions and personal inventory even while the GUI is closed. */
    public void tick() {
        TaskSavedData data = TaskSavedData.get(server);
        int playerIndex = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean refreshPins = Math.floorMod(playerIndex++, 20) == Math.floorMod(server.getTickCount(), 20);
            UUID id = player.getUUID();
            var contextScreen = stockScreen(player);
            if (screenContexts.containsKey(id) && (contextScreen == null
                    || !fr.lkdm.homelink.tasks.server.TaskAccess.canUseScreen(player, contextScreen))) {
                refresh(player);
                screenContexts.remove(id);
                sendPinned(player);
            }
            if (openBoards.containsKey(id)) {
                var board = data.board(openBoards.get(id)).orElse(null);
                var screen = stockScreen(player);
                if (board == null || !BoardPermissions.canView(board, id)
                        || screenContexts.containsKey(id) && (screen == null
                        || !fr.lkdm.homelink.tasks.server.TaskAccess.canView(player, board, screen))) refresh(player);
            }
            if (!data.pins(id).isEmpty() && refreshPins) {
                data.purgeInaccessible(id);
                sendPinned(player);
            }
        }
    }

    /**
     * Sends a player their own pinned cards.
     *
     * <p>Pins are personal: nothing here is sent to anybody else, and a card the player
     * may no longer see is dropped rather than sent and hidden.</p>
     *
     * @param player authenticated player
     */
    public void sendPinned(ServerPlayer player) {
        TaskSavedData data = TaskSavedData.get(server);
        List<TaskPackets.PinnedView> pinned = new ArrayList<>();
        for (UUID cardId : data.pins(player.getUUID())) {
            if (pinned.size() >= TaskPackets.MAX_PINNED) break;
            for (TaskBoard board : data.boards()) {
                if (!BoardPermissions.canView(board, player.getUUID())) continue;
                TaskCard card = board.card(cardId).orElse(null);
                if (card == null) continue;
                CraftObjective objective = card.objective().orElse(null);
                boolean missingObjective = objective == null && card.type() == fr.lkdm.homelink.tasks.task.TaskType.CRAFT;
                var plan = objective != null && !objective.complete() ? TaskManager.get(server).plan(player, board, card) : null;
                AvailabilityState availability = missingObjective ? AvailabilityState.UNVERIFIED : objective == null || objective.complete()
                        ? AvailabilityState.READY
                        : plan.state();
                pinned.add(new TaskPackets.PinnedView(card.id(), board.id(), card.title(), card.status(),
                        objective == null ? ItemStack.EMPTY : objective.target(),
                        objective == null ? card.unresolvedObjective().map(tag -> tag.getInt("Completed")).orElse(0) : objective.completedQuantity(),
                        objective == null ? card.unresolvedObjective().map(tag -> tag.getInt("TargetQuantity")).orElse(0) : objective.targetQuantity(), availability,
                        plan != null && plan.storageConfigured()));
                break;
            }
        }
        fr.lkdm.homelink.tasks.network.TaskTransport.send(player, new TaskPackets.PinnedTasks(pinned, data.trackedCard(player.getUUID())));
    }

    /** Tells a player why their request was refused, so an optimistic drawing is corrected.
     * @param player authenticated player
     * @param notice short outcome
     * @param card card concerned, or null
     */
    public void notice(ServerPlayer player, TaskPackets.Notice notice, UUID card) {
        fr.lkdm.homelink.tasks.network.TaskTransport.send(player, new TaskPackets.NoticeMessage(notice, Optional.ofNullable(card)));
    }

    /** Returns the board a player currently has open.
     * @param player player identity
     * @return board identity, or empty
     */
    public Optional<UUID> openBoard(UUID player) {
        return Optional.ofNullable(openBoards.get(Objects.requireNonNull(player, "player")));
    }

    private List<TaskPackets.BoardSummary> summaries(ServerPlayer player, TaskSavedData data) {
        List<TaskPackets.BoardSummary> summaries = new ArrayList<>();
        var physical = stockScreen(player);
        if (screenContexts.containsKey(player.getUUID()) && physical == null) return summaries;
        for (TaskBoard board : data.boardsFor(player.getUUID())) {
            if (!fr.lkdm.homelink.tasks.server.TaskAccess.canView(player, board, physical)) continue;
            if (summaries.size() >= TaskPackets.MAX_BOARDS) break;
            summaries.add(TaskPackets.BoardSummary.of(board, player.getUUID()));
        }
        return summaries;
    }
}
