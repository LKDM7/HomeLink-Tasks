package fr.lkdm.homelink.tasks.stock;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.device.DashboardDevice;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homecore.api.stock.StockProvider;
import fr.lkdm.homecore.api.stock.StockRequest;
import fr.lkdm.homecore.api.stock.StockSnapshot;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Asks a board's own network what stock a player is allowed to see.
 *
 * <p>Every check happens before a provider is called: the player must be a member of
 * the board, the board must be attached to a network, the player must hold HomeCore's
 * {@link Permission#VIEW} on that exact network, and the device must still be a reachable
 * member of it. Being invited to a board grants none of this.</p>
 *
 * <p>One network only. A board attached to one network never quietly reads another, and
 * a query never enumerates the storages of the server.</p>
 *
 * <p>The query is read-only. It extracts nothing, reserves nothing, forces no chunk to
 * load and triggers no full rescan merely because a card is on screen.</p>
 */
public final class AuthorizedStockQuery {
    /** Largest number of providers one query consults. */
    public static final int MAX_PROVIDERS = 16;

    private AuthorizedStockQuery() { }

    /**
     * Observes the wanted variants for one viewing player.
     *
     * @param player authenticated player the answer is computed for
     * @param board board the card belongs to
     * @param wanted count-one prototypes the plan needs, at most {@link StockRequest#MAX_VARIANTS}
     * @return merged, deduplicated context; inventory-only when the board has no network
     */
    public static StockContext observe(ServerPlayer player, TaskBoard board, List<ItemStack> wanted) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(wanted, "wanted");
        long tick = player.serverLevel().getGameTime();
        // A board nobody may read answers nothing, not a masked answer on the client.
        if (!BoardPermissions.canView(board, player.getUUID())) return StockContext.inventoryOnly(tick);
        Optional<UUID> network = board.networkId();
        // No network configured here: the plan is honestly computed from the inventory alone.
        if (network.isEmpty() || wanted.isEmpty()) return StockContext.inventoryOnly(tick);
        UUID networkId = network.get();
        if (!DashboardAPI.hasPermission(player, networkId, Permission.VIEW)) {
            // Configured but not readable by this player: unknown, which is never zero.
            return new StockContext(List.of(), fr.lkdm.homecore.api.stock.StockAvailability.UNAVAILABLE,
                    fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY, true, tick);
        }

        var networks = DashboardAPI.networks(player.server);
        var devices = DashboardAPI.devices(player.server);
        var request = new StockRequest(networkId, player.getUUID(), wanted);
        List<StockSnapshot> snapshots = new ArrayList<>();
        if (networks.getNetwork(networkId).isPresent()) {
            for (UUID deviceId : networks.getDevices(networkId)) {
                if (snapshots.size() >= MAX_PROVIDERS) {
                    snapshots.add(StockSnapshot.unavailable(tick));
                    break;
                }
                DashboardDevice device = devices.get(deviceId).orElse(null);
                if (device == null) {
                    // HomeCore does not persist capability metadata for unloaded devices.
                    // If Storage exists, this device could be a provider: absence is unprovable.
                    if (net.neoforged.fml.ModList.get().isLoaded("homelink_storage")) snapshots.add(StockSnapshot.unavailable(tick));
                    continue;
                }
                Optional<StockProvider> provider = device.capability(StockProvider.CAPABILITY);
                if (provider.isEmpty()) continue;
                if (!networks.isReachable(networkId, device)) {
                    snapshots.add(StockSnapshot.unavailable(tick));
                    continue;
                }
                try {
                    snapshots.add(provider.get().observe(request));
                } catch (RuntimeException failure) {
                    // A provider that fails makes the scope unprovable rather than smaller.
                    snapshots.add(StockSnapshot.unavailable(tick));
                }
            }
        }
        return snapshots.isEmpty() ? StockContext.inventoryOnly(tick) : StockContext.merge(snapshots, true, tick);
    }
}
