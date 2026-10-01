package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.recipe.RecipeResolver;
import fr.lkdm.homelink.tasks.server.TaskManager;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Validates and applies what a client asks for.
 *
 * <p>The acting player is always the authenticated player of the connection, never an
 * identity taken from the payload. Membership, role, bounds, quantities, recipe
 * identifiers, scope, revision and request rate are all checked here before anything
 * changes.</p>
 */
public final class TaskRequestHandler {
    private TaskRequestHandler() { }

    /** Applies a board-level request.
     * @param request client request
     * @param context payload context of the authenticated connection
     */
    public static void onBoardRequest(TaskPackets.BoardRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            TaskSavedData data = TaskSavedData.get(player.server);
            TaskSubscriptions subscriptions = TaskSubscriptions.of(player.server);
            var accepted = TaskManager.get(player.server).requests().claim(player.getUUID(), request.operationId(),
                    player.server.overworld().getGameTime());
            if (accepted != fr.lkdm.homelink.tasks.server.RequestLedger.Result.ACCEPTED) {
                if (accepted == fr.lkdm.homelink.tasks.server.RequestLedger.Result.THROTTLED)
                    subscriptions.notice(player, TaskPackets.Notice.TOO_FAST, null);
                return;
            }
            if (request.command() == TaskPackets.BoardCommand.CLOSE) {
                subscriptions.forget(player.getUUID());
                return;
            }
            if (request.command() == TaskPackets.BoardCommand.CREATE) {
                createBoard(player, data, subscriptions, request);
                return;
            }
            if (request.command() == TaskPackets.BoardCommand.OPEN && request.board().isEmpty()) {
                subscriptions.openBoard(player, null, request.screen().orElse(null));
                return;
            }
            TaskBoard board = request.board().flatMap(data::board).orElse(null);
            if (board == null || !BoardPermissions.canView(board, player.getUUID())) {
                subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
                return;
            }
            if (request.command() == TaskPackets.BoardCommand.OPEN) {
                subscriptions.openBoard(player, board.id(), request.screen().orElse(null));
                return;
            }
            if (request.command() == TaskPackets.BoardCommand.SELECT_DISPLAY) {
                var screen = request.screen().map(pos -> fr.lkdm.homelink.tasks.server.TaskAccess.nearbyScreen(player, pos, 64)).orElse(null);
                if (screen == null || !new fr.lkdm.homelink.tasks.block.TaskDisplayDevice(screen).canConfigure(player)
                        || !fr.lkdm.homelink.tasks.server.TaskAccess.canView(player, board, screen)) {
                    denied(player, subscriptions);
                    return;
                }
                screen.setSelectedBoard(board.id());
                subscriptions.openBoard(player, board.id(), screen.getBlockPos());
                return;
            }
            // Editing what somebody else already changed is a conflict, not a silent overwrite.
            if (request.expectedRevision() != board.revision()) {
                subscriptions.notice(player, TaskPackets.Notice.CONFLICT, null);
                subscriptions.refresh(player);
                return;
            }
            try {
                if (!applyBoard(player, data, subscriptions, board, request)) return;
            } catch (IllegalArgumentException | IllegalStateException malformed) {
                denied(player, subscriptions);
                return;
            }
            data.setDirty();
            subscriptions.broadcast(board);
        });
    }

    private static void createBoard(ServerPlayer player, TaskSavedData data, TaskSubscriptions subscriptions,
                                    TaskPackets.BoardRequest request) {
        String title = request.text().strip();
        if (title.isEmpty() || title.length() > TaskBoard.MAX_TITLE) {
            subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
            return;
        }
        try {
            var physical = request.screen().map(pos -> fr.lkdm.homelink.tasks.server.TaskAccess.nearbyScreen(player, pos, 64)).orElse(null);
            if (request.screen().isPresent() && (physical == null || !fr.lkdm.homelink.tasks.server.TaskAccess.canUseScreen(player, physical))) {
                subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
                return;
            }
            TaskBoard created = new TaskBoard(UUID.randomUUID(), title, player.getUUID(),
                    player.serverLevel().getGameTime());
            data.addBoard(created);
            subscriptions.openBoard(player, created.id(), request.screen().orElse(null));
        } catch (IllegalStateException limit) {
            subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, null);
        }
    }

    private static boolean applyBoard(ServerPlayer player, TaskSavedData data, TaskSubscriptions subscriptions,
                                      TaskBoard board, TaskPackets.BoardRequest request) {
        boolean owner = BoardPermissions.canManageBoard(board, player.getUUID());
        boolean editor = BoardPermissions.canEditCards(board, player.getUUID());
        switch (request.command()) {
            case RENAME -> {
                if (!editor) return denied(player, subscriptions);
                board.setTitle(request.text());
            }
            case DESCRIBE -> {
                if (!editor) return denied(player, subscriptions);
                board.setDescription(request.text());
            }
            case ARCHIVE -> {
                if (!editor) return denied(player, subscriptions);
                board.setArchived(request.flag());
            }
            case DELETE -> {
                if (!owner) return denied(player, subscriptions);
                data.removeBoard(board.id());
                TaskManager.get(player.server).index().rebuild(data.boards());
                subscriptions.broadcast(board);
                subscriptions.openBoard(player, null, null);
                return false;
            }
            case SET_MEMBER -> {
                UUID member = request.target().orElse(null);
                if (!owner || member == null || request.role() == BoardRole.OWNER
                        || !data.knownPlayers().containsKey(member) && board.roleOf(member).isEmpty()
                        && player.server.getPlayerList().getPlayer(member) == null) return denied(player, subscriptions);
                try {
                    board.setMember(member, request.role());
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, null);
                    return false;
                }
            }
            case REMOVE_MEMBER -> {
                UUID member = request.target().orElse(null);
                if (!owner || member == null) return denied(player, subscriptions);
                if (board.removeMember(member)) {
                    // Revoking access stops the data, rather than hiding it on their client.
                    data.purgeInaccessible(member);
                    subscriptions.forget(member);
                    ServerPlayer removed = player.server.getPlayerList().getPlayer(member);
                    if (removed != null) {
                        subscriptions.revoke(removed);
                    }
                }
            }
            case TRANSFER_OWNERSHIP -> {
                if (!owner) return denied(player, subscriptions);
                UUID target = request.target().orElse(null);
                if (target == null || board.roleOf(target).isEmpty()
                        || data.boardsFor(target).stream().filter(other -> other.owner().equals(target)).count()
                        >= fr.lkdm.homelink.tasks.server.TaskSavedData.MAX_BOARDS_PER_OWNER) return denied(player, subscriptions);
                board.transferOwnership(target);
            }
            case ATTACH_NETWORK -> {
                if (!attachNetwork(player, subscriptions, board, request)) return false;
            }
            default -> {
                return denied(player, subscriptions);
            }
        }
        return true;
    }

    /**
     * Attaches a board to the network of the screen being used, or detaches it.
     *
     * <p>Attaching needs the board's own ownership and HomeCore's MANAGE_NETWORK on that
     * exact network. A board invitation never creates a network permission.</p>
     */
    private static boolean attachNetwork(ServerPlayer player, TaskSubscriptions subscriptions, TaskBoard board,
                                         TaskPackets.BoardRequest request) {
        if (!BoardPermissions.canManageBoard(board, player.getUUID())) return denied(player, subscriptions);
        if (!request.flag()) {
            board.setNetworkId(null);
            return true;
        }
        UUID network = request.screen()
                .map(pos -> fr.lkdm.homelink.tasks.server.TaskAccess.nearbyScreen(player, pos, 64))
                .filter(screen -> new fr.lkdm.homelink.tasks.block.TaskDisplayDevice(screen).canConfigure(player))
                .flatMap(TaskDisplayBlockEntity::networkId)
                .orElse(null);
        if (network == null || !DashboardAPI.hasPermission(player, network, Permission.MANAGE_NETWORK)) {
            return denied(player, subscriptions);
        }
        board.setNetworkId(network);
        return true;
    }

    /** Applies a card-level request.
     * @param request client request
     * @param context payload context of the authenticated connection
     */
    public static void onCardRequest(TaskPackets.CardRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) CardRequestHandler.apply(player, request);
        });
    }

    /** Applies a personal request that affects only the asking player.
     * @param request client request
     * @param context payload context of the authenticated connection
     */
    public static void onPersonalRequest(TaskPackets.PersonalRequest request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            TaskManager manager = TaskManager.get(player.server);
            if (manager.requests().claim(player.getUUID(), UUID.randomUUID(), player.server.overworld().getGameTime())
                    != fr.lkdm.homelink.tasks.server.RequestLedger.Result.ACCEPTED) return;
            TaskSavedData data = manager.data();
            TaskSubscriptions subscriptions = TaskSubscriptions.of(player.server);
            TaskBoard board = data.board(request.board()).orElse(null);
            TaskCard card = board == null ? null : board.card(request.card()).orElse(null);
            if (board == null || card == null || !BoardPermissions.canView(board, player.getUUID())) {
                subscriptions.notice(player, TaskPackets.Notice.DENIED, request.card());
                return;
            }
            switch (request.command()) {
                case PIN -> {
                    if (!data.setPinned(player.getUUID(), card.id(), true,
                            fr.lkdm.homelink.tasks.server.TaskServerConfig.PIN_LIMIT.get())) {
                        subscriptions.notice(player, TaskPackets.Notice.LIMIT_REACHED, card.id());
                        return;
                    }
                    subscriptions.sendPinned(player);
                }
                case UNPIN -> {
                    data.setPinned(player.getUUID(), card.id(), false, TaskSavedData.MAX_PINS);
                    subscriptions.sendPinned(player);
                }
                // Tracking is a separate, deliberate choice; pinning never implies it.
                case TRACK -> { data.setTrackedCard(player.getUUID(), card.id()); subscriptions.sendPinned(player); }
                case UNTRACK -> { data.setTrackedCard(player.getUUID(), null); subscriptions.sendPinned(player); }
                case REQUEST_PLAN -> sendPlan(player, manager, subscriptions, board, card);
            }
        });
    }

    private static void sendPlan(ServerPlayer player, TaskManager manager, TaskSubscriptions subscriptions,
                                 TaskBoard board, TaskCard card) {
        long tick = player.serverLevel().getGameTime();
        if (!manager.budget().claim(player.getUUID(), tick)) {
            subscriptions.notice(player, TaskPackets.Notice.TOO_FAST, card.id());
            return;
        }
        CraftObjective objective = card.objective().orElse(null);
        Optional<RecipeDescriptor> descriptor = objective == null ? Optional.empty()
                : RecipeResolver.describe(player.server, objective.recipeId());
        int operations = objective == null ? 0
                : descriptor.map(described -> described.operationsFor(objective.remaining())).orElse(0);
        var plan = manager.plan(player, board, card);
        fr.lkdm.homelink.tasks.network.TaskTransport.send(player,
                new TaskPackets.CardPlan(PlanView.of(card.id(), plan, descriptor, operations)));
    }

    private static boolean denied(ServerPlayer player, TaskSubscriptions subscriptions) {
        subscriptions.notice(player, TaskPackets.Notice.DENIED, null);
        return false;
    }
}
