package fr.lkdm.homelink.tasks.client;

import com.mojang.blaze3d.platform.InputConstants;
import fr.lkdm.homelink.tasks.HomeLinkTasks;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only setup: the overlay, the key binding and the display configuration.
 *
 * <p>Nothing here decides anything about a board. The key binding asks the server for a
 * board the player is allowed to open; it is not a way into one they are not.</p>
 */
@Mod(value = HomeLinkTasks.MOD_ID, dist = Dist.CLIENT)
public final class TaskClient {
    /** Opens the pinned board or task. */
    public static final KeyMapping OPEN_PINNED = new KeyMapping(
            "key.homelink_tasks.open_pinned", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            InputConstants.KEY_K, "key.categories.homelink_tasks");

    /**
     * Registers client setup.
     *
     * @param modBus mod event bus
     * @param container this mod's container
     */
    public TaskClient(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, TaskClientConfig.SPEC);
        modBus.addListener(TaskClient::registerKeys);
        modBus.addListener(TaskClient::registerLayers);
        modBus.addListener(TaskClient::registerRenderers);
        NeoForge.EVENT_BUS.addListener(TaskClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(TaskClient::onLoggedOut);
    }

    public static final KeyMapping VIEW_PROJECT = new KeyMapping(
            "key.homelink_tasks.view_project", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(), "key.categories.homelink_tasks");

    private static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(VIEW_PROJECT);
        event.register(OPEN_PINNED);
    }

    private static void registerLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HomeLinkTasks.id("pinned_tasks"), PinnedTaskOverlay::render);
    }

    private static void registerRenderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(fr.lkdm.homelink.tasks.registry.TaskRegistries.TASK_DISPLAY_ENTITY.get(),
                TaskDisplayRenderer::new);
    }

    private static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        DisplayClientState.tick();
        ClientTaskState.tick();
        while (VIEW_PROJECT.consumeClick()) ProjectViewerScreen.open();
        while (OPEN_PINNED.consumeClick()) {
            // Asking the server which board to open, rather than opening one the client guessed.
            ClientTaskState.pinned().stream().findFirst().ifPresentOrElse(
                    view -> TaskClientNetwork.board(TaskPackets.BoardCommand.OPEN, view.board(), "", null,
                            fr.lkdm.homelink.tasks.board.BoardRole.VIEWER, false, 0L),
                    () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(new TaskPackets.BoardRequest(
                            TaskPackets.BoardCommand.OPEN, java.util.Optional.empty(), "", java.util.Optional.empty(),
                            fr.lkdm.homelink.tasks.board.BoardRole.VIEWER, false, java.util.Optional.empty(), 0)));
        }
    }

    private static void onLoggedOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        // Nothing from one world may still be on screen after joining another.
        ClientTaskState.clear();
        DisplayClientState.clear();
        PinnedTaskOverlay.clear();
    }

    /** Client-side event subscriptions that need no instance. */
    @EventBusSubscriber(modid = HomeLinkTasks.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        private Events() { }

        /** Keeps the board screen in step with the window being resized.
         * @param event screen initialisation event
         */
        @SubscribeEvent
        public static void onScreenInit(net.neoforged.neoforge.client.event.ScreenEvent.Init.Post event) {
            // The board screen rebuilds its own columns on init, so nothing more is needed here.
        }
    }
}
