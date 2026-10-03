package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public final class ObjectiveSettingsScreen extends TaskScreen {
    private final UUID board, card;
    private EditBox quantity;
    public ObjectiveSettingsScreen(UUID board, UUID card) { super(Component.translatable("screen.homelink_tasks.craft_settings")); this.board = board; this.card = card; }
    private CardView.ObjectiveView objective() { return ClientTaskState.card(card).flatMap(CardView::objective).orElse(null); }
    @Override protected void initContent() {
        var objective = objective(); if (objective == null) return;
        int left = Math.max(12, width / 2 - 150), wide = Math.min(300, width - 24);
        boolean editor = ClientTaskState.board().map(board -> board.viewerRole().atLeast(fr.lkdm.homelink.tasks.board.BoardRole.EDITOR)).orElse(false);
        boolean quantityEditable = editor && objective.remaining() > 0
                && !ClientCardPermissions.hasExpandedIngredients(ClientTaskState.card(card).orElseThrow());
        quantity = new EditBox(font, left, 62, wide - 84, 18, Component.translatable("screen.homelink_tasks.quantity"));
        quantity.setMaxLength(4); quantity.setFilter(text -> text.isEmpty() || text.chars().allMatch(Character::isDigit));
        quantity.setValue(Integer.toString(objective.targetQuantity())); quantity.setEditable(quantityEditable); addRenderableWidget(quantity);
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.save_quantity"), ignored -> {
            try { TaskClientNetwork.quantity(board, card, Integer.parseInt(quantity.getValue())); } catch (NumberFormatException invalid) { }
        }).bounds(left + wide - 80, 61, 80, HomeLinkTheme.CONTROL_HEIGHT).build()).active = quantityEditable;
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.contribution." + objective.policy().name().toLowerCase(java.util.Locale.ROOT)),
                ignored -> TaskClientNetwork.card(TaskPackets.CardCommand.SET_POLICY, board, card, "", objective.policy() != ContributionPolicy.ALL_CONTRIBUTORS))
                .bounds(left, 91, wide, HomeLinkTheme.CONTROL_HEIGHT).build()).active = editor;
        addRenderableWidget(HomeLinkButton.builder(Component.translatable(objective.lockedToRecipe() ? "screen.homelink_tasks.recipe_locked" : "screen.homelink_tasks.recipe_any"),
                ignored -> TaskClientNetwork.card(TaskPackets.CardCommand.LOCK_RECIPE, board, card, "", !objective.lockedToRecipe()))
                .bounds(left, 117, wide, HomeLinkTheme.CONTROL_HEIGHT).build()).active = editor;
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.back"), ignored -> minecraft.setScreen(new CardActionsScreen(board, card)))
                .bounds(left, height - 26, wide, HomeLinkTheme.CONTROL_HEIGHT).build());
    }
    @Override public void dataChanged() { clearWidgets(); initContent(); }
    @Override public void onClose() { minecraft.setScreen(new CardActionsScreen(board, card)); }
    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = Math.max(12, width / 2 - 150), wide = Math.min(300, width - 24);
        graphics.drawString(font, title, left, 32, HomeLinkTheme.TEXT, false);
        graphics.drawString(font, Component.translatable("screen.homelink_tasks.quantity"), left, 49, HomeLinkTheme.MUTED, false);
        int y = 145;
        for (var line : font.split(Component.translatable("screen.homelink_tasks.recipe_tracking_hint"), wide)) {
            if (y >= height - 58) break;
            graphics.drawString(font, line, left, y, HomeLinkTheme.MUTED, false); y += 10;
        }
        var view = ClientTaskState.card(card).orElse(null);
        if (view != null && view.assignees().isEmpty() && objective() != null && objective().policy() == ContributionPolicy.ASSIGNEES_ONLY)
            graphics.drawString(font, HomeLinkUi.clip(font, Component.translatable("screen.homelink_tasks.no_assignees").getString(), wide), left, height - 44, TaskAvailabilityStyle.IN_STORAGE, false);
        super.renderContent(graphics, mouseX, mouseY, partialTick);
    }
}
