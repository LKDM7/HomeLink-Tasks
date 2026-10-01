package fr.lkdm.homelink.tasks.block;

import fr.lkdm.homecore.api.action.ActionResult;
import fr.lkdm.homecore.api.device.DashboardDevice;
import fr.lkdm.homecore.api.device.DeviceStatus;
import fr.lkdm.homecore.api.device.Renamable;
import fr.lkdm.homecore.api.network.HomeNetwork;
import fr.lkdm.homecore.api.network.NetworkMember;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.tasks.HomeLinkTasks;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Adapter exposing a screen to HomeCore.
 *
 * <p>Implementing {@link NetworkMember} is what lets the existing HomeLink Connector
 * attach this screen to a network; this mod adds no binding tool of its own.</p>
 *
 * <p>The device publishes no board content. What a screen shows is private to the
 * players who may read that board, so nothing here leaks a project to observers.</p>
 */
public final class TaskDisplayDevice implements DashboardDevice, NetworkMember, Renamable {
    private final TaskDisplayBlockEntity screen;

    /** Adapts one screen.
     * @param screen backing block entity
     */
    public TaskDisplayDevice(TaskDisplayBlockEntity screen) { this.screen = screen; }

    @Override public UUID id() { return screen.id(); }

    @Override public ResourceLocation deviceType() { return HomeLinkTasks.id("task_display"); }

    @Override public Component displayName() { return screen.displayName(); }

    @Override public DeviceStatus status() {
        if (!isValid()) return DeviceStatus.OFFLINE;
        if (screen.selectedBoard().isEmpty()) {
            return DeviceStatus.WARNING.withMessage(Component.translatable("status.homelink_tasks.no_board"));
        }
        return DeviceStatus.ONLINE;
    }

    @Override public Optional<BlockPos> position() { return Optional.of(screen.getBlockPos().immutable()); }

    @Override public Optional<ResourceKey<Level>> dimension() {
        return screen.getLevel() == null ? Optional.empty() : Optional.of(screen.getLevel().dimension());
    }

    @Override public boolean isValid() {
        return !screen.isRemoved() && screen.getLevel() instanceof ServerLevel level
                && level.isLoaded(screen.getBlockPos()) && level.getBlockEntity(screen.getBlockPos()) == screen;
    }

    @Override public Optional<UUID> homeNetwork() { return screen.networkId(); }

    @Override public Optional<UUID> owner() { return screen.owner(); }

    @Override public boolean canConfigure(ServerPlayer player) {
        return isValid() && (player.hasPermissions(2) || screen.owner().filter(player.getUUID()::equals).isPresent()
                || screen.networkId().map(network ->
                        fr.lkdm.homecore.api.DashboardAPI.hasPermission(player, network, Permission.CONFIGURE))
                .orElse(false));
    }

    @Override public void homeNetworkChanged(Optional<HomeNetwork> network) {
        screen.setNetworkId(network.map(HomeNetwork::id).orElse(null));
    }

    @Override public ActionResult rename(String name) {
        if (!isValid()) return ActionResult.of(ActionResult.Code.DEVICE_OFFLINE);
        screen.setCustomName(name);
        return ActionResult.success();
    }
}
