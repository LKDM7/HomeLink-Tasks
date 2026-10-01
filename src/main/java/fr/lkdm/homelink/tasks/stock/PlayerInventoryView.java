package fr.lkdm.homelink.tasks.stock;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * The stacks a plan counts as "in your inventory".
 *
 * <p>The thirty-six main slots, hotbar included exactly once, plus the off hand. The
 * main hand is already part of the hotbar and is not counted a second time.</p>
 *
 * <p>Deliberately excluded: another player's inventory, the ender chest, the contents
 * of shulker boxes and bags, worn armour, result slots, and the virtual stack held by
 * the cursor. Ingredients already sitting in another workbench are not in your
 * inventory either, and are never silently added. Supporting a particular container
 * would need its own explicit adapter, which this version does not have.</p>
 */
public final class PlayerInventoryView {
    private PlayerInventoryView() { }

    /**
     * Reads a player's countable stacks.
     *
     * <p>Copies are returned, so a calculation can never modify what a player is
     * carrying: planning is not a transaction.</p>
     *
     * @param player player whose plan is being computed
     * @return copied stacks, each slot appearing exactly once
     */
    public static List<ItemStack> countable(Player player) {
        Objects.requireNonNull(player, "player");
        Inventory inventory = player.getInventory();
        List<ItemStack> stacks = new ArrayList<>(Inventory.INVENTORY_SIZE + 1);
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) stacks.add(stack.copy());
        }
        for (ItemStack offhand : inventory.offhand) {
            if (!offhand.isEmpty()) stacks.add(offhand.copy());
        }
        return List.copyOf(stacks);
    }
}
