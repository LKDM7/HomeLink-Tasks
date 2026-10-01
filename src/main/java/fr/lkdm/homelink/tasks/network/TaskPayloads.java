package fr.lkdm.homelink.tasks.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers this mod's own channel.
 *
 * <p>A dedicated channel, so nothing here overwrites or competes with HomeCore's own
 * dashboard subscription.</p>
 */
public final class TaskPayloads {
    /** Protocol version; both sides must agree on it. */
    public static final String VERSION = "4";

    private TaskPayloads() { }

    /** Declares every payload and its handler.
     * @param event payload registration event
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(ScreenNetworkPackets.Request.TYPE, ScreenNetworkPackets.Request.CODEC, ScreenNetworkPackets::handle);
        registrar.playToClient(ScreenNetworkPackets.Snapshot.TYPE, ScreenNetworkPackets.Snapshot.CODEC, TaskClientDispatch::onScreenNetwork);
        registrar.playToClient(TaskPackets.BoardSnapshot.TYPE, TaskPackets.BoardSnapshot.CODEC,
                TaskClientDispatch::onBoardSnapshot);
        registrar.playToClient(TaskPackets.CardPlan.TYPE, TaskPackets.CardPlan.CODEC,
                TaskClientDispatch::onCardPlan);
        registrar.playToClient(TaskPackets.PinnedTasks.TYPE, TaskPackets.PinnedTasks.CODEC,
                TaskClientDispatch::onPinnedTasks);
        registrar.playToClient(TaskPackets.NoticeMessage.TYPE, TaskPackets.NoticeMessage.CODEC,
                TaskClientDispatch::onNotice);
        registrar.playToServer(TaskPackets.BoardRequest.TYPE, TaskPackets.BoardRequest.CODEC,
                TaskRequestHandler::onBoardRequest);
        registrar.playToServer(TaskPackets.CardRequest.TYPE, TaskPackets.CardRequest.CODEC,
                TaskRequestHandler::onCardRequest);
        registrar.playToServer(TaskPackets.PersonalRequest.TYPE, TaskPackets.PersonalRequest.CODEC,
                TaskRequestHandler::onPersonalRequest);
        registrar.playToServer(DisplayPackets.Request.TYPE, DisplayPackets.Request.CODEC,
                fr.lkdm.homelink.tasks.server.DisplayQueries::handle);
        registrar.playToClient(DisplayPackets.Snapshot.TYPE, DisplayPackets.Snapshot.CODEC,
                TaskClientDispatch::onDisplaySnapshot);
        registrar.playToServer(MemberPackets.Request.TYPE, MemberPackets.Request.CODEC, MemberPackets::request);
        registrar.playToClient(MemberPackets.Directory.TYPE, MemberPackets.Directory.CODEC, TaskClientDispatch::onMembers);
        registrar.playToServer(ProjectPackets.Request.TYPE, ProjectPackets.Request.CODEC, ProjectPackets::request);
        registrar.playToClient(ProjectPackets.Snapshot.TYPE, ProjectPackets.Snapshot.CODEC, TaskClientDispatch::onProjectPlan);
    }
}
