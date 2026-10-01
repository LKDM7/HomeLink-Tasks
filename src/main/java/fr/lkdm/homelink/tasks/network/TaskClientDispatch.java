package fr.lkdm.homelink.tasks.network;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Side-safe entry point for the payloads a client receives.
 *
 * <p>Registration happens on both sides, so the handlers must be reachable from a
 * dedicated server without loading a single client class. Each method checks the
 * distribution first; the call into the client code below is resolved only when it
 * actually runs, which on a dedicated server is never.</p>
 */
public final class TaskClientDispatch {
    private TaskClientDispatch() { }
    public static void onScreenNetwork(ScreenNetworkPackets.Snapshot payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        context.enqueueWork(() -> {
            if (net.minecraft.client.Minecraft.getInstance().screen instanceof fr.lkdm.homelink.tasks.client.ScreenNetworkScreen screen) screen.receive(payload);
        });
    }
    public static void onProjectPlan(ProjectPackets.Snapshot payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        context.enqueueWork(() -> {
            var client = net.minecraft.client.Minecraft.getInstance();
            if (fr.lkdm.homelink.tasks.client.ClientTaskState.board().filter(board -> board.id().equals(payload.board())).isPresent()
                    && client.screen instanceof fr.lkdm.homelink.tasks.client.ProjectMaterialsScreen screen) screen.receive(payload);
        });
    }

    public static void onDisplaySnapshot(DisplayPackets.Snapshot payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        fr.lkdm.homelink.tasks.client.DisplayClientState.receive(payload, context);
    }

    public static void onMembers(MemberPackets.Directory payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        fr.lkdm.homelink.tasks.client.ClientTaskState.onMembers(payload, context);
    }

    /** Delivers a board snapshot to the client.
     * @param payload received payload
     * @param context payload context
     */
    public static void onBoardSnapshot(TaskPackets.BoardSnapshot payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        fr.lkdm.homelink.tasks.client.ClientTaskState.onBoardSnapshot(payload, context);
    }

    /** Delivers an availability plan to the client.
     * @param payload received payload
     * @param context payload context
     */
    public static void onCardPlan(TaskPackets.CardPlan payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        fr.lkdm.homelink.tasks.client.ClientTaskState.onCardPlan(payload, context);
    }

    /** Delivers the player's own pinned cards to the client.
     * @param payload received payload
     * @param context payload context
     */
    public static void onPinnedTasks(TaskPackets.PinnedTasks payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        fr.lkdm.homelink.tasks.client.ClientTaskState.onPinnedTasks(payload, context);
    }

    /** Delivers a refusal or conflict notice to the client.
     * @param payload received payload
     * @param context payload context
     */
    public static void onNotice(TaskPackets.NoticeMessage payload, IPayloadContext context) {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        fr.lkdm.homelink.tasks.client.ClientTaskState.onNotice(payload, context);
    }
}
