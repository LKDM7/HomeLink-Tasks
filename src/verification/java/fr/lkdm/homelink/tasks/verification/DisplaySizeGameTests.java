package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homelink.tasks.block.TaskDisplayBlock;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlock.Part;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlock.Size;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity;
import fr.lkdm.homelink.tasks.registry.TaskRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DisplaySizeGameTests {
    @GameTest(template = "empty")
    public static void removingBackingWallKeepsEveryScreenSize(GameTestHelper h) {
        for (var facing : Direction.Plane.HORIZONTAL) for (var size : Size.values()) {
            var target = prepare(h);
            constrain(h, target, facing, size);
            var owner = player(h, target, false);
            var master = place(h, target, facing, owner, size);
            for (var part : Part.values()) if (fits(size, part))
                solid(h, cell(master, facing, part).relative(facing.getOpposite()));
            var entity = (TaskDisplayBlockEntity)h.getLevel().getBlockEntity(master);
            var identity = entity.id();
            for (var part : Part.values()) if (fits(size, part))
                h.getLevel().destroyBlock(cell(master, facing, part).relative(facing.getOpposite()), false);
            assertScreen(h, master, facing, size, owner);
            h.assertTrue(((TaskDisplayBlockEntity)h.getLevel().getBlockEntity(master)).id().equals(identity), "Removing support must preserve device identity");
            h.assertTrue(dropCount(h, target) == 0, "Removing backing must not drop the display");
        }
        h.succeed();
    }
    @GameTest(template = "empty")
    public static void openWallChoosesLargeInEveryOrientation(GameTestHelper h) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            var target = prepare(h);
            var player = player(h, target, false);
            var master = place(h, target, facing, player, Size.LARGE);
            h.assertTrue(master.equals(target), "An open wall must extend right and up: " + facing);
            assertScreen(h, master, facing, Size.LARGE, player);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void oneRowChoosesWideInEveryOrientation(GameTestHelper h) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            var target = prepare(h);
            constrain(h, target, facing, Size.WIDE);
            var player = player(h, target, false);
            var master = place(h, target, facing, player, Size.WIDE);
            h.assertTrue(master.equals(target), "One row must prefer extending right");
            assertScreen(h, master, facing, Size.WIDE, player);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void narrowSpaceChoosesSingleInEveryOrientation(GameTestHelper h) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            var target = prepare(h);
            constrain(h, target, facing, Size.SINGLE);
            var player = player(h, target, false);
            assertScreen(h, place(h, target, facing, player, Size.SINGLE), facing, Size.SINGLE, player);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void sneakingForcesSingleWithoutBackingWall(GameTestHelper h) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            var target = prepare(h);
            var player = player(h, target, true);
            // A replaceable clicked cell exercises the existing free-standing placement behavior.
            var stack = new ItemStack(block(), 2);
            var context = new BlockPlaceContext(h.getLevel(), player, InteractionHand.MAIN_HAND, stack,
                    new BlockHitResult(Vec3.atCenterOf(target), facing, target, false));
            h.assertTrue(block().asItem().useOn(context).consumesAction(), "Sneaking free-standing placement failed");
            h.assertTrue(stack.getCount() == 1, "A forced single screen consumes one item");
            assertScreen(h, target, facing, Size.SINGLE, player);
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void alternativeAnchorsKeepClickedCellInScreen(GameTestHelper h) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            for (var clickedPart : Part.values()) {
                var target = prepare(h);
                var right = facing.getCounterClockWise();
                var master = target.relative(right, -clickedPart.column()).below(clickedPart.row());
                // Only the intended 2x2 rectangle remains replaceable in the screen plane.
                for (int column = -1; column <= 1; column++) {
                    for (int row = -1; row <= 1; row++) {
                        var candidate = target.relative(right, column).above(row);
                        int x = column + clickedPart.column();
                        int y = row + clickedPart.row();
                        if (x < 0 || x >= 2 || y < 0 || y >= 2) solid(h, candidate);
                    }
                }
                var player = player(h, target, false);
                h.assertTrue(place(h, target, facing, player, Size.LARGE).equals(master),
                        "Placement must relocate the master for clicked part " + clickedPart + "/" + facing);
                h.assertTrue(h.getLevel().getBlockState(target).getValue(TaskDisplayBlock.PART) == clickedPart,
                        "Clicked cell must have its actual part");
                assertScreen(h, master, facing, Size.LARGE, player);
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void occupiedCellRejectsPlacementWithoutConsumingItem(GameTestHelper h) {
        var target = prepare(h);
        solid(h, target);
        var stack = new ItemStack(block(), 2);
        var context = wallContext(h, player(h, target, false), target, Direction.NORTH, stack);
        h.assertTrue(block().getStateForPlacement(context) == null, "An occupied target must refuse every size");
        h.assertTrue(!block().asItem().useOn(context).consumesAction(), "Item placement in occupied space must fail");
        h.assertTrue(stack.getCount() == 2 && h.getLevel().getBlockState(target).is(Blocks.STONE),
                "Rejected placement must preserve target and stack");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void buildHeightLimitsConstrainEveryCell(GameTestHelper h) {
        var origin = h.absolutePos(new BlockPos(2, 2, 2));
        var target = new BlockPos(origin.getX(), h.getLevel().getMaxBuildHeight() - 1, origin.getZ());
        var player = player(h, target, false);
        for (int column = -1; column <= 1; column++) solid(h, target.offset(column, -1, 0));
        var master = place(h, target, Direction.NORTH, player, Size.WIDE);
        assertScreen(h, master, Direction.NORTH, Size.WIDE, player);
        h.getLevel().removeBlock(master, false);
        for (int column = -1; column <= 1; column++) h.getLevel().removeBlock(target.offset(column, -1, 0), false);
        h.getLevel().removeBlock(target.south(), false);
        var beyond = target.above();
        var stack = new ItemStack(block());
        var context = new BlockPlaceContext(h.getLevel(), player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(Vec3.atCenterOf(beyond), Direction.NORTH, beyond, false));
        h.assertTrue(block().getStateForPlacement(context) == null, "Every candidate outside build height must fail");
        h.succeed();
    }



    @GameTest(template = "empty")
    public static void survivalBreakEveryPartDropsOneScreen(GameTestHelper h) {
        verifyPlayerBreaks(h, GameType.SURVIVAL, true);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeBreakEveryPartDropsNothing(GameTestHelper h) {
        verifyPlayerBreaks(h, GameType.CREATIVE, true);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void bareHandBreakPreservesExistingTaskScreenDrops(GameTestHelper h) {
        verifyPlayerBreaks(h, GameType.SURVIVAL, false);
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void worldRemovalAndReplacementRemoveWholeScreen(GameTestHelper h) {
        for (var part : Part.values()) {
            for (boolean replacement : new boolean[] {false, true}) {
                var target = prepare(h);
                var master = place(h, target, Direction.NORTH, player(h, target, false), Size.LARGE);
                var removed = cell(master, Direction.NORTH, part);
                if (replacement) h.getLevel().setBlock(removed, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                else h.getLevel().destroyBlock(removed, true);
                for (var other : Part.values()) {
                    var pos = cell(master, Direction.NORTH, other);
                    h.assertTrue(!h.getLevel().getBlockState(pos).is(block()), "External removal must remove every part");
                }
                if (replacement) h.assertTrue(h.getLevel().getBlockState(removed).is(Blocks.STONE), "Replacement must survive cleanup");
                h.assertTrue(dropCount(h, target) == (replacement ? 0 : 1), "External removal must not duplicate drops: " + part + "/replacement=" + replacement + "/drops=" + dropCount(h, target));
            }
        }
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void explosionOnEveryPartDropsOnlyOneScreen(GameTestHelper h) {
        for (var part : Part.values()) {
            var target = prepare(h);
            var master = place(h, target, Direction.NORTH, player(h, target, false), Size.LARGE);
            var hit = cell(master, Direction.NORTH, part);
            // Keep blast geometry deterministic while running vanilla's actual explosion destruction and loot.
            var explosion = new Explosion(h.getLevel(), null, hit.getX() + 0.5, hit.getY() + 0.5,
                    hit.getZ() + 0.5, 1.0F, false, Explosion.BlockInteraction.DESTROY, java.util.List.of(hit));
            explosion.finalizeExplosion(false);
            assertGone(h, master, Direction.NORTH, Size.LARGE);
            h.assertTrue(dropCount(h, target) == 1, "Explosion of " + part + " must drop exactly one screen");
        }
        var target = prepare(h);
        var master = place(h, target, Direction.NORTH, player(h, target, false), Size.LARGE);
        var affected = java.util.Arrays.stream(Part.values()).map(part -> cell(master, Direction.NORTH, part)).toList();
        var explosion = new Explosion(h.getLevel(), null, target.getX(), target.getY(), target.getZ(),
                1.0F, false, Explosion.BlockInteraction.DESTROY, affected);
        explosion.finalizeExplosion(false);
        assertGone(h, master, Direction.NORTH, Size.LARGE);
        h.assertTrue(dropCount(h, target) == 1, "A blast covering all cells must still drop only one screen");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void poweredPistonDestroysWholeScreenWithoutDuplicating(GameTestHelper h) {
        var target = prepare(h);
        var master = place(h, target, Direction.NORTH, player(h, target, false), Size.LARGE);
        var piston = cell(master, Direction.NORTH, Part.TOP_RIGHT).south();
        h.getLevel().setBlock(piston, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.NORTH), Block.UPDATE_ALL);
        h.getLevel().setBlock(piston.south(), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
        h.runAfterDelay(8, () -> {
            h.assertTrue(h.getLevel().getBlockState(piston).getValue(PistonBaseBlock.EXTENDED), "The powered piston must actually extend");
            assertGone(h, master, Direction.NORTH, Size.LARGE);
            h.assertTrue(dropCount(h, target) == 1, "Piston destruction must drop exactly one screen");
            h.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void inconsistentPartSizeOrFacingInvalidatesScreen(GameTestHelper h) {
        for (int mutation = 0; mutation < 3; mutation++) {
            var target = prepare(h);
            var master = place(h, target, Direction.NORTH, player(h, target, false), Size.LARGE);
            var damaged = cell(master, Direction.NORTH, Part.TOP_RIGHT);
            var state = h.getLevel().getBlockState(damaged);
            var corrupt = switch (mutation) {
                case 0 -> state.setValue(TaskDisplayBlock.PART, Part.TOP_LEFT);
                case 1 -> state.setValue(TaskDisplayBlock.SIZE, Size.WIDE);
                default -> state.setValue(TaskDisplayBlock.FACING, Direction.EAST);
            };
            h.getLevel().setBlock(damaged, corrupt, Block.UPDATE_ALL);
            // A subsequent ordinary neighbor change must also invalidate the inconsistent initiating cell.
            solid(h, damaged.above());
            for (var part : Part.values()) {
                h.assertTrue(!h.getLevel().getBlockState(cell(master, Direction.NORTH, part)).is(block()),
                        "Inconsistent size, facing or part must not leave orphan cells: mutation " + mutation);
            }
            h.assertTrue(dropCount(h, target) <= 1, "Integrity cleanup must never duplicate items");
        }
        h.succeed();
    }



    @GameTest(template = "empty")
    public static void legacyStateLoadsAsSingleMaster(GameTestHelper h) {
        var tag = new CompoundTag();
        tag.putString("Name", "homelink_tasks:task_display");
        var properties = new CompoundTag();
        properties.putString("facing", "east");
        properties.putString("status", "error");
        tag.put("Properties", properties);
        var state = NbtUtils.readBlockState(h.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK), tag);
        h.assertTrue(state.getValue(TaskDisplayBlock.SIZE) == Size.SINGLE
                && state.getValue(TaskDisplayBlock.PART) == Part.BOTTOM_LEFT,
                "Serialized states without size/part must default to a single master");
        var target = prepare(h);
        h.getLevel().setBlock(target, state, Block.UPDATE_ALL);
        h.assertTrue(h.getLevel().getBlockEntity(target) instanceof TaskDisplayBlockEntity, "Legacy screen keeps its entity");
        h.assertTrue(TaskDisplayBlock.masterPos(state, target).equals(target), "Legacy screen is its own master");
        h.assertTrue(state.getValue(TaskDisplayBlock.FACING) == Direction.EAST
               , "Legacy properties must survive decoding");
        h.succeed();
    }

    private static void verifyPlayerBreaks(GameTestHelper h, GameType mode, boolean correctTool) {
        for (var facing : Direction.Plane.HORIZONTAL) {
            for (var size : Size.values()) {
                for (var part : Part.values()) {
                    if (!fits(size, part)) continue;
                    var target = prepare(h);
                    constrain(h, target, facing, size);
                    var master = place(h, target, facing, player(h, target, false), size);
                    var player = h.makeMockPlayer(mode);
                    var tool = correctTool ? new ItemStack(Items.DIAMOND_PICKAXE) : ItemStack.EMPTY;
                    player.setItemInHand(InteractionHand.MAIN_HAND, tool);
                    var broken = cell(master, facing, part);
                    var state = h.getLevel().getBlockState(broken);
                    var entity = h.getLevel().getBlockEntity(broken);
                    boolean harvest = player.hasCorrectToolForDrops(state, h.getLevel(), broken);
                    h.assertTrue(harvest, "Task screens preserve their no-required-tool behavior");
                    // ServerPlayerGameMode's destruction lifecycle, with the same tool and old entity.
                    block().playerWillDestroy(h.getLevel(), broken, state, player);
                    h.getLevel().removeBlock(broken, false);
                    if (mode != GameType.CREATIVE && harvest) block().playerDestroy(h.getLevel(), player, broken, state, entity, tool);
                    for (var other : Part.values()) {
                        if (fits(size, other)) h.assertTrue(h.getLevel().getBlockState(cell(master, facing, other)).isAir(),
                                "Breaking " + part + " must remove " + size + "/" + facing);
                    }
                    int expected = mode == GameType.SURVIVAL ? 1 : 0;
                    h.assertTrue(dropCount(h, target) == expected, "Wrong drop count for " + mode + "/" + size + "/" + part + "/" + facing);
                }
            }
        }
    }

    private static TaskDisplayBlock block() { return TaskRegistries.TASK_DISPLAY.get(); }

    private static BlockPos prepare(GameTestHelper h) {
        var target = h.absolutePos(new BlockPos(2, 2, 2));
        for (var pos : BlockPos.betweenClosed(target.offset(-2, -1, -2), target.offset(2, 2, 2))) {
            h.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
        }
        for (var item : h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(3))) item.discard();
        return target;
    }

    private static Player player(GameTestHelper h, BlockPos target, boolean sneaking) {
        var player = h.makeMockPlayer(GameType.SURVIVAL);
        player.setPos(target.getX() + 0.5, target.getY(), target.getZ() - 2.5);
        player.setShiftKeyDown(sneaking);
        return player;
    }

    private static BlockPlaceContext wallContext(GameTestHelper h, Player player, BlockPos target, Direction facing, ItemStack stack) {
        var wall = target.relative(facing.getOpposite());
        solid(h, wall);
        return new BlockPlaceContext(h.getLevel(), player, InteractionHand.MAIN_HAND, stack,
                new BlockHitResult(Vec3.atCenterOf(wall).relative(facing, 0.5), facing, wall, false));
    }

    private static BlockPos place(GameTestHelper h, BlockPos target, Direction facing, Player player, Size expected) {
        var stack = new ItemStack(block(), 2);
        var context = wallContext(h, player, target, facing, stack);
        h.assertTrue(block().asItem().useOn(context).consumesAction(), "Item placement failed for " + expected + "/" + facing);
        h.assertTrue(stack.getCount() == 1, "A whole screen consumes exactly one item");
        var state = h.getLevel().getBlockState(target);
        h.assertTrue(state.is(block()) && state.getValue(TaskDisplayBlock.SIZE) == expected, "Wrong chosen size: " + expected);
        return TaskDisplayBlock.masterPos(state, target);
    }

    private static void constrain(GameTestHelper h, BlockPos target, Direction facing, Size size) {
        var right = facing.getCounterClockWise();
        if (size != Size.LARGE) {
            for (int column = -1; column <= 1; column++) {
                solid(h, target.relative(right, column).above());
                solid(h, target.relative(right, column).below());
            }
        }
        if (size == Size.SINGLE) {
            solid(h, target.relative(right));
            solid(h, target.relative(right.getOpposite()));
        }
    }

    private static void assertScreen(GameTestHelper h, BlockPos master, Direction facing, Size size, Player owner) {
        for (var part : Part.values()) {
            if (!fits(size, part)) continue;
            var pos = cell(master, facing, part);
            var state = h.getLevel().getBlockState(pos);
            h.assertTrue(state.is(block()) && state.getValue(TaskDisplayBlock.SIZE) == size
                    && state.getValue(TaskDisplayBlock.PART) == part && state.getValue(TaskDisplayBlock.FACING) == facing,
                    "Wrong screen part at " + part + "/" + facing);
            h.assertTrue(TaskDisplayBlock.masterPos(state, pos).equals(master), "Every part must resolve the same master");
            var entity = h.getLevel().getBlockEntity(pos);
            if (part == Part.BOTTOM_LEFT) {
                h.assertTrue(entity instanceof TaskDisplayBlockEntity, "Master must own the sole entity");
                h.assertTrue(((TaskDisplayBlockEntity) entity).owner().orElseThrow().equals(owner.getUUID()), "Owner must be initialized on relocated master");
            } else h.assertTrue(entity == null, "Secondary cells must never own a block entity");
        }
    }

    private static boolean fits(Size size, Part part) { return part.column() < size.width() && part.row() < size.height(); }
    private static void assertGone(GameTestHelper h, BlockPos master, Direction facing, Size size) {
        for (var part : Part.values()) {
            if (fits(size, part)) h.assertTrue(!h.getLevel().getBlockState(cell(master, facing, part)).is(block()),
                    "Removed screen must leave no cell at " + part);
        }
    }
    private static BlockPos cell(BlockPos master, Direction facing, Part part) { return master.relative(facing.getCounterClockWise(), part.column()).above(part.row()); }
    private static void solid(GameTestHelper h, BlockPos pos) { h.getLevel().setBlock(pos, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL); }
    private static int dropCount(GameTestHelper h, BlockPos target) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(3)).stream()
                .mapToInt(item -> item.getItem().is(block().asItem()) ? item.getItem().getCount() : 0).sum();
    }
}
