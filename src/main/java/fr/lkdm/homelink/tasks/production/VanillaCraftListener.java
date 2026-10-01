package fr.lkdm.homelink.tasks.production;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.production.ProductionLog;
import fr.lkdm.homecore.api.production.ProductionReceipt;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Turns a finished crafting table result into a production receipt.
 *
 * <p>The game fires its craft event at the moment the result is actually taken, which is
 * the only moment items really exist. Previewing a result in the grid fires nothing, so a
 * preview never counts.</p>
 *
 * <p>The quantity is whatever the event says was crafted, taken as it comes. Minecraft
 * already aggregates a shift-click into the callbacks it makes, so the count is used once
 * and never multiplied by a recipe yield a second time. A callback is not assumed to mean
 * one item.</p>
 *
 * <p>Nothing else here produces a receipt: moving items between inventories, taking them
 * out of storage, trading, picking them up, {@code /give} and the creative menu all leave
 * this listener untouched.</p>
 */
public final class VanillaCraftListener {
    /** Producer identity carried by every receipt this listener issues. */
    public static final ResourceLocation SOURCE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "crafting_table");

    private VanillaCraftListener() { }

    /**
     * Publishes a receipt for a result the player has just taken.
     *
     * @param event craft event fired by the game on the server
     */
    public static void onCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ItemStack crafted = event.getCrafting();
        if (crafted.isEmpty()) return;
        ProductionLog log = DashboardAPI.production(player.server);
        long tick = player.serverLevel().getGameTime();
        var start = log.startStamp();
        // A table craft is instantaneous: it starts and finishes in the same tick.
        log.publish(new ProductionReceipt(UUID.randomUUID(), player.getUUID(), SOURCE,
                recipeUsed(player), crafted.copy(), tick, tick, log.nextSequence(), Optional.empty(), Optional.of(start)));
    }

    /**
     * Reads the recipe the result slot recorded, when it still holds one.
     *
     * <p>Used only to honour a card locked to one recipe. When the slot no longer knows,
     * the receipt simply carries no recipe rather than naming a guessed one.</p>
     */
    private static Optional<ResourceLocation> recipeUsed(ServerPlayer player) {
        var menu = player.containerMenu;
        if (menu == null || menu.slots.isEmpty()) return Optional.empty();
        Slot result = menu.slots.getFirst();
        if (!(result.container instanceof ResultContainer container)) return Optional.empty();
        var holder = container.getRecipeUsed();
        return holder == null ? Optional.empty() : Optional.of(holder.id());
    }
}
