package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.HomeLinkTasks;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Local server identities only. No account lookup or remote service is used. */
public final class MemberPackets {
    public static final int LIMIT = 128;
    private MemberPackets() { }
    public record Player(UUID id, String name) { }

    public record Request(UUID board) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(HomeLinkTasks.id("members_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buffer, request) -> buffer.writeUUID(request.board()), buffer -> new Request(buffer.readUUID()));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Directory(UUID board, List<Player> players) implements CustomPacketPayload {
        public static final Type<Directory> TYPE = new Type<>(HomeLinkTasks.id("members_directory"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Directory> CODEC = StreamCodec.of((buffer, directory) -> {
            buffer.writeUUID(directory.board());
            buffer.writeVarInt(directory.players().size());
            for (Player player : directory.players()) { buffer.writeUUID(player.id()); buffer.writeUtf(player.name(), 64); }
        }, buffer -> {
            UUID board = buffer.readUUID();
            int count = ByteBufCodecs.readCount(buffer, LIMIT);
            List<Player> players = new ArrayList<>(count);
            for (int index = 0; index < count; index++) players.add(new Player(buffer.readUUID(), buffer.readUtf(64)));
            return new Directory(board, players);
        });
        public Directory {
            players = List.copyOf(players);
            if (players.size() > LIMIT) throw new IllegalArgumentException("Too many known players");
        }
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void request(Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (fr.lkdm.homelink.tasks.server.TaskManager.get(player.server).requests().claim(player.getUUID(), UUID.randomUUID(),
                    player.server.overworld().getGameTime()) != fr.lkdm.homelink.tasks.server.RequestLedger.Result.ACCEPTED) return;
            var data = TaskSavedData.get(player.server);
            var board = data.board(request.board()).orElse(null);
            if (board == null || !BoardPermissions.canView(board, player.getUUID())) return;
            var entries = new java.util.LinkedHashMap<UUID, String>();
            for (UUID id : board.members().keySet()) {
                String name = data.knownPlayers().getOrDefault(id, id.toString().substring(0, 8));
                if (player.server.getProfileCache() != null) name = player.server.getProfileCache().get(id)
                        .map(profile -> profile.getName()).orElse(name);
                entries.put(id, name);
            }
            if (BoardPermissions.canManageBoard(board, player.getUUID())) data.knownPlayers().forEach(entries::putIfAbsent);
            fr.lkdm.homelink.tasks.network.TaskTransport.send(player, new Directory(board.id(), entries.entrySet().stream().limit(LIMIT)
                    .map(entry -> new Player(entry.getKey(), entry.getValue())).toList()));
        });
    }
}
