package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.network.NetworkMember;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlock;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.registry.TaskRegistries;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The screen on a running server: it is a real HomeCore device, and breaking it destroys
 * nothing that belongs to a project.
 */
@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TaskDisplayGameTests {
    @GameTest(template = "empty")
    public static void displayStatesAndAutomaticSelectionRespectAccess(GameTestHelper h) {
        var owner = new net.neoforged.neoforge.common.util.FakePlayer(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ScreenOwner"));
        var visitor = new net.neoforged.neoforge.common.util.FakePlayer(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "ScreenVisitor"));
        // These observers have no negotiated transport; assertions read the server state directly.
        owner.connection = null;
        visitor.connection = null;
        var pos = h.absolutePos(new BlockPos(1, 1, 2));
        h.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        h.setBlock(new BlockPos(1, 1, 2), TaskRegistries.displayBlock().defaultBlockState().setValue(TaskDisplayBlock.FACING, Direction.SOUTH));
        owner.setPos(pos.getCenter()); visitor.setPos(pos.getCenter());
        var screen = (TaskDisplayBlockEntity)h.getLevel().getBlockEntity(pos);
        screen.setOwner(owner.getUUID());
        var data = TaskSavedData.get(owner.server);
        var board = new TaskBoard(UUID.randomUUID(), "Selected project", owner.getUUID(), 0);
        data.addBoard(board);
        h.assertTrue(fr.lkdm.homelink.tasks.server.DisplayQueries.describe(owner, pos).state() == fr.lkdm.homelink.tasks.network.DisplayPackets.State.NO_PROJECT, "Empty is not private");
        fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(owner.server).openBoard(owner, board.id(), pos);
        h.assertTrue(screen.selectedBoard().filter(board.id()::equals).isPresent(), "Owner selection must persist on screen");
        h.assertTrue(fr.lkdm.homelink.tasks.server.DisplayQueries.describe(owner, pos).view().isPresent(), "Owner should see project");
        var denied = fr.lkdm.homelink.tasks.server.DisplayQueries.describe(visitor, pos);
        h.assertTrue(denied.state() == fr.lkdm.homelink.tasks.network.DisplayPackets.State.PRIVATE && denied.view().isEmpty(), "Private response must contain no project data");
        var visitorBoard = new TaskBoard(UUID.randomUUID(), "Visitor project", visitor.getUUID(), 0);
        data.addBoard(visitorBoard);
        fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(owner.server).openBoard(visitor, visitorBoard.id(), pos);
        h.assertTrue(screen.selectedBoard().filter(board.id()::equals).isPresent(), "Visitor must not change physical selection");
        var networks = DashboardAPI.networks(owner.server);
        var network = networks.createNetwork("Display fixture", owner.getUUID());
        try {
            var device = new fr.lkdm.homelink.tasks.block.TaskDisplayDevice(screen);
            h.assertTrue(DashboardAPI.bindDevice(visitor, device, java.util.Optional.of(network.id())) == NetworkMember.BindResult.DENIED, "Visitor cannot bind owner screen");
            h.assertTrue(DashboardAPI.bindDevice(owner, device, java.util.Optional.of(network.id())) == NetworkMember.BindResult.BOUND, "Owner can bind via HomeCore");
            h.assertTrue(networks.getDevices(network.id()).contains(screen.id()), "HomeCore must contain the device");
            h.assertTrue(fr.lkdm.homelink.tasks.server.DisplayQueries.describe(visitor, pos).state() == fr.lkdm.homelink.tasks.network.DisplayPackets.State.NETWORK_DENIED, "Network denial needs its own message");
            var saved = screen.saveWithoutMetadata(h.getLevel().registryAccess());
            h.assertTrue(saved.getUUID("Network").equals(network.id()) && saved.getUUID("Board").equals(board.id()), "Selection and binding must persist together");
            board.setNetworkId(UUID.randomUUID());
            h.assertTrue(fr.lkdm.homelink.tasks.server.DisplayQueries.describe(owner, pos).state() == fr.lkdm.homelink.tasks.network.DisplayPackets.State.NETWORK_MISMATCH, "Wrong project network is not private");
        } finally { networks.deleteNetwork(network.id()); }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void actualHomeCoreConnectorBindsTaskScreen(GameTestHelper h) {
        var owner = new net.neoforged.neoforge.common.util.FakePlayer(h.getLevel(),
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "TaskConnector"));
        var pos = h.absolutePos(new BlockPos(1, 1, 2));
        h.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        h.setBlock(new BlockPos(1, 1, 2), TaskRegistries.displayBlock().defaultBlockState().setValue(TaskDisplayBlock.FACING, Direction.SOUTH));
        var screen = (TaskDisplayBlockEntity)h.getLevel().getBlockEntity(pos);
        screen.setOwner(owner.getUUID());
        var networks = DashboardAPI.networks(owner.server);
        var network = networks.createNetwork("Connector fixture", owner.getUUID());
        try {
            var connector = fr.lkdm.homecore.registry.HomeCoreItems.HOMELINK_CONNECTOR.get();
            owner.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new net.minecraft.world.item.ItemStack(connector));
            connector.use(owner.level(), owner, net.minecraft.world.InteractionHand.MAIN_HAND);
            var hit = new net.minecraft.world.phys.BlockHitResult(pos.getCenter(), Direction.SOUTH, pos, false);
            connector.onItemUseFirst(owner.getMainHandItem(), new net.minecraft.world.item.context.UseOnContext(owner, net.minecraft.world.InteractionHand.MAIN_HAND, hit));
            h.assertTrue(screen.networkId().filter(network.id()::equals).isPresent() && networks.getDevices(network.id()).contains(screen.id()), "Real connector must bind screen and network");
        } finally { networks.deleteNetwork(network.id()); }
        h.succeed();
    }
    /**
     * A placed screen registers itself with HomeCore and is bindable by the shared connector.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void screenIsARealDevice(GameTestHelper helper) {
        BlockPos wall = new BlockPos(1, 1, 1);
        BlockPos screenPos = wall.relative(Direction.SOUTH);
        helper.setBlock(wall, Blocks.STONE);
        helper.setBlock(screenPos, TaskRegistries.displayBlock().defaultBlockState()
                .setValue(TaskDisplayBlock.FACING, Direction.SOUTH));

        helper.succeedWhen(() -> {
            TaskDisplayBlockEntity screen = helper.getBlockEntity(screenPos) instanceof TaskDisplayBlockEntity entity
                    ? entity : null;
            helper.assertTrue(screen != null, "the screen must have a block entity");
            var device = DashboardAPI.devices(helper.getLevel().getServer()).get(screen.id()).orElse(null);
            helper.assertTrue(device != null, "the screen must register itself as a HomeCore device");
            // Implementing NetworkMember is what makes the existing HomeLink Connector work on it.
            helper.assertTrue(device instanceof NetworkMember, "the screen must be bindable by the connector");
            helper.assertTrue(screen.networkId().isEmpty(), "a fresh screen must not invent a network");
        });
    }

    /**
     * Breaking the screen leaves every board and card untouched on the server.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void breakingTheScreenKeepsTheBoards(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        TaskSavedData data = TaskSavedData.get(server);
        UUID owner = UUID.randomUUID();
        TaskBoard board = new TaskBoard(UUID.randomUUID(), "Atelier", owner, server.overworld().getGameTime());
        TaskCard card = new TaskCard(UUID.randomUUID(), board.id(), TaskType.MANUAL,
                "Construire le bâtiment", owner, server.overworld().getGameTime());
        board.addCard(card);
        data.addBoard(board);

        BlockPos wall = new BlockPos(1, 1, 1);
        BlockPos screenPos = wall.relative(Direction.SOUTH);
        helper.setBlock(wall, Blocks.STONE);
        helper.setBlock(screenPos, TaskRegistries.displayBlock().defaultBlockState()
                .setValue(TaskDisplayBlock.FACING, Direction.SOUTH));
        if (helper.getBlockEntity(screenPos) instanceof TaskDisplayBlockEntity screen) {
            screen.setSelectedBoard(board.id());
        }
        helper.setBlock(screenPos, Blocks.AIR);

        helper.succeedWhen(() -> {
            helper.assertTrue(data.board(board.id()).isPresent(), "the board must survive the screen");
            helper.assertTrue(data.board(board.id()).orElseThrow().card(card.id()).isPresent(),
                    "the card must survive the screen");
            helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(screenPos)).isAir(),
                    "the screen block must be gone");
        });
    }

    /**
     * A screen only shows a board a player may read; it grants nothing by itself.
     *
     * @param helper game test helper
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void screenGrantsNoAccess(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        TaskSavedData data = TaskSavedData.get(server);
        UUID owner = UUID.randomUUID();
        UUID outsider = UUID.randomUUID();
        TaskBoard board = new TaskBoard(UUID.randomUUID(), "Privé", owner, server.overworld().getGameTime());
        data.addBoard(board);

        helper.succeedIf(() -> {
            helper.assertTrue(fr.lkdm.homelink.tasks.board.BoardPermissions.canView(board, owner),
                    "the owner must see their own board");
            helper.assertFalse(fr.lkdm.homelink.tasks.board.BoardPermissions.canView(board, outsider),
                    "a screen never turns a stranger into a member");
            helper.assertTrue(data.boardsFor(outsider).isEmpty(),
                    "a stranger must be offered no board at all");
        });
    }
}
