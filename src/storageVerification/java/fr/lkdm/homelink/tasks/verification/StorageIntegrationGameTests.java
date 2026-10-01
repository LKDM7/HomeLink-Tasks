package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.stock.StockAvailability;
import fr.lkdm.homecore.api.stock.StockProvider;
import fr.lkdm.homecore.api.stock.StockRequest;
import fr.lkdm.homelink.storage.blockentity.StorageBlockEntity;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import fr.lkdm.homelink.tasks.stock.StockContext;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development fixture uses actual Storage internals; the distributable Tasks JAR never imports them. */
@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorageIntegrationGameTests {
    private record Fixture(ServerPlayer owner, StorageBlockEntity controller, StorageBlockEntity link,
                           BarrelBlockEntity barrel, java.util.UUID network, BlockPos anchor) { }

    /** Coverage belongs to a chunk: keep all fixture blocks inside the Link's actual chunk. */
    private static BlockPos anchor(GameTestHelper helper) {
        var absolute = helper.absolutePos(new BlockPos(1, 1, 1));
        return new BlockPos(Math.floorMod(absolute.getX(), 16) <= 12 ? 1 : 4, 1,
                Math.floorMod(absolute.getZ(), 16) <= 12 ? 1 : 4);
    }

    @SuppressWarnings("removal")
    private static Fixture fixture(GameTestHelper helper) {
        var player = helper.makeMockServerPlayerInLevel();
        var level = helper.getLevel();
        var network = DashboardAPI.networks(level.getServer()).createNetwork("Stock fixture", player.getUUID());
        var anchor = anchor(helper);
        helper.setBlock(anchor.offset(2, 0, 0), Blocks.BARREL);
        var barrel = (BarrelBlockEntity) helper.getBlockEntity(anchor.offset(2, 0, 0));
        barrel.setItem(0, new ItemStack(Items.DIAMOND, 8));
        helper.setBlock(anchor, StorageRegistries.CONTROLLER.get());
        helper.setBlock(anchor.offset(1, 0, 0), StorageRegistries.LINK.get());
        var controller = (StorageBlockEntity) helper.getBlockEntity(anchor);
        var link = (StorageBlockEntity) helper.getBlockEntity(anchor.offset(1, 0, 0));
        controller.setOwner(player.getUUID()); link.setOwner(player.getUUID());
        controller.setHomeNetwork(network.id()); DashboardAPI.networks(level.getServer()).addDevice(network.id(), controller.id());
        helper.assertTrue(link.bind(controller), "The physical link binds");
        if (controller.energyPort() != null) controller.energyPort().insert(100_000, false);
        StorageBlockEntity.tick(level, controller.getBlockPos(), controller.getBlockState(), controller);
        controller.refreshIndex();
        return new Fixture(player, controller, link, barrel, network.id(), anchor);
    }

    private static fr.lkdm.homecore.api.stock.StockSnapshot observe(Fixture fixture, ServerPlayer player, ItemStack item) {
        return fixture.controller().device().capability(StockProvider.CAPABILITY).orElseThrow()
                .observe(new StockRequest(fixture.network(), player.getUUID(), List.of(item)));
    }

    @SuppressWarnings("removal")
    @GameTest(template = "storage_empty", timeoutTicks = 200)
    public static void viewerMayReadButRetrievalRemainsRestrictedAndRevocationDisclosesNothing(GameTestHelper helper) {
        var fixture = fixture(helper);
        var viewer = helper.makeMockServerPlayerInLevel();
        var networks = DashboardAPI.networks(helper.getLevel().getServer());
        networks.setMember(fixture.network(), viewer.getUUID(), fr.lkdm.homecore.api.network.NetworkRole.VIEWER);
        var readable = observe(fixture, viewer, new ItemStack(Items.DIAMOND));
        helper.assertTrue(readable.entries().stream().mapToLong(entry -> entry.total()).sum() == 8, "VIEW reveals the eight diamonds");
        helper.assertTrue(readable.access() == fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY, "VIEW grants no withdrawal");
        networks.removeMember(fixture.network(), viewer.getUUID());
        var revoked = observe(fixture, viewer, new ItemStack(Items.DIAMOND));
        helper.assertTrue(revoked.availability() == StockAvailability.UNAVAILABLE && revoked.entries().isEmpty(), "Revocation is checked before observation");
        helper.assertTrue(fixture.barrel().getItem(0).getCount() == 8, "No observation withdrew items");
        helper.succeed();
    }

    @GameTest(template = "storage_empty", timeoutTicks = 200)
    public static void controllerWithoutEnergyReportsUnknownInsteadOfItsOldIndex(GameTestHelper helper) {
        var fixture = fixture(helper);
        helper.assertTrue(observe(fixture, fixture.owner(), new ItemStack(Items.DIAMOND)).entries().size() == 1, "Powered index is readable");
        helper.assertTrue(fixture.controller().energyPort() != null, "This fixture uses Storage's own energy cost");
        fixture.controller().energyPort().setStored(0);
        StorageBlockEntity.tick(helper.getLevel(), fixture.controller().getBlockPos(), fixture.controller().getBlockState(), fixture.controller());
        var stopped = observe(fixture, fixture.owner(), new ItemStack(Items.DIAMOND));
        helper.assertTrue(stopped.availability() == StockAvailability.UNAVAILABLE && stopped.entries().isEmpty(), "Unpowered index is not presented as current stock");
        helper.succeed();
    }

    @GameTest(template = "storage_empty", timeoutTicks = 200)
    public static void unobservedInventoryAndRemovedCoverageCannotServeStaleCounts(GameTestHelper helper) {
        var fixture = fixture(helper);
        var initial = observe(fixture, fixture.owner(), new ItemStack(Items.DIAMOND));
        helper.assertTrue(initial.entries().stream().mapToLong(entry -> entry.total()).sum() == 8,
                "Initial source must be indexed: " + initial.availability() + " / " + fixture.controller().connections().size());
        for (var connection : fixture.controller().connections().values()) connection.observedTick = helper.getLevel().getGameTime()
                - Math.max(20L, fr.lkdm.homelink.storage.config.StorageConfig.RESCAN_INTERVAL.get() * 2L) - 1;
        var stale = observe(fixture, fixture.owner(), new ItemStack(Items.DIAMOND));
        helper.assertTrue(stale.availability() == StockAvailability.PARTIAL && stale.entries().isEmpty(),
                "An old successful observation cannot supply current quantities");
        for (var connection : fixture.controller().connections().values()) connection.observedTick = -1;
        var unknown = observe(fixture, fixture.owner(), new ItemStack(Items.DIAMOND));
        helper.assertTrue(unknown.availability() == StockAvailability.PARTIAL && unknown.entries().isEmpty(), "No successful scan means unknown, even if the index retained eight: " + unknown.availability() + " / " + unknown.entries().size());
        fixture.controller().refreshIndex();
        helper.assertTrue(observe(fixture, fixture.owner(), new ItemStack(Items.DIAMOND)).entries().size() == 1, "A real rescan restores the observation");
        helper.setBlock(fixture.anchor().offset(1, 0, 0), Blocks.AIR);
        var disconnected = observe(fixture, fixture.owner(), new ItemStack(Items.DIAMOND));
        helper.assertTrue(disconnected.entries().isEmpty(), "Removed coverage cannot disclose its old quantities");
        helper.succeed();
    }

    @GameTest(template = "storage_empty", timeoutTicks = 200)
    public static void allFacesAndBothHalvesDescribeOneDoubleChest(GameTestHelper helper) {
        var level = helper.getLevel();
        var left = new BlockPos(2, 1, 1); var right = new BlockPos(3, 1, 1);
        helper.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,
                net.minecraft.core.Direction.NORTH).setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.LEFT));
        helper.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING,
                net.minecraft.core.Direction.NORTH).setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(left)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(right)).setItem(0, new ItemStack(Items.DIAMOND, 5));
        var snapshots = new java.util.ArrayList<fr.lkdm.homecore.api.stock.StockSnapshot>();
        for (var pos : List.of(left, right)) for (var face : net.minecraft.core.Direction.values()) {
            var resolved = fr.lkdm.homelink.storage.storage.inventory.StorageInventoryAdapter.resolve(level, helper.absolutePos(pos), face);
            helper.assertTrue(resolved.adapter() != null, "Both halves resolve on every face");
            long count = 0;
            for (int slot = 0; slot < resolved.adapter().slots(); slot++) count += resolved.adapter().handler().getStackInSlot(slot).getCount();
            snapshots.add(new fr.lkdm.homecore.api.stock.StockSnapshot(StockAvailability.COMPLETE, fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY,
                    List.of(new fr.lkdm.homecore.api.stock.StockEntry(new ItemStack(Items.DIAMOND), java.util.Map.of(
                            new fr.lkdm.homecore.api.stock.StockSourceId(level.dimension().location(), resolved.adapter().identity()), count))), 0, level.getGameTime()));
        }
        var merged = StockContext.merge(snapshots, true, level.getGameTime());
        helper.assertTrue(merged.entries().getFirst().total() == 8, "Twelve actual capability views describe eight diamonds once");
        helper.succeed();
    }

    @GameTest(template = "storage_empty", timeoutTicks = 200)
    public static void realStockMovesRedOrangeGreenWithoutCreditingProduction(GameTestHelper helper) {
        var fixture = fixture(helper);
        var level = helper.getLevel(); var manager = fr.lkdm.homelink.tasks.server.TaskManager.get(level.getServer());
        var board = new fr.lkdm.homelink.tasks.board.TaskBoard(java.util.UUID.randomUUID(), "Availability", fixture.owner().getUUID(), level.getGameTime());
        board.setNetworkId(fixture.network());
        var card = new fr.lkdm.homelink.tasks.task.TaskCard(java.util.UUID.randomUUID(), board.id(), fr.lkdm.homelink.tasks.task.TaskType.CRAFT,
                "Four planks", fixture.owner().getUUID(), level.getGameTime());
        card.setObjective(new fr.lkdm.homelink.tasks.objective.CraftObjective(new ItemStack(Items.OAK_PLANKS), 4,
                net.minecraft.resources.ResourceLocation.parse("minecraft:oak_planks"), fr.lkdm.homelink.tasks.objective.ContributionPolicy.ALL_CONTRIBUTORS, level.getGameTime()));
        board.addCard(card); manager.data().addBoard(board); manager.indexCard(card);
        helper.setBlock(fixture.anchor().offset(0, 1, 0), Blocks.STONE);
        helper.setBlock(fixture.anchor().offset(0, 1, 1), fr.lkdm.homelink.tasks.registry.TaskRegistries.displayBlock().defaultBlockState()
                .setValue(fr.lkdm.homelink.tasks.block.TaskDisplayBlock.FACING, net.minecraft.core.Direction.SOUTH));
        var screen = (fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity) helper.getBlockEntity(fixture.anchor().offset(0, 1, 1));
        screen.setNetworkId(fixture.network()); screen.setSelectedBoard(board.id());
        fixture.owner().setPos(screen.getBlockPos().getCenter());
        fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(level.getServer()).openBoard(fixture.owner(), board.id(), screen.getBlockPos());
        fixture.controller().refreshIndex();
        var initialPlan = manager.plan(fixture.owner(), board, card);
        var initialStock = observe(fixture, fixture.owner(), new ItemStack(Items.OAK_LOG));
        helper.assertTrue(initialPlan.state() == fr.lkdm.homelink.tasks.stock.AvailabilityState.MISSING,
                "A complete real index without logs is red: " + initialPlan.state() + " / " + initialStock.availability()
                        + " / " + fixture.controller().connections().size());
        fixture.barrel().setItem(1, new ItemStack(Items.OAK_LOG)); fixture.controller().refreshIndex();
        helper.assertTrue(manager.plan(fixture.owner(), board, card).state() == fr.lkdm.homelink.tasks.stock.AvailabilityState.IN_STORAGE,
                "A real log newly observed in Storage is orange");
        fixture.barrel().setItem(1, ItemStack.EMPTY); fixture.controller().refreshIndex();
        fixture.owner().getInventory().add(new ItemStack(Items.OAK_LOG));
        helper.assertTrue(manager.plan(fixture.owner(), board, card).state() == fr.lkdm.homelink.tasks.stock.AvailabilityState.READY,
                "The log in the viewing player's inventory is green");
        helper.assertTrue(card.objective().orElseThrow().completedQuantity() == 0 && card.status() == fr.lkdm.homelink.tasks.task.TaskStatus.TODO,
                "Moving actual materials never contributes or changes the task status");
        helper.succeed();
    }
    @SuppressWarnings("removal")
    @GameTest(template = "storage_empty", timeoutTicks = 200)
    public static void realProvidersDeduplicateChestAndRejectAnotherNetwork(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        var level = helper.getLevel();
        var network = DashboardAPI.networks(level.getServer()).createNetwork("Storage contract fixture", player.getUUID());
        var anchor = anchor(helper);
        BlockPos chestPos = anchor.offset(2, 0, 0);
        helper.setBlock(chestPos, Blocks.BARREL);
        var barrel = (BarrelBlockEntity) helper.getBlockEntity(chestPos);
        barrel.setItem(0, new ItemStack(Items.DIAMOND, 8));
        var snapshots = new java.util.ArrayList<fr.lkdm.homecore.api.stock.StockSnapshot>();
        for (int index = 0; index < 2; index++) {
            BlockPos controllerPos = anchor.offset(0, 0, index * 2);
            BlockPos linkPos = controllerPos.offset(1, 0, 0);
            helper.setBlock(controllerPos, StorageRegistries.CONTROLLER.get());
            helper.setBlock(linkPos, StorageRegistries.LINK.get());
            var controller = (StorageBlockEntity) helper.getBlockEntity(controllerPos);
            var link = (StorageBlockEntity) helper.getBlockEntity(linkPos);
            controller.setOwner(player.getUUID()); link.setOwner(player.getUUID());
            controller.setHomeNetwork(network.id()); DashboardAPI.networks(level.getServer()).addDevice(network.id(), controller.id());
            helper.assertTrue(link.bind(controller), "Real Storage Link must bind");
            if (controller.energyPort() != null) controller.energyPort().insert(100_000, false);
            StorageBlockEntity.tick(level, controller.getBlockPos(), controller.getBlockState(), controller);
            controller.refreshIndex();
            // Public capability is the only stock interface Tasks uses in production.
            var provider = controller.device().capability(StockProvider.CAPABILITY).orElseThrow();
            var request = new StockRequest(network.id(), player.getUUID(), List.of(new ItemStack(Items.DIAMOND)));
            var observed = provider.observe(request);
            snapshots.add(observed);
            helper.assertTrue(observed.entries().stream().anyMatch(entry -> entry.total() == 8), "Real provider must report eight diamonds: "
                    + observed.availability() + " / controller=" + controller.getBlockPos() + " / link=" + link.getBlockPos()
                    + " / barrel=" + barrel.getBlockPos() + " / connections=" + controller.connections().size()
                    + " / indexComplete=" + controller.indexComplete());
            helper.assertTrue(barrel.getItem(0).getCount() == 8, "Reading must not extract any item");
            var wrong = provider.observe(new StockRequest(java.util.UUID.randomUUID(), player.getUUID(), request.variants()));
            helper.assertTrue(wrong.availability() == StockAvailability.UNAVAILABLE && wrong.entries().isEmpty(), "Another network must disclose nothing");
        }
        var merged = StockContext.merge(snapshots, true, level.getGameTime());
        helper.assertTrue(merged.entries().size() == 1 && merged.entries().getFirst().total() == 8, "Two actual controllers must count a physical source once");
        helper.succeed();
    }
}
