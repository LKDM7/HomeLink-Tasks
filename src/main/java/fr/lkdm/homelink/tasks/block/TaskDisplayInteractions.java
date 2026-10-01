package fr.lkdm.homelink.tasks.block;

import fr.lkdm.homelink.tasks.HomeLinkTasks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Connector interactions with any screen cell resolve to its sole device. */
@EventBusSubscriber(modid = HomeLinkTasks.MOD_ID)
public final class TaskDisplayInteractions {
    private TaskDisplayInteractions() { }

    @SubscribeEvent
    public static void connector(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getItemStack().is(fr.lkdm.homecore.registry.HomeCoreItems.HOMELINK_CONNECTOR.get())) return;
        var state = event.getLevel().getBlockState(event.getPos());
        if (!(state.getBlock() instanceof TaskDisplayBlock)) return;
        var master = TaskDisplayBlock.masterPos(state, event.getPos());
        if (master.equals(event.getPos())) return;
        event.setCanceled(true);
        if (event.getLevel().isClientSide) { event.setCancellationResult(InteractionResult.SUCCESS); return; }
        if (!event.getLevel().isLoaded(master)) { event.setCancellationResult(InteractionResult.FAIL); return; }
        var hit = event.getHitVec();
        var redirected = new BlockHitResult(hit.getLocation(), hit.getDirection(), master, hit.isInside());
        event.setCancellationResult(event.getItemStack().getItem().useOn(new UseOnContext(event.getEntity(), event.getHand(), redirected)));
    }
}
