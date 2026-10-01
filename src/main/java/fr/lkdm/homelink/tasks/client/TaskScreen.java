package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.network.TaskPackets;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Shared industrial shell, close lifecycle and deliberately immediate interactions. */
public abstract class TaskScreen extends Screen {
    private static net.minecraft.network.chat.Component notice;
    private static long noticeUntil;
    public static void notice(net.minecraft.network.chat.Component message) { notice = message; noticeUntil = System.currentTimeMillis() + 8000; }
    protected TaskScreen(Component title) { super(title); }

    private int guiLeft, guiTop;
    public int guiLeft() { return guiLeft; }
    public int guiTop() { return guiTop; }

    @Override protected final void init() {
        int windowWidth = minecraft.getWindow().getGuiScaledWidth();
        int windowHeight = minecraft.getWindow().getGuiScaledHeight();
        // Same content dimensions and external bevel as Dashboard.
        width = Math.min(520, windowWidth - 16);
        height = Math.min(340, windowHeight - 16);
        guiLeft = (windowWidth - width) / 2;
        guiTop = (windowHeight - height) / 2;
        initContent();
    }

    protected void initContent() { }

    @Override protected <T extends net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> T addRenderableWidget(T widget) {
        if (widget instanceof net.minecraft.client.gui.components.EditBox input) {
            input.setHeight(18);
            input.setTextColor(TaskTheme.TEXT);
            input.setTextColorUneditable(TaskTheme.TEXT_MUTED);
        }
        return super.addRenderableWidget(widget);
    }

    @Override public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(), -20, 0x90101214);
        graphics.pose().pushPose();
        graphics.pose().translate(guiLeft, guiTop, 0);
        renderContent(graphics, mouseX - guiLeft, mouseY - guiTop, partialTick);
        graphics.pose().popPose();
    }

    @Override public final boolean mouseClicked(double x, double y, int button) { return mouseClickedContent(x - guiLeft, y - guiTop, button); }
    @Override public final boolean mouseReleased(double x, double y, int button) { return mouseReleasedContent(x - guiLeft, y - guiTop, button); }
    @Override public final boolean mouseDragged(double x, double y, int button, double dx, double dy) { return mouseDraggedContent(x - guiLeft, y - guiTop, button, dx, dy); }
    @Override public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return mouseScrolledContent(x - guiLeft, y - guiTop, horizontal, vertical); }
    @Override public void mouseMoved(double x, double y) { super.mouseMoved(x - guiLeft, y - guiTop); }
    public boolean mouseClickedContent(double x, double y, int button) { return super.mouseClicked(x, y, button); }
    public boolean mouseReleasedContent(double x, double y, int button) { return super.mouseReleased(x, y, button); }
    public boolean mouseDraggedContent(double x, double y, int button, double dx, double dy) { return super.mouseDragged(x, y, button, dx, dy); }
    public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) { return super.mouseScrolled(x, y, horizontal, vertical); }

    // Each task screen draws its shell before its content. Screen.render in 1.21
    // draws the background again; invoking it last would cover that content.
    public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (var renderable : renderables) renderable.render(graphics, mouseX, mouseY, partialTick);
        if (notice != null && System.currentTimeMillis() < noticeUntil) {
            graphics.fill(6, 5, width - 6, 24, TaskTheme.PANEL);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 1);
            graphics.drawString(font, TaskTheme.clip(font, notice.getString(), width - 28), 14, 10, TaskTheme.IN_STORAGE, false);
            graphics.pose().popPose();
            if (mouseY < 24) graphics.renderTooltip(font, font.split(notice, Math.min(300, width - 24)), mouseX, mouseY);
        }
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(-3, -3, width + 3, height + 3, -12, 0xFF141617);
        graphics.fill(-2, -2, width + 2, height + 2, -12, 0xFF6B6E70);
        graphics.fill(0, 0, width, height, -11, TaskTheme.ANTHRACITE);
        graphics.fill(0, 0, width, 29, -10, TaskTheme.PANEL);
        graphics.renderOutline(0, 0, width, height, TaskTheme.STEEL);
        graphics.fill(10, height - 29, width - 10, height - 28, TaskTheme.STEEL);
        graphics.drawString(font, "HomeLink Tasks", 14, 11, TaskTheme.TEXT, false);
        TaskTheme.screw(graphics, 4, 4);
        TaskTheme.screw(graphics, width - 8, 4);
        TaskTheme.screw(graphics, 4, height - 8);
        TaskTheme.screw(graphics, width - 8, height - 8);
    }

    public boolean readOnly() { return false; }
    public void dataChanged() { }
    public void requestFailed() { }

    @Override public void onClose() {
        if (minecraft != null && minecraft.getConnection() != null) PacketDistributor.sendToServer(
                new TaskPackets.BoardRequest(TaskPackets.BoardCommand.CLOSE, Optional.empty(), "", Optional.empty(),
                        fr.lkdm.homelink.tasks.board.BoardRole.VIEWER, false, Optional.empty(), 0));
        super.onClose();
    }

    @Override public boolean isPauseScreen() { return false; }
}
