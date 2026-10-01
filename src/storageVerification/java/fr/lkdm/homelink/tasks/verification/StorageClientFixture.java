package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/** Real Storage, only compiled for the optional integration profile. */
@net.neoforged.fml.common.EventBusSubscriber(modid = TasksValidation.MOD_ID)
public final class StorageClientFixture {
    private static final BlockPos ANCHOR = new BlockPos(8, -60, 8);
    private static boolean supply;
    @net.neoforged.bus.api.SubscribeEvent
    public static void supply(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (!Boolean.getBoolean("tasks.inGame") || !supply || event.getServer().getTickCount() % 20 != 0) return;
        var level = event.getServer().overworld();
        if (level.getChunkSource().getChunkNow(0, 0) == null) return;
        if (level.getBlockEntity(ANCHOR) instanceof StorageBlockEntity controller && controller.energyPort() != null)
            controller.energyPort().insert(100_000, false);
    }
    public static void setup(MinecraftServer server, UUID player, UUID board) {
        var level = server.overworld();
        var network = DashboardAPI.networks(server).createNetwork("Client stock validation", player);
        level.setBlockAndUpdate(ANCHOR.offset(2, 0, 0), Blocks.BARREL.defaultBlockState());
        var barrel = (BarrelBlockEntity) level.getBlockEntity(ANCHOR.offset(2, 0, 0));
        barrel.setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        level.setBlockAndUpdate(ANCHOR, StorageRegistries.CONTROLLER.get().defaultBlockState());
        level.setBlockAndUpdate(ANCHOR.offset(1, 0, 0), StorageRegistries.LINK.get().defaultBlockState());
        var controller = (StorageBlockEntity) level.getBlockEntity(ANCHOR);
        var link = (StorageBlockEntity) level.getBlockEntity(ANCHOR.offset(1, 0, 0));
        controller.setOwner(player); link.setOwner(player);
        controller.setHomeNetwork(network.id()); DashboardAPI.networks(server).addDevice(network.id(), controller.id());
        if (!link.bind(controller)) throw new IllegalStateException("Storage link did not bind");
        setPowered(server, true);
        StorageBlockEntity.tick(level, ANCHOR, controller.getBlockState(), controller);
        controller.refreshIndex();
        TaskSavedData.get(server).board(board).orElseThrow().setNetworkId(network.id());
        TaskSavedData.get(server).setDirty();
        var displayPos = ANCHOR.offset(0, 1, 3);
        level.setBlockAndUpdate(displayPos.north(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(displayPos, fr.lkdm.homelink.tasks.registry.TaskRegistries.TASK_DISPLAY.get().defaultBlockState()
                .setValue(fr.lkdm.homelink.tasks.block.TaskDisplayBlock.FACING, net.minecraft.core.Direction.SOUTH));
        var display = (fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity) level.getBlockEntity(displayPos);
        display.setOwner(player); display.setNetworkId(network.id()); display.setSelectedBoard(board);
        var actor = server.getPlayerList().getPlayer(player);
        actor.teleportTo(level, 8.5, -60, 13.5, java.util.Set.of(), 180, 0);
        display.openFor(actor);
    }
    public static void setPowered(MinecraftServer server, boolean powered) {
        supply = powered;
        var controller = (StorageBlockEntity) server.overworld().getBlockEntity(ANCHOR);
        if (controller.energyPort() != null) {
            if (powered) controller.energyPort().insert(100_000, false);
            else controller.energyPort().setStored(0);
        }
        StorageBlockEntity.tick(server.overworld(), ANCHOR, controller.getBlockState(), controller);
    }
}
