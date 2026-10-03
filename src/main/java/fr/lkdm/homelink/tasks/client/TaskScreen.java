package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkScreenLayout;
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
        var layout = HomeLinkScreenLayout.fit(windowWidth, windowHeight,
                HomeLinkTheme.DEFAULT_MAX_WIDTH, HomeLinkTheme.DEFAULT_MAX_HEIGHT);
        width = layout.width();
        height = layout.height();
        guiLeft = layout.x();
        guiTop = layout.y();
        initContent();
    }

    protected void initContent() { }

    @Override protected <T extends net.minecraft.client.gui.components.events.GuiEventListener
            & net.minecraft.client.gui.components.Renderable
            & net.minecraft.client.gui.narration.NarratableEntry> T addRenderableWidget(T widget) {
        if (widget instanceof net.minecraft.client.gui.components.EditBox input) {
            input.setHeight(HomeLinkTheme.CONTROL_HEIGHT);
            HomeLinkUi.input(input);
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
            graphics.fill(6, 5, width - 6, 24, HomeLinkTheme.HEADER);
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 1);
            graphics.drawString(font, HomeLinkUi.clip(font, notice.getString(), width - 28), 14, 10, TaskAvailabilityStyle.IN_STORAGE, false);
            graphics.pose().popPose();
            if (mouseY < 24) graphics.renderTooltip(font, font.split(notice, Math.min(300, width - 24)), mouseX, mouseY);
        }
    }

    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, -12);
        HomeLinkUi.frame(graphics, 0, 0, width, height);
        graphics.pose().popPose();
        graphics.drawString(font, "HomeLink Tasks", 14, 11, HomeLinkTheme.TEXT, false);
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
