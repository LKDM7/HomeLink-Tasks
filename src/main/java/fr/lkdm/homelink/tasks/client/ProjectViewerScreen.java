package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Personal consultation of one project. No edit or project-switch controls. */
public final class ProjectViewerScreen extends TaskScreen {
    private final UUID project;
    private int scroll;

    public ProjectViewerScreen(UUID project) {
        super(Component.translatable("screen.homelink_tasks.project_viewer"));
        this.project = project;
    }

    public static void open() {
        UUID current = ClientTaskState.board().map(board -> board.id()).orElseGet(() ->
                ClientTaskState.pinned().stream().filter(pin -> ClientTaskState.tracking(pin.card()))
                        .findFirst().or(() -> ClientTaskState.pinned().stream().findFirst())
                        .map(TaskPackets.PinnedView::board).orElse(null));
        ClientTaskState.beginConsultation();
        Minecraft.getInstance().setScreen(new ProjectViewerScreen(current));
        if (current != null) PacketDistributor.sendToServer(new TaskPackets.BoardRequest(
                TaskPackets.BoardCommand.OPEN, Optional.of(current), "", Optional.empty(),
                fr.lkdm.homelink.tasks.board.BoardRole.VIEWER, false, Optional.empty(), 0));
    }

    @Override public boolean readOnly() { return true; }
    private int left() { return Math.max(12, width / 2 - 190); }
    private int wide() { return Math.min(380, width - 24); }
    private int rows() { return Math.max(1, (height - 110) / 34); }
    private List<CardView> cards() {
        return ClientTaskState.board().filter(board -> board.id().equals(project))
                .map(board -> board.cards().stream().filter(card -> !card.archived())
                        .sorted(java.util.Comparator.comparing(CardView::status).thenComparingInt(CardView::order))
                        .toList()).orElse(List.of());
    }

    @Override protected void initContent() {
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.close"),
                ignored -> onClose()).bounds(left(), height - 26, wide(), HomeLinkTheme.CONTROL_HEIGHT).build());
        var cards = cards();
        scroll = Math.clamp(scroll, 0, Math.max(0, cards.size() - rows()));
        for (int i = scroll; i < Math.min(cards.size(), scroll + rows()); i++) {
            var card = cards.get(i);
            var button = addRenderableWidget(HomeLinkButton.builder(Component.literal(card.title()),
                    ignored -> minecraft.setScreen(new RecipeScreen(project, card.id(), true)))
                    .bounds(left(), 72 + (i - scroll) * 34, wide() - 86, 26).build());
            button.active = card.objective().isPresent();
            button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                    card.description().isBlank() ? card.title() : card.title() + "\n" + card.description())));
        }
    }

    @Override public void dataChanged() { clearWidgets(); initContent(); }
    @Override public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) {
        scroll -= (int) vertical; dataChanged(); return true;
    }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, title, left(), 32, HomeLinkTheme.ACCENT, false);
        var board = ClientTaskState.board().filter(value -> value.id().equals(project)).orElse(null);
        graphics.drawString(font, HomeLinkUi.clip(font, board == null
                ? Component.translatable("screen.homelink_tasks.no_current_project").getString() : board.title(), wide()),
                left(), 49, HomeLinkTheme.TEXT, false);
        var cards = cards();
        if (board != null && cards.isEmpty()) graphics.drawString(font,
                Component.translatable("screen.homelink_tasks.empty_column"), left(), 78, HomeLinkTheme.MUTED, false);
        for (int i = scroll; i < Math.min(cards.size(), scroll + rows()); i++) {
            var card = cards.get(i);
            String progress = card.objective().map(objective -> objective.completedQuantity() + " / " + objective.targetQuantity())
                    .orElseGet(() -> Component.translatable("column.homelink_tasks." +
                            card.status().name().toLowerCase(java.util.Locale.ROOT)).getString());
            graphics.drawString(font, HomeLinkUi.clip(font, progress, 80), left() + wide() - 80,
                    81 + (i - scroll) * 34, HomeLinkTheme.MUTED, false);
        }
        super.renderContent(graphics, mouseX, mouseY, partialTick);
    }
}
