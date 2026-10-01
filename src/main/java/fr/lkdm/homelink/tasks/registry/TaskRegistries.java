package fr.lkdm.homelink.tasks.registry;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homelink.tasks.HomeLinkTasks;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlock;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity;
import fr.lkdm.homelink.tasks.block.TaskDisplayDevice;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Everything this mod registers with the game. */
public final class TaskRegistries {
    /** Blocks owned by this mod. */
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(HomeLinkTasks.MOD_ID);
    /** Items owned by this mod. */
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(HomeLinkTasks.MOD_ID);
    /** Block entity types owned by this mod. */
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, HomeLinkTasks.MOD_ID);
    /** Creative tabs owned by this mod. */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, HomeLinkTasks.MOD_ID);

    /** The wall-mounted screen that opens a project. */
    public static final DeferredBlock<TaskDisplayBlock> TASK_DISPLAY = BLOCKS.register("task_display",
            () -> new TaskDisplayBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(2.0F)
                    .sound(SoundType.METAL)
                    .noOcclusion()
                    .lightLevel(state -> 5)));

    /** Item form of the screen. */
    public static final DeferredItem<BlockItem> TASK_DISPLAY_ITEM =
            ITEMS.registerSimpleBlockItem("task_display", TASK_DISPLAY);

    public static final DeferredBlock<TaskDisplayBlock> TASK_DISPLAY_MEDIUM = registerDisplay("task_display_medium");
    public static final DeferredBlock<TaskDisplayBlock> TASK_DISPLAY_LARGE = registerDisplay("task_display_large");
    public static final DeferredItem<BlockItem> TASK_DISPLAY_MEDIUM_ITEM =
            ITEMS.registerSimpleBlockItem("task_display_medium", TASK_DISPLAY_MEDIUM);
    public static final DeferredItem<BlockItem> TASK_DISPLAY_LARGE_ITEM =
            ITEMS.registerSimpleBlockItem("task_display_large", TASK_DISPLAY_LARGE);

    private static DeferredBlock<TaskDisplayBlock> registerDisplay(String name) {
        return BLOCKS.register(name, () -> new TaskDisplayBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK).strength(2.0F).sound(SoundType.METAL).noOcclusion()));
    }

    /** Block entity type backing the screen. */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TaskDisplayBlockEntity>>
            TASK_DISPLAY_ENTITY = BLOCK_ENTITIES.register("task_display",
                    () -> BlockEntityType.Builder.of(TaskDisplayBlockEntity::new, TASK_DISPLAY.get(),
                            TASK_DISPLAY_MEDIUM.get(), TASK_DISPLAY_LARGE.get()).build(null));

    /** Creative tab holding this mod's content. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.homelink_tasks"))
                    .icon(() -> TASK_DISPLAY_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(TASK_DISPLAY_ITEM.get());
                    })
                    .build());

    private TaskRegistries() { }

    /** Registers every deferred register on the mod bus.
     * @param modBus mod event bus
     */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        TABS.register(modBus);
    }

    /**
     * Publishes the screen to HomeCore once registries are frozen.
     *
     * <p>Registering the adapter is what lets the existing HomeLink Connector bind this
     * screen; no binding tool of this mod's own is added.</p>
     *
     * @param event common setup event
     */
    public static void registerDeviceProviders(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> DashboardAPI.registerDeviceProvider(TASK_DISPLAY_ENTITY.get(),
                (TaskDisplayBlockEntity screen) -> new TaskDisplayDevice(screen)));
    }

    /** Returns the item this mod's recipe produces, for data generation and tests.
     * @return screen item
     */
    public static Item displayItem() { return TASK_DISPLAY_ITEM.get(); }

    /** Returns the screen block.
     * @return screen block
     */
    public static Block displayBlock() { return TASK_DISPLAY.get(); }
}
