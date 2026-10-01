package fr.lkdm.homelink.tasks.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class TaskDisplayBlock extends BaseEntityBlock {
    public static final MapCodec<TaskDisplayBlock> CODEC = simpleCodec(TaskDisplayBlock::new);
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Size> SIZE = EnumProperty.create("size", Size.class);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    private static final int ASSEMBLY_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final ThreadLocal<Boolean> REMOVING = ThreadLocal.withInitial(() -> false);

    public TaskDisplayBlock(Properties properties) {
        super(properties.pushReaction(PushReaction.DESTROY));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SIZE, Size.SINGLE).setValue(PART, Part.BOTTOM_LEFT));
    }
    @Override protected MapCodec<TaskDisplayBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
        builder.add(SIZE, PART);
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == Part.BOTTOM_LEFT ? new TaskDisplayBlockEntity(pos, state) : null;
    }

    /** All offsets are measured from the bottom left when looking at the front. */
    public static BlockPos masterPos(BlockState state, BlockPos pos) {
        var part = state.getValue(PART);
        return pos.relative(state.getValue(FACING).getCounterClockWise(), -part.column()).below(part.row());
    }

    private static BlockPos partPos(BlockState state, BlockPos master, Part part) {
        return master.relative(state.getValue(FACING).getCounterClockWise(), part.column()).above(part.row());
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        var face = context.getClickedFace();
        var state = defaultBlockState().setValue(FACING, face.getAxis().isHorizontal()
                ? face : context.getHorizontalDirection().getOpposite());
        boolean single = context.getPlayer() != null && context.getPlayer().isShiftKeyDown();
        for (var size : new Size[] { Size.LARGE, Size.WIDE, Size.SINGLE }) {
            if (single && size != Size.SINGLE) continue;
            // Prefer right/up, then left/up, right/down and left/down, always covering the click.
            for (var part : Part.values()) {
                if (!part.fits(size)) continue;
                var candidate = state.setValue(SIZE, size).setValue(PART, part);
                var master = masterPos(candidate, context.getClickedPos());
                boolean fits = true;
                for (var cell : Part.values()) {
                    if (!cell.fits(size)) continue;
                    var cellPos = partPos(candidate, master, cell);
                    var cellContext = BlockPlaceContext.at(context, cellPos, context.getClickedFace());
                    if (context.getLevel().isOutsideBuildHeight(cellPos)
                            || !context.getLevel().getWorldBorder().isWithinBounds(cellPos)
                            || !context.getLevel().getBlockState(cellPos).canBeReplaced(cellContext)) {
                        fits = false;
                        break;
                    }
                }
                if (fits) return candidate;
            }
        }
        return null;
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        var master = masterPos(state, pos);
        // Install the whole rectangle before emitting shape updates. The clicked cell may be a satellite.
        for (var part : Part.values()) {
            if (part.fits(state.getValue(SIZE))) {
                level.setBlock(partPos(state, master, part), state.setValue(PART, part), ASSEMBLY_FLAGS);
            }
        }
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(master) instanceof TaskDisplayBlockEntity display) display.setOwner(player.getUUID());
        for (var part : Part.values()) {
            if (part.fits(state.getValue(SIZE))) notifyNeighbors(level, partPos(state, master, part));
        }
    }

    private boolean matches(BlockState candidate, BlockState state, Part part) {
        return candidate.is(this) && candidate.getValue(SIZE) == state.getValue(SIZE)
                && candidate.getValue(FACING) == state.getValue(FACING) && candidate.getValue(PART) == part;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // Front/back blocks never support the display. Only its own rectangle matters.
        if (direction.getAxis() == state.getValue(FACING).getAxis()) return state;
        var size = state.getValue(SIZE);
        if (!state.getValue(PART).fits(size)) return Blocks.AIR.defaultBlockState();
        var master = masterPos(state, pos);
        for (var part : Part.values()) {
            if (!part.fits(size)) continue;
            var cell = partPos(state, master, part);
            // A rectangle can straddle a chunk boundary; an unloaded chunk is not a missing piece.
            if (!level.hasChunk(cell.getX() >> 4, cell.getZ() >> 4)) return state;
            if (!matches(level.getBlockState(cell), state, part)) return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!REMOVING.get() && !matches(replacement, state, state.getValue(PART))) {
            var master = masterPos(state, pos);
            // Vanilla handles the initiating cell's loot (including tool, creative and explosion rules).
            // All other cells disappear silently, also for commands, neighbor updates and pistons.
            var removed = new java.util.ArrayList<BlockPos>();
            REMOVING.set(true);
            try {
                for (var part : Part.values()) {
                    if (!part.fits(state.getValue(SIZE))) continue;
                    var cell = partPos(state, master, part);
                    if (!cell.equals(pos) && matches(level.getBlockState(cell), state, part)) {
                        level.setBlock(cell, Blocks.AIR.defaultBlockState(), ASSEMBLY_FLAGS | Block.UPDATE_SUPPRESS_DROPS);
                        removed.add(cell);
                    }
                }
            } finally {
                REMOVING.set(false);
            }
            // Notify only after every satellite is gone: intermediate shape updates could drop it again.
            removed.forEach(cell -> notifyNeighbors(level, cell));
            if (state.getValue(PART) == Part.BOTTOM_LEFT) level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }

    private void notifyNeighbors(Level level, BlockPos pos) {
        level.updateNeighborsAt(pos, this);
        level.getBlockState(pos).updateNeighbourShapes(level, pos, Block.UPDATE_ALL);
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        var master = masterPos(state, pos);
        if (!level.isClientSide && player instanceof net.minecraft.server.level.ServerPlayer server
                && level.getBlockEntity(master) instanceof TaskDisplayBlockEntity display) display.openFor(server);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state,
            Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (stack.is(fr.lkdm.homecore.registry.HomeCoreItems.HOMELINK_CONNECTOR.get()))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        useWithoutItem(state, level, pos, player, hit);
        return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override protected net.minecraft.world.level.block.RenderShape getRenderShape(BlockState state) {
        return net.minecraft.world.level.block.RenderShape.MODEL;
    }

    @Override protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return net.minecraft.world.phys.shapes.Shapes.empty();
    }

    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int bottom = state.getValue(PART).row() == 0 ? 2 : 0;
        int top = state.getValue(SIZE) == Size.LARGE && state.getValue(PART).row() == 0 ? 16 : 14;
        return switch (state.getValue(FACING)) {
            case SOUTH -> Block.box(0, bottom, 0, 16, top, 3.125);
            case EAST -> Block.box(0, bottom, 0, 3.125, top, 16);
            case WEST -> Block.box(12.875, bottom, 0, 16, top, 16);
            default -> Block.box(0, bottom, 12.875, 16, top, 16);
        };
    }

    public enum Size implements StringRepresentable {
        SINGLE("single", 1, 1), WIDE("wide", 2, 1), LARGE("large", 2, 2);
        private final String name;
        private final int width;
        private final int height;
        Size(String name, int width, int height) { this.name = name; this.width = width; this.height = height; }
        @Override public String getSerializedName() { return name; }
        public int width() { return width; }
        public int height() { return height; }
    }

    public enum Part implements StringRepresentable {
        BOTTOM_LEFT("bottom_left", 0, 0), BOTTOM_RIGHT("bottom_right", 1, 0),
        TOP_LEFT("top_left", 0, 1), TOP_RIGHT("top_right", 1, 1);
        private final String name;
        private final int column;
        private final int row;
        Part(String name, int column, int row) { this.name = name; this.column = column; this.row = row; }
        @Override public String getSerializedName() { return name; }
        public int column() { return column; }
        public int row() { return row; }
        public boolean fits(Size size) { return column < size.width() && row < size.height(); }
    }
}
