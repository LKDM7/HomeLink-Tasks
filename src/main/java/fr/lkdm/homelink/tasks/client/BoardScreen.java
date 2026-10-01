package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.network.BoardView;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.PlanView;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The Kanban view of a project: three fixed columns and the cards in them.
 *
 * <p>Cards can be dragged between columns and reordered, and every one of those moves is
 * also reachable without a drag, through the card's own "Move to" menu, for players who
 * cannot or would rather not drag.</p>
 *
 * <p>The drag is drawn optimistically, but the server decides. A refusal arrives as a
 * notice and the board is redrawn from the state the server sent.</p>
 */
public final class BoardScreen extends TaskScreen {
    private static final int COLUMN_GAP = 6;
    private static final int CARD_HEIGHT = 52;
    private static final int CARD_GAP = 3;
    private static final int HEADER = 84;
    private static final int FOOTER = 26;

    private final List<Column> columns = new ArrayList<>();
    private UUID dragged;
    private UUID selected;
    private int dragX;
    private int dragY;
    private boolean dragging;
    private boolean consumedCardClick;
    private long lastPlanRequest;
    private int planCursor;
    private TaskStatus compactStatus;
    private boolean compact() { return width < 480; }
    private int header() { return compact() ? 98 : HEADER; }
    private final java.util.Map<TaskStatus, Integer> scrolls = new java.util.EnumMap<>(TaskStatus.class);

    private BoardScreen() { super(Component.translatable("screen.homelink_tasks.board")); }

    /** Opens the board screen, or refreshes it when it is already open. */
    public static void openOrRefresh() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof BoardScreen open) {
            open.dataChanged();
            return;
        }
        if (ClientTaskState.board().isEmpty() && ClientTaskState.boards().isEmpty()) return;
        client.setScreen(new BoardScreen());
    }

    @Override public void dataChanged() { clearWidgets(); initContent(); }

    @Override protected void initContent() {
        if (compactStatus == null) compactStatus = ClientTaskState.board().flatMap(board -> board.cards().stream().filter(card -> !card.archived()).map(CardView::status).findFirst()).orElse(TaskStatus.TODO);
        rebuild();
        Optional<BoardView> board = ClientTaskState.board();
        int addWidth = Math.min(140, (width - 80) / 2);
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.boards"),
                button -> minecraft.setScreen(new BoardListScreen())).bounds(10, 30, 82, 22).build());
        if (board.isPresent() && board.get().viewerRole().atLeast(BoardRole.EDITOR))
            addRenderableWidget(TaskButton.primary(Component.translatable("screen.homelink_tasks.new_card"),
                    button -> minecraft.setScreen(new CardEditorScreen(board.get().id(), null)))
                    .bounds(width - addWidth - 46, 30, addWidth, 22).build());
        addRenderableWidget(TaskButton.builder(Component.literal("..."), button -> {
            TaskMenuScreen menu = new TaskMenuScreen(this);
            board.ifPresent(open -> {
                menu.action("manage", () -> minecraft.setScreen(new BoardSettingsScreen(open.id())));
                menu.action("mind_map", () -> minecraft.setScreen(new MindMapScreen(open.id())));
                menu.action("project_materials", () -> minecraft.setScreen(new ProjectMaterialsScreen(open.id())));
                if (ClientTaskState.screen().isPresent()) menu.action("show_display", () -> TaskClientNetwork.board(
                        TaskPackets.BoardCommand.SELECT_DISPLAY, open.id(), "", null, open.viewerRole(), false, open.revision()));
            });
            minecraft.setScreen(menu);
        }).bounds(width - 40, 30, 30, 22).build()).setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.homelink_tasks.more")));
        if (compact()) for (TaskStatus status : TaskStatus.values()) {
            long count = board.map(open -> open.cards().stream().filter(card -> !card.archived() && card.status() == status).count()).orElse(0L);
            int w = (width - 28) / 3;
            addRenderableWidget(TaskButton.tab(Component.translatable("column.homelink_tasks." + status.name().toLowerCase(java.util.Locale.ROOT)).append(" (" + count + ")"), ignored -> {
                compactStatus = status; dataChanged();
            }, compactStatus == status).bounds(10 + status.ordinal() * (w + 4), 74, w, 20).build());
        }
    }
    /**
     * Attaching or detaching the board's network, for its owner.
     *
     * <p>Only offered when the board was opened from a screen, because the network used is
     * that screen's own. The server still requires the owner's HomeCore MANAGE_NETWORK
     * right on it: this button asks, it does not grant.</p>
     */
    private void rebuild() {
        columns.clear();
        Optional<BoardView> board = ClientTaskState.board();
        if (board.isEmpty()) return;
        int usable = width - 20 - 2 * COLUMN_GAP;
        int columnWidth = compact() ? width - 20 : Math.max(1, usable / 3);
        int x = 10;
        for (TaskStatus status : TaskStatus.values()) {
            if (compact() && status != compactStatus) continue;
            List<CardView> cards = new ArrayList<>(board.get().cards().stream()
                    .filter(card -> !card.archived() && card.status() == status)
                    .sorted(Comparator.comparingInt(CardView::order).thenComparing(CardView::title))
                    .toList());
            columns.add(new Column(status, x, columnWidth, cards));
            x += columnWidth + COLUMN_GAP;
        }
    }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        Optional<BoardView> board = ClientTaskState.board();
        if (board.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.homelink_tasks.empty_board"),
                    width / 2, height / 2, TaskTheme.TEXT_MUTED);
            super.renderContent(graphics, mouseX, mouseY, partialTick);
            return;
        }
        graphics.drawString(font, TaskTheme.clip(font, board.get().title(), width - 20), 10, 59, TaskTheme.TEXT, false);
        for (Column column : columns) renderColumn(graphics, column, mouseX, mouseY);
        if (dragging && dragged != null) {
            ClientTaskState.card(dragged).ifPresent(card ->
                    renderCard(graphics, card, dragX - 40, dragY - CARD_HEIGHT / 2, 100, true));
        }
        graphics.drawString(font, TaskTheme.clip(font, Component.translatable("screen.homelink_tasks.move_hint").getString(), width - 20),
                10, height - 18, TaskTheme.TEXT_MUTED, false);
        super.renderContent(graphics, mouseX, mouseY, partialTick);
        if (!dragging) hit(mouseX, mouseY).ifPresent(hit -> {
            if (trashHit(hit, mouseX, mouseY))
                graphics.renderTooltip(font, Component.translatable("screen.homelink_tasks.delete_card"), mouseX, mouseY);
            else if (font.width(hit.card().title()) > hit.width() - 46)
                graphics.renderTooltip(font, Component.literal(hit.card().title()), mouseX, mouseY);
        });
        requestVisiblePlans();
    }

    private void renderColumn(GuiGraphics graphics, Column column, int mouseX, int mouseY) {
        int top = header();
        int bottom = height - FOOTER;
        TaskTheme.panel(graphics, column.x, top, column.width, bottom - top);
        graphics.fill(column.x, top, column.x + column.width, top + 1, TaskTheme.STEEL);
        Component title = Component.translatable("column.homelink_tasks."
                + column.status.name().toLowerCase(java.util.Locale.ROOT));
        if (!compact()) {
            graphics.drawString(font, title, column.x + 6, top - 12, TaskTheme.TEXT, false);
            graphics.drawString(font, Integer.toString(column.cards.size()), column.x + column.width - 14, top - 12, TaskTheme.TEXT_MUTED, false);
        }
        int y = top + 4;
        int start = Math.clamp(scrolls.getOrDefault(column.status, 0), 0, Math.max(0, column.cards.size() - visibleRows()));
        scrolls.put(column.status, start);
        for (CardView card : column.cards.stream().skip(start).toList()) {
            if (y + CARD_HEIGHT > bottom) break;
            if (!card.id().equals(dragged) || !dragging) {
                boolean hovered = mouseX >= column.x && mouseX <= column.x + column.width
                        && mouseY >= y && mouseY <= y + CARD_HEIGHT;
                renderCard(graphics, card, column.x + 3, y, column.width - 6, hovered);
            }
            y += CARD_HEIGHT + CARD_GAP;
        }
        if (column.cards.size() > visibleRows()) {
            graphics.drawString(font, (start + 1) + " / " + column.cards.size(), column.x + 4, bottom - 10,
                    TaskTheme.TEXT_MUTED, false);
        }
        if (column.cards.isEmpty()) {
            graphics.drawString(font, TaskTheme.clip(font, Component.translatable("screen.homelink_tasks.empty_column").getString(), column.width - 12),
                    column.x + 6, top + 10, TaskTheme.TEXT_MUTED, false);
        }
    }

    private void renderCard(GuiGraphics graphics, CardView card, int x, int y, int cardWidth, boolean hovered) {
        graphics.fill(x, y, x + cardWidth, y + CARD_HEIGHT, hovered ? TaskTheme.SURFACE_HOVER : TaskTheme.PANEL);
        int accent = switch (card.priority()) {
            case URGENT -> TaskTheme.MISSING;
            case HIGH -> TaskTheme.COPPER;
            case NORMAL -> TaskTheme.STEEL;
            case LOW -> TaskTheme.TEXT_MUTED;
        };
        graphics.fill(x, y, x + 2, y + CARD_HEIGHT, accent);
        int textX = x + 6;
        if (card.objective().isPresent()) {
            graphics.renderItem(card.objective().get().target(), x + 5, y + 4);
            textX = x + 25;
        }
        graphics.drawString(font, TaskTheme.clip(font, card.title(), cardWidth - (textX - x) - 16),
                textX, y + 5, TaskTheme.TEXT, false);
        // The star is personal: it pins for this player only and changes no allocation.
        boolean isPinned = ClientTaskState.pinned().stream().anyMatch(view -> view.card().equals(card.id()));
        graphics.drawString(font, isPinned ? "★" : "☆",
                x + cardWidth - 12, y + 5, isPinned ? TaskTheme.COPPER : TaskTheme.TEXT_MUTED, false);
        renderSubtitle(graphics, card, textX, y + 18, cardWidth - (textX - x) - (canDeleteCompleted(card) ? 22 : 6));
        if (canDeleteCompleted(card)) {
            int tx = x + cardWidth - 15, ty = y + 24;
            graphics.fill(tx + 3, ty, tx + 7, ty + 1, TaskTheme.MISSING);
            graphics.fill(tx + 1, ty + 2, tx + 9, ty + 3, TaskTheme.MISSING);
            graphics.fill(tx + 2, ty + 4, tx + 8, ty + 11, TaskTheme.MISSING);
            graphics.fill(tx + 3, ty + 5, tx + 4, ty + 9, TaskTheme.SURFACE);
            graphics.fill(tx + 6, ty + 5, tx + 7, ty + 9, TaskTheme.SURFACE);
        }
        if (card.objective().isPresent()) {
            var objective = card.objective().get();
            int barWidth = cardWidth - 12;
            graphics.fill(x + 6, y + 43, x + 6 + barWidth, y + 46, TaskTheme.SURFACE);
            graphics.fill(x + 6, y + 43, x + 6 + (int)(barWidth * Math.min(1.0, (double)objective.completedQuantity() / Math.max(1, objective.targetQuantity()))), y + 46, TaskTheme.COPPER);
        }
    }
    private void renderSubtitle(GuiGraphics graphics, CardView card, int x, int y, int available) {
        if (card.type() == TaskType.CRAFT && card.objective().isPresent()) {
            var objective = card.objective().get();
            String progress = objective.completedQuantity() + " / " + objective.targetQuantity();
            graphics.drawString(font, progress, x, y, TaskTheme.TEXT_MUTED, false);
            Optional<PlanView> plan = ClientTaskState.plan(card.id());
            if (objective.remaining() == 0) {
                graphics.drawString(font, Component.translatable("screen.homelink_tasks.plan_finished"), x, y + 10, TaskTheme.TEXT_MUTED, false);
            } else if (plan.isPresent()) {
                int colour = TaskTheme.colour(plan.get().state());
                String summary = TaskTheme.symbol(plan.get().state()) + " "
                        + TaskTheme.label(plan.get().state(), plan.get().storageConfigured()).getString();
                graphics.drawString(font, TaskTheme.clip(font, summary, Math.max(0, available)),
                        x, y + 10, colour, false);
            }
            return;
        }
        if (!card.assignees().isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.assignees",
                    card.assignees().size()), x, y, TaskTheme.TEXT_MUTED, false);
        }
    }

    private void requestVisiblePlans() {
        long now = System.currentTimeMillis();
        // The server throttles too, but there is no reason to ask it on every frame.
        if (now - lastPlanRequest < 650L) return;
        lastPlanRequest = now;
        ClientTaskState.board().ifPresent(board -> {
            List<CardView> visible = columns.stream().flatMap(column -> column.cards.stream()
                    .skip(scrolls.getOrDefault(column.status, 0)).limit(visibleRows()))
                    .filter(card -> card.type() == TaskType.CRAFT).toList();
            if (visible.isEmpty()) return;
            CardView card = visible.get(Math.floorMod(planCursor++, visible.size()));
            TaskClientNetwork.personal(TaskPackets.PersonalCommand.REQUEST_PLAN, board.id(), card.id());
        });
    }

    @Override public boolean mouseClickedContent(double mouseX, double mouseY, int button) {
        consumedCardClick = false;
        if (super.mouseClickedContent(mouseX, mouseY, button)) return true;
        Optional<Hit> hit = hit(mouseX, mouseY);
        if (hit.isEmpty()) return false;
        CardView card = hit.get().card();
        selected = card.id();
        if (button == 0 && trashHit(hit.get(), mouseX, mouseY)) {
            consumedCardClick = true;
            dragged = null;
            dragging = false;
            ClientTaskState.board().ifPresent(board -> TaskClientNetwork.card(TaskPackets.CardCommand.DELETE, board.id(), card.id(), "", true));
            return true;
        }
        if (mouseX >= hit.get().x() + hit.get().width() - 14 && mouseY < hit.get().y() + 18) {
            togglePin(card);
            return true;
        }
        if (button == 1) {
            ClientTaskState.board().ifPresent(board ->
                    minecraft.setScreen(new CardDetailScreen(board.id(), card.id())));
            return true;
        }
        if (!ClientCardPermissions.canMove(card)) return true;
        dragged = card.id();
        dragX = (int) mouseX;
        dragY = (int) mouseY;
        return true;
    }

    @Override public boolean mouseDraggedContent(double mouseX, double mouseY, int button, double dragXDelta,
                                          double dragYDelta) {
        if (dragged != null) {
            dragging = true;
            dragX = (int) mouseX;
            dragY = (int) mouseY;
            return true;
        }
        return super.mouseDraggedContent(mouseX, mouseY, button, dragXDelta, dragYDelta);
    }

    @Override public boolean mouseReleasedContent(double mouseX, double mouseY, int button) {
        if (consumedCardClick) { consumedCardClick = false; return true; }
        if (dragged != null && dragging) {
            Column target = columnAt(mouseX);
            UUID moved = dragged;
            dragged = null;
            dragging = false;
            if (compact() && mouseY >= 74 && mouseY < 94) {
                int index = ((int)mouseX - 10) / (((width - 28) / 3) + 4);
                if (index >= 0 && index < TaskStatus.values().length) {
                    TaskStatus status = TaskStatus.values()[index];
                    var view = ClientTaskState.card(moved).orElse(null);
                    if (view != null && ClientCardPermissions.canMoveTo(view, status)) {
                        ClientTaskState.board().ifPresent(board -> TaskClientNetwork.move(board.id(), moved, status, 0));
                        compactStatus = status; dataChanged();
                    }
                }
                return true;
            }
            if (target != null) {
                var view = ClientTaskState.card(moved).orElse(null);
                if (view == null || !ClientCardPermissions.canMoveTo(view, target.status)) return true;
                ClientTaskState.board().ifPresent(board ->
                        TaskClientNetwork.move(board.id(), moved, target.status, orderAt(target, mouseY)));
            }
            return true;
        }
        dragged = null;
        dragging = false;
        if (super.mouseReleasedContent(mouseX, mouseY, button)) return true;
        Optional<Hit> hit = hit(mouseX, mouseY);
        Optional<BoardView> board = ClientTaskState.board();
        if (hit.isPresent() && board.isPresent() && button == 0
                && !(mouseX >= hit.get().x() + hit.get().width() - 14 && mouseY < hit.get().y() + 18)) {
            minecraft.setScreen(new CardDetailScreen(board.get().id(), hit.get().card().id()));
            return true;
        }
        return false;
    }

    /**
     * Keyboard alternative to dragging.
     *
     * <p>Left and right move the selected card between columns; the card's own detail
     * screen offers the same through a "Move to" menu.</p>
     */
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (selected != null && ClientTaskState.board().isPresent()) {
            Optional<CardView> card = ClientTaskState.card(selected);
            if (card.isPresent() && (keyCode == 263 || keyCode == 262)) {
                TaskStatus[] statuses = TaskStatus.values();
                int index = Math.clamp(card.get().status().ordinal() + (keyCode == 262 ? 1 : -1),
                        0, statuses.length - 1);
                if (!ClientCardPermissions.canMoveTo(card.get(), statuses[index])) return true;
                TaskClientNetwork.move(ClientTaskState.board().orElseThrow().id(), selected,
                        statuses[index], card.get().order());
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void togglePin(CardView card) {
        boolean isPinned = ClientTaskState.pinned().stream().anyMatch(view -> view.card().equals(card.id()));
        ClientTaskState.board().ifPresent(board -> TaskClientNetwork.personal(
                isPinned ? TaskPackets.PersonalCommand.UNPIN : TaskPackets.PersonalCommand.PIN,
                board.id(), card.id()));
    }

    private boolean canDeleteCompleted(CardView card) {
        return card.status() == TaskStatus.DONE && !card.archived() && ClientCardPermissions.editor();
    }

    private boolean trashHit(Hit hit, double x, double y) {
        return canDeleteCompleted(hit.card()) && x >= hit.x() + hit.width() - 19
                && x < hit.x() + hit.width() - 1 && y >= hit.y() + 20 && y < hit.y() + 39;
    }

    private Column columnAt(double mouseX) {
        for (Column column : columns) {
            if (mouseX >= column.x && mouseX <= column.x + column.width) return column;
        }
        return null;
    }

    private int orderAt(Column column, double mouseY) {
        int index = scrolls.getOrDefault(column.status, 0) + (int) ((mouseY - header() - 4) / (CARD_HEIGHT + CARD_GAP));
        return Math.clamp(index, 0, Math.max(0, column.cards.size()));
    }

    private Optional<Hit> hit(double mouseX, double mouseY) {
        if (mouseY < header() || mouseY >= height - FOOTER) return Optional.empty();
        for (Column column : columns) {
            if (mouseX < column.x || mouseX > column.x + column.width) continue;
            int y = header() + 4;
            for (CardView card : column.cards.stream().skip(scrolls.getOrDefault(column.status, 0)).limit(visibleRows()).toList()) {
                if (mouseY >= y && mouseY <= y + CARD_HEIGHT) {
                    return Optional.of(new Hit(card, column.x + 3, y, column.width - 6));
                }
                y += CARD_HEIGHT + CARD_GAP;
            }
        }
        return Optional.empty();
    }

    private int visibleRows() { return Math.max(1, (height - header() - FOOTER - 4 + CARD_GAP) / (CARD_HEIGHT + CARD_GAP)); }

    @Override public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) {
        Column column = columnAt(x);
        if (column == null || y < header()) return false;
        scrolls.put(column.status, Math.clamp(scrolls.getOrDefault(column.status, 0) - (int) vertical,
                0, Math.max(0, column.cards.size() - visibleRows())));
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }

    private record Hit(CardView card, int x, int y, int width) { }

    private record Column(TaskStatus status, int x, int width, List<CardView> cards) { }
}
