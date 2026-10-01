package fr.lkdm.homelink.tasks.server;

import fr.lkdm.homelink.tasks.network.DisplayPackets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Bounded polling of already loaded nearby screens, with no world or chest scan. */
public final class DisplayQueries {
    private final Map<UUID, Long> lastPoll = new HashMap<>();
    private final Map<UUID, Watch> watches = new HashMap<>();
    private record Identity(UUID device, UUID board) { }
    private record Watch(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                         long expires, Map<net.minecraft.core.BlockPos, Identity> displays) { }

    public static void handle(DisplayPackets.Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            TaskManager manager = TaskManager.get(player.server);
            long tick = player.server.overworld().getGameTime();
            if (!manager.displayQueries().claim(player.getUUID(), tick)) return;
            Map<net.minecraft.core.BlockPos, Identity> granted = new HashMap<>();
            for (var pos : request.positions()) {
                var screen = TaskAccess.nearbyScreen(player, pos, TaskAccess.DISPLAY_DISTANCE_SQUARED);
                var board = screen == null ? null : screen.selectedBoard().flatMap(manager.data()::board).orElse(null);
                var snapshot = describe(player, pos);
                var view = snapshot.view();
                fr.lkdm.homelink.tasks.network.TaskTransport.send(player, snapshot);
                if (view.isPresent()) granted.put(pos, new Identity(screen.id(), board.id()));
            }
            manager.displayQueries().watches.put(player.getUUID(), new Watch(player.level().dimension(), tick + 25, granted));
        });
    }

    public static DisplayPackets.Snapshot describe(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        var screen = TaskAccess.nearbyScreen(player, pos, TaskAccess.DISPLAY_DISTANCE_SQUARED);
        DisplayPackets.State state;
        if (screen == null) state = DisplayPackets.State.UNAVAILABLE;
        else if (!TaskAccess.canUseScreen(player, screen)) state = DisplayPackets.State.NETWORK_DENIED;
        else {
            var board = screen.selectedBoard().flatMap(TaskSavedData.get(player.server)::board).orElse(null);
            if (board == null) state = DisplayPackets.State.NO_PROJECT;
            else if (!fr.lkdm.homelink.tasks.board.BoardPermissions.canView(board, player.getUUID())) state = DisplayPackets.State.PRIVATE;
            else if (!TaskAccess.canView(player, board, screen)) state = DisplayPackets.State.NETWORK_MISMATCH;
            else return new DisplayPackets.Snapshot(pos, Optional.of(DisplayPackets.View.of(screen.id(), board)), DisplayPackets.State.LIVE);
        }
        return new DisplayPackets.Snapshot(pos, Optional.empty(), state);
    }

    public boolean claim(UUID player, long tick) {
        Long last = lastPoll.get(player);
        if (last != null && tick - last < 20) return false;
        lastPoll.put(player, tick);
        return true;
    }

    public void forget(UUID player) { lastPoll.remove(player); watches.remove(player); }

    /** Invalidates cached content on the first server tick that observes a revocation. */
    public void tick(TaskManager manager) {
        long tick = manager.server().overworld().getGameTime();
        var iterator = watches.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var player = manager.server().getPlayerList().getPlayer(entry.getKey());
            var watch = entry.getValue();
            if (player == null || tick > watch.expires()) { iterator.remove(); continue; }
            watch.displays().entrySet().removeIf(display -> {
                var screen = watch.dimension().equals(player.level().dimension())
                        ? TaskAccess.nearbyScreen(player, display.getKey(), TaskAccess.DISPLAY_DISTANCE_SQUARED) : null;
                var board = manager.data().board(display.getValue().board()).orElse(null);
                boolean valid = screen != null && screen.id().equals(display.getValue().device())
                        && screen.selectedBoard().filter(display.getValue().board()::equals).isPresent()
                        && board != null && TaskAccess.canView(player, board, screen);
                if (!valid) fr.lkdm.homelink.tasks.network.TaskTransport.send(player,
                        new DisplayPackets.Snapshot(display.getKey(), Optional.empty(), DisplayPackets.State.LOADING));
                return !valid;
            });
            if (watch.displays().isEmpty()) iterator.remove();
        }
    }
}
