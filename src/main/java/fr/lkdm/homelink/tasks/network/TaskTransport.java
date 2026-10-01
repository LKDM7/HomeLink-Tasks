package fr.lkdm.homelink.tasks.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Fake players and disconnected connections have no negotiated Tasks payload channels. */
public final class TaskTransport {
    private TaskTransport() { }
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload))
            PacketDistributor.sendToPlayer(player, payload);
    }
}
