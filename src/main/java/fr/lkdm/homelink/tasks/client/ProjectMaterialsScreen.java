package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homelink.tasks.network.ProjectPackets;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Lists the plan frontier, including recipe choices, without claiming an optimal modpack plan. */
public final class ProjectMaterialsScreen extends TaskScreen {
    private final UUID board;
    private ProjectPackets.Snapshot snapshot;
    private int scroll;
    private long nextRequest;
    private long received;
    public ProjectMaterialsScreen(UUID board) { super(Component.translatable("screen.homelink_tasks.project_materials")); this.board = board; }
    @Override protected void initContent() {
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.back"),
                ignored -> onClose()).bounds(12, height - 26, 90, HomeLinkTheme.CONTROL_HEIGHT).build());
    }
    public void receive(ProjectPackets.Snapshot value) { if (board.equals(value.board())) { snapshot = value; received = System.currentTimeMillis(); } }
    @Override public void onClose() { BoardScreen.openOrRefresh(); }
    private int rows() { return Math.max(1, (height - 100) / 44); }
    @Override public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) {
        int count = snapshot == null ? 0 : snapshot.needs().size();
        scroll = Math.clamp(scroll - (int) Math.signum(vertical), 0, Math.max(0, count - rows())); return true;
    }
    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, 12, 32, HomeLinkTheme.TEXT, false);
        graphics.drawString(font, HomeLinkUi.clip(font, Component.translatable("screen.homelink_tasks.project_plan_hint").getString(), width - 24), 12, 48, HomeLinkTheme.MUTED, false);
        if (snapshot != null) {
            int end = Math.min(snapshot.needs().size(), scroll + rows());
            for (int index = scroll; index < end; index++) {
                var need = snapshot.needs().get(index); int y = 66 + (index - scroll) * 44;
                graphics.fill(12, y, width - 12, y + 40, HomeLinkTheme.SURFACE);
                if (!need.alternatives().isEmpty()) {
                    var item = need.alternatives().get((int) ((System.currentTimeMillis() / 1800) % need.alternatives().size()));
                    graphics.renderItem(item, 16, y + 6);
                    graphics.drawString(font, HomeLinkUi.clip(font, need.quantity() + " × " + item.getHoverName().getString(), width - 65), 38, y + 4, HomeLinkTheme.TEXT, false);
                }
                String card = ClientTaskState.card(need.card()).map(fr.lkdm.homelink.tasks.network.CardView::title).orElse("");
                graphics.drawString(font, HomeLinkUi.clip(font, card + " / " + need.recipe(), width - 65), 38, y + 17, HomeLinkTheme.MUTED, false);
                boolean fresh = System.currentTimeMillis() - received <= 2500;
                var state = fresh ? need.state() : fr.lkdm.homelink.tasks.stock.AvailabilityState.UNVERIFIED;
                String sources = Component.translatable("screen.homelink_tasks.sources", fresh ? need.fromInventory() : 0,
                        fresh ? need.fromStock() : 0, need.quantity()).getString();
                graphics.drawString(font, HomeLinkUi.clip(font, TaskAvailabilityStyle.symbol(state) + " " + sources, width - 65),
                        38, y + 28, TaskAvailabilityStyle.colour(state), false);
                if (mouseX >= 12 && mouseX < width - 12 && mouseY >= y && mouseY < y + 40) {
                    var details = new java.util.ArrayList<Component>();
                    details.add(TaskAvailabilityStyle.label(state, snapshot.storageConfigured()));
                    if (fresh && need.fromStock() > 0 && snapshot.access() == fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY)
                        details.add(Component.translatable("screen.homelink_tasks.read_only"));
                    details.add(Component.translatable("screen.homelink_tasks.reusable_hint"));
                    graphics.renderComponentTooltip(font, details, mouseX, mouseY);
                }
            }
            if (snapshot.incomplete()) graphics.drawString(font, Component.translatable("screen.homelink_tasks.project_plan_partial"), 112, height - 24, TaskAvailabilityStyle.IN_STORAGE, false);
        }
        if (System.currentTimeMillis() >= nextRequest) {
            nextRequest = System.currentTimeMillis() + 1500;
            PacketDistributor.sendToServer(new ProjectPackets.Request(board));
        }
        super.renderContent(graphics, mouseX, mouseY, partialTick);
    }
}
