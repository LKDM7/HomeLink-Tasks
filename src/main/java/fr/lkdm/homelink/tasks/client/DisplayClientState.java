package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.network.DisplayPackets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Ephemeral summaries for this observer, with a short expiry on loss of access. */
public final class DisplayClientState {
    private static final Map<BlockPos, Entry> VIEWS = new LinkedHashMap<>();
    private static final Map<BlockPos, Long> VISIBLE = new LinkedHashMap<>();
    private static long ticks;
    private static net.minecraft.resources.ResourceLocation dimension;
    private static final int EXPIRY = 25;
    private DisplayClientState() { }

    public static void observe(BlockPos pos) {
        if (VISIBLE.size() < 64 || VISIBLE.containsKey(pos)) VISIBLE.put(pos.immutable(), ticks);
    }

    public static Optional<DisplayPackets.View> view(BlockPos pos) {
        Entry entry = VIEWS.get(pos);
        return entry == null || ticks - entry.tick() > EXPIRY ? Optional.empty() : Optional.ofNullable(entry.view());
    }

    public static DisplayPackets.State state(BlockPos pos) {
        Entry entry = VIEWS.get(pos);
        return entry == null || ticks - entry.tick() > EXPIRY ? DisplayPackets.State.LOADING : entry.state();
    }

    public static void receive(DisplayPackets.Snapshot snapshot, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!VISIBLE.containsKey(snapshot.pos())) return;
            VIEWS.put(snapshot.pos(), new Entry(snapshot.view().orElse(null), ticks, snapshot.state()));
        });
    }

    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) { clear(); return; }
        var current = client.level.dimension().location();
        if (!current.equals(dimension)) { clear(); dimension = current; }
        ticks++;
        VIEWS.entrySet().removeIf(entry -> ticks - entry.getValue().tick() > EXPIRY);
        VISIBLE.entrySet().removeIf(entry -> ticks - entry.getValue() > 20);
        if (ticks % 20 != 0) return;
        var positions = VISIBLE.keySet().stream()
                .filter(pos -> client.player.distanceToSqr(pos.getCenter()) <= 16 * 16)
                .sorted(java.util.Comparator.comparingDouble(pos -> client.player.distanceToSqr(pos.getCenter())))
                .limit(DisplayPackets.MAX_DISPLAYS).toList();
        if (!positions.isEmpty()) PacketDistributor.sendToServer(new DisplayPackets.Request(positions));
    }

    public static void clear() { VIEWS.clear(); VISIBLE.clear(); ticks = 0; dimension = null; }
    private record Entry(DisplayPackets.View view, long tick, DisplayPackets.State state) { }
}
