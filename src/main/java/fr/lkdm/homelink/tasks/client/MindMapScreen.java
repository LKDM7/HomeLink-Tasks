package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import com.mojang.blaze3d.vertex.PoseStack;
import fr.lkdm.homelink.tasks.network.BoardView;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The mind map view of the same project.
 *
 * <p>These are not two databases: every node here is the same card the Kanban shows, so
 * a rename, an assignment or a credited batch appears in both views at once.</p>
 *
 * <p>Parent links and dependencies are drawn differently, because they mean different
 * things: a parent holds a subtask, a dependency waits for another card. A card waiting
 * on an unfinished dependency gets a badge, not a column of its own.</p>
 */
public final class MindMapScreen extends TaskScreen {
    private static final int NODE_WIDTH = 96;
    private static final int NODE_HEIGHT = 30;
    private static final float MIN_ZOOM = 0.5F;
    private static final float MAX_ZOOM = 2.0F;

    private final UUID boardId;
    private float zoom = 1.0F;
    private int panX;
    private int panY;
    private UUID dragged;
    private boolean panning;
    private float nodeDeltaX;
    private float nodeDeltaY;
    private long dragRevision;
    private boolean positioned;

    /** Opens the mind map of one board.
     * @param boardId board to show
     */
    public MindMapScreen(UUID boardId) {
        super(Component.translatable("screen.homelink_tasks.mind_map"));
        this.boardId = boardId;
    }

    @Override protected void initContent() {
        if (!positioned) { recenter(); positioned = true; }
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.project_materials"),
                button -> minecraft.setScreen(new ProjectMaterialsScreen(boardId))).bounds(106, 30, Math.max(56, Math.min(140, width - 216)), HomeLinkTheme.CONTROL_HEIGHT).build());
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.board"),
                        button -> BoardScreen.openOrRefresh()).bounds(10, 30, 90, HomeLinkTheme.CONTROL_HEIGHT).build());
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.recenter"),
                        button -> recenter()).bounds(width - 100, 30, 90, HomeLinkTheme.CONTROL_HEIGHT).build());
    }

    private void recenter() {
        var cards = ClientTaskState.board().map(board -> board.cards().stream().filter(card -> !card.archived()).toList()).orElse(java.util.List.of());
        if (cards.isEmpty()) { panX = 0; panY = 22; zoom = 1; return; }
        int minX = cards.stream().mapToInt(CardView::nodeX).min().orElse(0);
        int minY = cards.stream().mapToInt(CardView::nodeY).min().orElse(0);
        int maxX = cards.stream().mapToInt(CardView::nodeX).max().orElse(0) + NODE_WIDTH;
        int maxY = cards.stream().mapToInt(CardView::nodeY).max().orElse(0) + NODE_HEIGHT;
        zoom = Math.clamp(Math.min((width - 32F) / (maxX - minX), (height - 104F) / (maxY - minY)), MIN_ZOOM, 1F);
        panX = Math.round(-(minX + maxX) * .5F * zoom);
        panY = 22 - Math.round((minY + maxY) * .5F * zoom);
    }
    @Override public void onClose() { BoardScreen.openOrRefresh(); }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        Optional<BoardView> board = ClientTaskState.board().filter(open -> open.id().equals(boardId));
        if (board.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.homelink_tasks.empty_board"),
                    width / 2, height / 2, HomeLinkTheme.MUTED);
            super.renderContent(graphics, mouseX, mouseY, partialTick);
            return;
        }
        PoseStack pose = graphics.pose();
        graphics.enableScissor(8, 66, width - 8, height - 22);
        pose.pushPose();
        pose.translate(width / 2.0F + panX, height / 2.0F + panY, 0);
        pose.scale(zoom, zoom, 1.0F);
        for (CardView card : board.get().cards()) {
            if (card.archived()) continue;
            renderLinks(graphics, board.get(), card);
        }
        for (CardView card : board.get().cards()) {
            if (card.archived()) continue;
            renderNode(graphics, board.get(), card);
        }
        pose.popPose();
        graphics.disableScissor();
        graphics.drawString(font, HomeLinkUi.clip(font, board.get().title(), width - 24), 12, 56, HomeLinkTheme.TEXT, false);
        super.renderContent(graphics, mouseX, mouseY, partialTick);
        nodeAt(mouseX, mouseY).filter(card -> waiting(board.get(), card)).ifPresent(card ->
                graphics.renderTooltip(font, Component.translatable("badge.homelink_tasks.waiting"), mouseX, mouseY));
    }

    private void renderLinks(GuiGraphics graphics, BoardView board, CardView card) {
        int x = card.nodeX() + NODE_WIDTH / 2;
        int y = card.nodeY() + NODE_HEIGHT / 2;
        // A parent link is solid; a dependency is drawn thinner so the two are never confused.
        card.parent().flatMap(parent -> find(board, parent)).ifPresent(parent ->
                line(graphics, x, y, parent.nodeX() + NODE_WIDTH / 2, parent.nodeY() + NODE_HEIGHT / 2,
                        HomeLinkTheme.LINE, 2));
        for (UUID dependency : card.dependencies()) {
            find(board, dependency).ifPresent(other ->
                    line(graphics, x, y, other.nodeX() + NODE_WIDTH / 2, other.nodeY() + NODE_HEIGHT / 2,
                            HomeLinkTheme.ACCENT, 1));
        }
    }

    private static void line(GuiGraphics graphics, int fromX, int fromY, int toX, int toY, int colour, int thickness) {
        // Two bounded draw calls per relationship, even for nodes at opposite coordinate limits.
        int middle = fromX + (toX - fromX) / 2;
        graphics.fill(Math.min(fromX, middle), fromY, Math.max(fromX, middle) + thickness, fromY + thickness, colour);
        graphics.fill(middle, Math.min(fromY, toY), middle + thickness, Math.max(fromY, toY) + thickness, colour);
        graphics.fill(Math.min(middle, toX), toY, Math.max(middle, toX) + thickness, toY + thickness, colour);
    }

    private void renderNode(GuiGraphics graphics, BoardView board, CardView card) {
        int x = card.nodeX() + (card.id().equals(dragged) ? (int) nodeDeltaX : 0);
        int y = card.nodeY() + (card.id().equals(dragged) ? (int) nodeDeltaY : 0);
        graphics.fill(x, y, x + NODE_WIDTH, y + NODE_HEIGHT, HomeLinkTheme.SURFACE);
        int border = card.status() == TaskStatus.DONE ? TaskAvailabilityStyle.READY : HomeLinkTheme.LINE;
        graphics.fill(x, y, x + NODE_WIDTH, y + 1, border);
        graphics.fill(x, y + NODE_HEIGHT - 1, x + NODE_WIDTH, y + NODE_HEIGHT, border);
        int textX = x + 4;
        if (card.objective().isPresent()) {
            graphics.renderItem(card.objective().get().target(), x + 3, y + 3);
            textX = x + 22;
        }
        graphics.drawString(font, HomeLinkUi.clip(font, card.title(), NODE_WIDTH - (textX - x) - 4),
                textX, y + 4, HomeLinkTheme.TEXT, false);
        if (card.type() == TaskType.CRAFT && card.objective().isPresent()) {
            var objective = card.objective().get();
            graphics.drawString(font, HomeLinkUi.clip(font, objective.completedQuantity() + " / " + objective.targetQuantity(), NODE_WIDTH - (textX - x) - 14),
                    textX, y + 16, HomeLinkTheme.MUTED, false);
        }
        if (waiting(board, card)) {
            // Waiting is a badge, never a fourth column, and it never rejects a real batch.
            graphics.drawString(font, "!", x + NODE_WIDTH - 8, y + 16, TaskAvailabilityStyle.IN_STORAGE, false);
        }
    }

    private static boolean waiting(BoardView board, CardView card) {
        for (UUID dependency : card.dependencies()) {
            Optional<CardView> other = find(board, dependency);
            if (other.isPresent() && !other.get().archived() && other.get().status() != TaskStatus.DONE) return true;
        }
        return false;
    }

    private static Optional<CardView> find(BoardView board, UUID card) {
        return board.cards().stream().filter(view -> view.id().equals(card)).findFirst();
    }

    @Override public boolean mouseClickedContent(double mouseX, double mouseY, int button) {
        if (super.mouseClickedContent(mouseX, mouseY, button)) return true;
        if (mouseY < 66 || mouseY > height - 22) return false;
        Optional<CardView> hit = nodeAt(mouseX, mouseY);
        if (hit.isEmpty()) {
            panning = true;
            return true;
        }
        if (button == 1) {
            minecraft.setScreen(new CardDetailScreen(boardId, hit.get().id()));
            return true;
        }
        if (!ClientCardPermissions.editor()) {
            minecraft.setScreen(new CardDetailScreen(boardId, hit.get().id()));
            return true;
        }
        dragged = hit.get().id();
        dragRevision = hit.get().revision();
        nodeDeltaX = 0; nodeDeltaY = 0;
        return true;
    }

    @Override public boolean mouseDraggedContent(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (dragged != null) {
            nodeDeltaX += (float) (deltaX / zoom);
            nodeDeltaY += (float) (deltaY / zoom);
            return true;
        }
        if (panning) {
            panX += (int) deltaX;
            panY += (int) deltaY;
            return true;
        }
        return super.mouseDraggedContent(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override public boolean mouseReleasedContent(double mouseX, double mouseY, int button) {
        if (dragged != null) {
            var card = ClientTaskState.card(dragged).orElse(null);
            if (card != null && (Math.abs(nodeDeltaX) > 1 || Math.abs(nodeDeltaY) > 1)) {
                TaskClientNetwork.moveNode(boardId, dragged, Math.clamp(card.nodeX() + (int) nodeDeltaX, -32767, 32767),
                        Math.clamp(card.nodeY() + (int) nodeDeltaY, -32767, 32767), dragRevision);
            } else if (card != null) minecraft.setScreen(new CardDetailScreen(boardId, card.id()));
        }
        dragged = null;
        panning = false;
        return super.mouseReleasedContent(mouseX, mouseY, button);
    }

    @Override public boolean mouseScrolledContent(double mouseX, double mouseY, double scrollX, double scrollY) {
        float next = Math.clamp(zoom + (float) scrollY * 0.1F, MIN_ZOOM, MAX_ZOOM);
        panX = (int) (mouseX - width / 2.0 - (mouseX - width / 2.0 - panX) * next / zoom);
        panY = (int) (mouseY - height / 2.0 - (mouseY - height / 2.0 - panY) * next / zoom);
        zoom = next;
        return true;
    }

    private Optional<CardView> nodeAt(double mouseX, double mouseY) {
        double worldX = (mouseX - width / 2.0 - panX) / zoom;
        double worldY = (mouseY - height / 2.0 - panY) / zoom;
        return ClientTaskState.board().filter(board -> board.id().equals(boardId))
                .flatMap(board -> board.cards().stream()
                        .filter(card -> !card.archived())
                        .filter(card -> worldX >= card.nodeX() && worldX <= card.nodeX() + NODE_WIDTH
                                && worldY >= card.nodeY() && worldY <= card.nodeY() + NODE_HEIGHT)
                        .reduce((first, second) -> second));
    }

    @Override public boolean isPauseScreen() { return false; }
}
