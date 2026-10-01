package fr.lkdm.homelink.tasks.server;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** Screen access adds HomeCore checks to the board's independent membership. */
public final class TaskAccess {
    public static final double DISPLAY_DISTANCE_SQUARED = 16 * 16;
    private TaskAccess() { }

    public static TaskDisplayBlockEntity nearbyScreen(ServerPlayer player, BlockPos pos, double distanceSquared) {
        if (!player.serverLevel().isLoaded(pos) || player.distanceToSqr(pos.getCenter()) > distanceSquared) return null;
        var state = player.serverLevel().getBlockState(pos);
        if (state.getBlock() instanceof fr.lkdm.homelink.tasks.block.TaskDisplayBlock)
            pos = fr.lkdm.homelink.tasks.block.TaskDisplayBlock.masterPos(state, pos);
        if (!player.serverLevel().isLoaded(pos)) return null;
        return player.serverLevel().getBlockEntity(pos) instanceof TaskDisplayBlockEntity screen ? screen : null;
    }

    public static boolean canUseScreen(ServerPlayer player, TaskDisplayBlockEntity screen) {
        return screen.getLevel() == player.serverLevel() && !screen.isRemoved()
                && screen.networkId().map(network -> DashboardAPI.hasPermission(player, network, Permission.VIEW))
                .orElse(true);
    }

    public static boolean canView(ServerPlayer player, TaskBoard board, TaskDisplayBlockEntity screen) {
        if (!BoardPermissions.canView(board, player.getUUID())) return false;
        if (screen == null) return true; // Personal board access is independent of network access.
        return canUseScreen(player, screen) && (board.networkId().isEmpty() || board.networkId().equals(screen.networkId()));
    }
}
