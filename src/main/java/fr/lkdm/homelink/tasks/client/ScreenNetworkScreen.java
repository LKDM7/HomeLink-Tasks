package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.network.ScreenNetworkPackets;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Configure the physical display separately from the project attached to it. */
public final class ScreenNetworkScreen extends TaskScreen {
    private final Screen parent;
    private final BlockPos pos;
    private ScreenNetworkPackets.Snapshot snapshot;
    private int selected;
    public ScreenNetworkScreen(Screen parent, BlockPos pos) {
        super(Component.translatable("screen.homelink_tasks.screen_network"));
        this.parent = parent; this.pos = pos.immutable();
        PacketDistributor.sendToServer(new ScreenNetworkPackets.Request(pos, false, Optional.empty()));
    }
    public void receive(ScreenNetworkPackets.Snapshot value) {
        if (!pos.equals(value.pos())) return;
        snapshot = value; dataChanged();
    }
    @Override protected void initContent() {
        int left = Math.max(12, width / 2 - 190), wide = Math.min(380, width - 24);
        if (snapshot != null) {
            var choices = snapshot.choices();
            addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.previous"), b -> { selected--; dataChanged(); }).bounds(left, 95, 30, 20).build()).active = choices.size() > 1;
            addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.next"), b -> { selected++; dataChanged(); }).bounds(left + wide - 30, 95, 30, 20).build()).active = choices.size() > 1;
            addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.connect_screen"), b -> {
                b.active = false;
                PacketDistributor.sendToServer(new ScreenNetworkPackets.Request(pos, true, Optional.of(choices.get(Math.floorMod(selected, choices.size())).id())));
            }).bounds(left, 126, wide, 20).build()).active = snapshot.editable() && !choices.isEmpty();
            addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.disconnect_screen"), b -> {
                b.active = false;
                PacketDistributor.sendToServer(new ScreenNetworkPackets.Request(pos, true, Optional.empty()));
            }).bounds(left, 151, wide, 20).build()).active = snapshot.editable() && snapshot.current().isPresent();
        }
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.back"), b -> onClose()).bounds(left, height - 26, wide, 20).build());
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public void dataChanged() { clearWidgets(); initContent(); }
    @Override public void renderContent(GuiGraphics g, int x, int y, float tick) {
        renderBackground(g, x, y, tick);
        int left = Math.max(12, width / 2 - 190), wide = Math.min(380, width - 24);
        g.drawString(font, title, left, 33, TaskTheme.TEXT, false);
        if (snapshot != null) {
            String current = snapshot.current().map(id -> snapshot.choices().stream().filter(n -> n.id().equals(id))
                    .map(ScreenNetworkPackets.Choice::name).findFirst().orElse(id.toString()))
                    .orElse(Component.translatable("screen.homelink_tasks.unbound_screen").getString());
            g.drawString(font, TaskTheme.clip(font, current, wide), left, 65, TaskTheme.TEXT_MUTED, false);
            String chosen = snapshot.choices().isEmpty() ? Component.translatable("screen.homelink_tasks.no_network").getString()
                    : snapshot.choices().get(Math.floorMod(selected, snapshot.choices().size())).name();
            g.drawString(font, TaskTheme.clip(font, chosen, wide - 80), left + 38, 101, TaskTheme.TEXT, false);
            if (!snapshot.result().isEmpty()) g.drawWordWrap(font, Component.translatable("message.homecore.connector." + snapshot.result()), left, 185, wide, TaskTheme.COPPER);
        }
        super.renderContent(g, x, y, tick);
    }
}
