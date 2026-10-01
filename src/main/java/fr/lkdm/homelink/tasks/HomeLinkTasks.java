package fr.lkdm.homelink.tasks;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.tasks.registry.TaskRegistries;
import fr.lkdm.homelink.tasks.server.TaskManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Entry point shared by the client and the dedicated server.
 *
 * <p>Boards, progress and every decision live on the server. The client only draws what
 * it is authorised to see, so a dedicated server never loads a client class from here.</p>
 */
@Mod(HomeLinkTasks.MOD_ID)
public final class HomeLinkTasks {
    /** Namespace of every identifier this mod owns. */
    public static final String MOD_ID = "homelink_tasks";
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Registers content and server lifecycle listeners.
     *
     * @param modBus mod event bus of this mod
     */
    public HomeLinkTasks(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
                fr.lkdm.homelink.tasks.server.TaskServerConfig.SPEC);
        TaskRegistries.register(modBus);
        modBus.addListener(fr.lkdm.homelink.tasks.network.TaskPayloads::register);
        modBus.addListener(TaskRegistries::registerDeviceProviders);
        var forgeBus = net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        forgeBus.addListener(this::serverStarted);
        forgeBus.addListener(this::serverStopped);
        forgeBus.addListener(this::playerLoggedOut);
        forgeBus.addListener(this::serverTick);
        forgeBus.addListener(this::playerLoggedIn);
        // Fired when a crafted result is actually taken, which is when the items exist.
        forgeBus.addListener(fr.lkdm.homelink.tasks.production.VanillaCraftListener::onCrafted);
        LOGGER.info("HomeLink Tasks initialized");
    }

    /** Builds an identifier in this mod's namespace.
     * @param path path of the identifier
     * @return namespaced identifier
     */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private void serverStarted(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        TaskManager.start(event.getServer());
    }

    private void serverStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        // Listeners, caches and subscriptions are released; nothing is caught up on the next start.
        TaskManager.stop(event.getServer());
    }

    private void playerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            TaskManager.get(player.server).budget().forget(player.getUUID());
            TaskManager.get(player.server).displayQueries().forget(player.getUUID());
            TaskManager.get(player.server).requests().forget(player.getUUID());
            fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(player.server).logout(player.getUUID());
        }
    }

    private void playerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            TaskManager.get(player.server).data().recordPlayer(player.getUUID(), player.getGameProfile().getName());
            fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(player.server).sendPinned(player);
        }
    }

    private void serverTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(event.getServer()).tick();
        var manager = TaskManager.get(event.getServer());
        manager.displayQueries().tick(manager);
    }
}
