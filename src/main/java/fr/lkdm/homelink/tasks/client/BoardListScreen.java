package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The welcome screen: the projects this player may open.
 *
 * <p>Each row says how many cards it holds, how far along it is and how many members it
 * has. When the list is empty the screen explains what to do rather than showing an
 * empty box.</p>
 */
public final class BoardListScreen extends TaskScreen {
    private static final int ROW_HEIGHT = 34;
    private int left() { return Math.max(12, width / 2 - 190); }
    private int wide() { return Math.min(380, width - 24); }
    private int rows() { return Math.max(1, (height - 116) / ROW_HEIGHT); }

    private EditBox search;
    private EditBox newTitle;
    private int scroll;

    /** Opens the board list. */
    public BoardListScreen() { super(Component.translatable("screen.homelink_tasks.boards")); }

    @Override protected void initContent() {
        String oldSearch = search == null ? "" : search.getValue();
        String oldTitle = newTitle == null ? "" : newTitle.getValue();
        int left = left();
        ClientTaskState.screen().ifPresent(pos -> addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.screen_network"),
                button -> minecraft.setScreen(new ScreenNetworkScreen(this, pos))).bounds(left + wide() - 144, 24, 144, HomeLinkTheme.CONTROL_HEIGHT).build()));
        search = new EditBox(font, left, 48, wide(), 18, Component.translatable("screen.homelink_tasks.search"));
        search.setMaxLength(TaskBoard.MAX_TITLE);
        search.setHint(Component.translatable("screen.homelink_tasks.search"));
        search.setValue(oldSearch);
        search.setResponder(ignored -> scroll = 0);
        addRenderableWidget(search);

        newTitle = new EditBox(font, left, height - 26, wide() - 116, 18,
                Component.translatable("screen.homelink_tasks.new_board"));
        newTitle.setMaxLength(TaskBoard.MAX_TITLE);
        newTitle.setHint(Component.translatable("screen.homelink_tasks.new_board"));
        newTitle.setValue(oldTitle);
        addRenderableWidget(newTitle);
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.create"),
                button -> {
                    String title = newTitle.getValue().strip();
                    if (!title.isEmpty()) TaskClientNetwork.createBoard(title);
                }).bounds(left + wide() - 110, height - 26, 110, HomeLinkTheme.CONTROL_HEIGHT).build());
    }

    private List<TaskPackets.BoardSummary> visible() {
        String filter = search == null ? "" : search.getValue().strip().toLowerCase(Locale.ROOT);
        return ClientTaskState.boards().stream()
                .filter(summary -> filter.isEmpty() || summary.title().toLowerCase(Locale.ROOT).contains(filter))
                .toList();
    }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = left();
        graphics.drawString(font, title, left, 32, HomeLinkTheme.TEXT, false);
        List<TaskPackets.BoardSummary> boards = visible();
        if (boards.isEmpty()) {
            graphics.drawWordWrap(font, Component.translatable(ClientTaskState.boards().isEmpty()
                            ? "screen.homelink_tasks.no_boards" : "screen.homelink_tasks.no_match"),
                    left, 76, wide(), HomeLinkTheme.MUTED);
            super.renderContent(graphics, mouseX, mouseY, partialTick);
            return;
        }
        scroll = Math.clamp(scroll, 0, Math.max(0, boards.size() - rows()));
        int y = 74;
        for (int index = scroll; index < Math.min(boards.size(), scroll + rows()); index++) {
            TaskPackets.BoardSummary summary = boards.get(index);
            boolean hovered = mouseX >= left && mouseX <= left + wide() && mouseY >= y && mouseY <= y + ROW_HEIGHT - 2;
            graphics.fill(left, y, left + wide(), y + ROW_HEIGHT - 2,
                    hovered ? HomeLinkTheme.HOVER : HomeLinkTheme.SURFACE);
            graphics.drawString(font, HomeLinkUi.clip(font, summary.title(), wide() - 96), left + 6, y + 3, HomeLinkTheme.TEXT, false);
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.board_summary",
                            summary.doneCount(), summary.cardCount(), summary.memberCount()),
                    left + 6, y + 15, HomeLinkTheme.MUTED, false);
            HomeLinkUi.progressBar(graphics, left + 6, y + 28, wide() - 12, 2,
                    (double) summary.doneCount() / Math.max(1, summary.cardCount()), HomeLinkTheme.ACCENT);
            graphics.drawString(font, Component.translatable("role.homelink_tasks."
                            + summary.role().name().toLowerCase(Locale.ROOT)),
                    left + wide() - 84, y + 7, HomeLinkTheme.ACCENT, false);
            y += ROW_HEIGHT;
        }
        super.renderContent(graphics, mouseX, mouseY, partialTick);
    }

    @Override public boolean mouseClickedContent(double mouseX, double mouseY, int button) {
        if (super.mouseClickedContent(mouseX, mouseY, button)) return true;
        int left = left();
        if (mouseX < left || mouseX > left + wide() || mouseY >= 74 + rows() * ROW_HEIGHT) return false;
        List<TaskPackets.BoardSummary> boards = visible();
        int index = scroll + (int) ((mouseY - 74) / ROW_HEIGHT);
        if (mouseY < 74 || index < 0 || index >= boards.size()) return false;
        TaskClientNetwork.board(TaskPackets.BoardCommand.OPEN, boards.get(index).id(), "", null,
                boards.get(index).role(), false, 0L);
        return true;
    }

    @Override public boolean mouseScrolledContent(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.clamp(scroll - (int) scrollY, 0, Math.max(0, visible().size() - rows()));
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { if (ClientTaskState.board().isPresent()) BoardScreen.openOrRefresh(); else super.onClose(); }
    @Override public void dataChanged() { clearWidgets(); initContent(); }
}
