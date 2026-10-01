package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.security.Permission;
import fr.lkdm.homelink.tasks.HomeLinkTasks;
import fr.lkdm.homelink.tasks.block.TaskDisplayDevice;
import fr.lkdm.homelink.tasks.server.TaskAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Physical screen binding; HomeCore remains the authority for both network permissions. */
public final class ScreenNetworkPackets {
    private ScreenNetworkPackets() { }
    public record Choice(UUID id, String name) { }
    public record Request(BlockPos pos, boolean change, Optional<UUID> network) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(HomeLinkTasks.id("screen_network_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((b, v) -> {
            b.writeBlockPos(v.pos()); b.writeBoolean(v.change()); b.writeOptional(v.network(), (out, id) -> out.writeUUID(id));
        }, b -> new Request(b.readBlockPos(), b.readBoolean(), b.readOptional(in -> in.readUUID())));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Snapshot(BlockPos pos, Optional<UUID> current, List<Choice> choices, boolean editable, String result) implements CustomPacketPayload {
        public Snapshot { choices = List.copyOf(choices); }
        public static final Type<Snapshot> TYPE = new Type<>(HomeLinkTasks.id("screen_network_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((b, v) -> {
            b.writeBlockPos(v.pos()); b.writeOptional(v.current(), (out, id) -> out.writeUUID(id));
            b.writeVarInt(v.choices().size());
            for (var choice : v.choices()) { b.writeUUID(choice.id()); b.writeUtf(choice.name(), 256); }
            b.writeBoolean(v.editable()); b.writeUtf(v.result(), 32);
        }, b -> {
            var pos = b.readBlockPos(); var current = b.readOptional(in -> in.readUUID());
            int count = ByteBufCodecs.readCount(b, 128);
            var choices = new java.util.ArrayList<Choice>(count);
            for (int i = 0; i < count; i++) choices.add(new Choice(b.readUUID(), b.readUtf(256)));
            return new Snapshot(pos, current, choices, b.readBoolean(), b.readUtf(32));
        });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void handle(Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var screen = TaskAccess.nearbyScreen(player, request.pos(), 64);
            if (screen == null) return;
            var device = new TaskDisplayDevice(screen);
            boolean editable = device.canConfigure(player);
            String result = editable ? "" : "denied";
            if (request.change()) result = DashboardAPI.bindDevice(player, device, request.network()).name().toLowerCase(java.util.Locale.ROOT);
            var choices = editable ? DashboardAPI.networks(player.server).getNetworksForPlayer(player.getUUID()).stream()
                    .filter(n -> DashboardAPI.hasPermission(player, n.id(), Permission.MANAGE_NETWORK))
                    .limit(128).map(n -> new Choice(n.id(), n.name().substring(0, Math.min(256, n.name().length())))).toList()
                    : List.<Choice>of();
            TaskTransport.send(player, new Snapshot(request.pos(), editable ? screen.networkId() : Optional.empty(), choices, editable, result));
        });
    }
}
