package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.board.BoardRole;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.PlanView;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import fr.lkdm.homelink.tasks.stock.AvailabilityState;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One card in full: what it wants, what was really produced and what is still needed.
 *
 * <p>"Pin" and "Track this craft" are two different buttons and say so. Pinning puts the
 * card on this player's own overlay; tracking is what decides that this player's own
 * finished batches are credited here first.</p>
 *
 * <p>The requirements shown are the ones still needed for the remaining quantity, from
 * the recipe the server actually has. Once the objective is reached, materials are not
 * asked for again.</p>
 */
public final class CardDetailScreen extends TaskScreen {
    private final UUID boardId;
    private final UUID cardId;
    private long lastPlanRequest;
    private int contentScroll;
    private int contentHeight;
    private final IngredientTaskActions ingredientActions;
    private final java.util.List<Button> ingredientButtons = new java.util.ArrayList<>();
    private int left() { return Math.max(12, width / 2 - 150); }
    private int wide() { return Math.min(300, width - 24); }

    /** Opens the detail of one card.
     * @param boardId board the card belongs to
     * @param cardId card to show
     */
    public CardDetailScreen(UUID boardId, UUID cardId) {
        super(Component.translatable("screen.homelink_tasks.card"));
        this.boardId = boardId;
        this.cardId = cardId;
        ingredientActions = new IngredientTaskActions(boardId, cardId);
    }

    @Override protected void initContent() {
        ingredientButtons.clear();
        var view = ClientTaskState.card(cardId).orElse(null); if (view == null) return;
        int x = left(), w = wide(), half = (w - 6) / 2;
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.back"), ignored -> onClose()).bounds(x, 30, 72, 20).build());
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.edit_card"), ignored -> minecraft.setScreen(new CardActionsScreen(boardId, cardId))).bounds(x + w - 118, 30, 80, 20).build()).active = canEdit() || ClientCardPermissions.canClaim(view);
        addRenderableWidget(TaskButton.builder(Component.literal("..."), ignored -> openMenu()).bounds(x + w - 32, 30, 32, 20).build());
        if (view.objective().isPresent()) {
            addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.recipe_short"),
                    ignored -> minecraft.setScreen(new RecipeScreen(boardId, cardId))).bounds(x + 78, 30, 78, 20).build());
            view.objective().flatMap(objective -> ClientRecipeLookup.describe(objective.recipeId())).ifPresent(recipe -> {
                for (int i = 0; i < recipe.ingredients().size(); i++) {
                    int index = i;
                    var add = addRenderableWidget(TaskButton.builder(Component.literal("+"),
                            ignored -> ingredientActions.create(index, this)).bounds(x + w - 24, 0, 24, 20).build());
                    add.visible = false;
                    add.active = ingredientActions.canCreate(index);
                    add.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                            "screen.homelink_tasks." + (add.active ? "quick_component" : "component_unavailable"))));
                    ingredientButtons.add(add);
                }
            });
        }
        boolean pinned = ClientTaskState.pinned().stream().anyMatch(pin -> pin.card().equals(cardId));
        addRenderableWidget(TaskButton.builder(Component.translatable(pinned ? "screen.homelink_tasks.unpin" : "screen.homelink_tasks.pin"), ignored ->
                TaskClientNetwork.personal(pinned ? TaskPackets.PersonalCommand.UNPIN : TaskPackets.PersonalCommand.PIN, boardId, cardId)).bounds(x, height - 26, half, 20).build());
        if (view.type() == TaskType.CRAFT) {
            boolean tracked = ClientTaskState.tracking(cardId);
            addRenderableWidget(TaskButton.primary(Component.translatable(tracked ? "screen.homelink_tasks.untrack" : "screen.homelink_tasks.track"), ignored ->
                    TaskClientNetwork.personal(tracked ? TaskPackets.PersonalCommand.UNTRACK : TaskPackets.PersonalCommand.TRACK, boardId, cardId)).bounds(x + half + 6, height - 26, half, 20).build());
        } else {
            TaskStatus next = view.status() == TaskStatus.DONE ? TaskStatus.TODO : TaskStatus.DONE;
            if (!ClientCardPermissions.canMove(view) && ClientCardPermissions.canClaim(view))
                addRenderableWidget(TaskButton.primary(Component.translatable("screen.homelink_tasks.claim"), ignored -> TaskClientNetwork.card(TaskPackets.CardCommand.CLAIM, boardId, cardId, "", false)).bounds(x + half + 6, height - 26, half, 20).build());
            else addRenderableWidget(TaskButton.primary(Component.translatable(next == TaskStatus.DONE ? "screen.homelink_tasks.complete" : "screen.homelink_tasks.reopen_task"), ignored ->
                    TaskClientNetwork.move(boardId, cardId, next, view.order())).bounds(x + half + 6, height - 26, half, 20).build()).active = ClientCardPermissions.canMoveTo(view, next);
        }
    }
    private void openMenu() {
        var view = ClientTaskState.card(cardId).orElse(null); if (view == null) return;
        TaskMenuScreen menu = new TaskMenuScreen(this);
        if (view.objective().isPresent()) {
            menu.action("recipe_panel", () -> minecraft.setScreen(new RecipeScreen(boardId, cardId)));
            menu.action("craft_settings", () -> minecraft.setScreen(new ObjectiveSettingsScreen(boardId, cardId)));
            if (view.objective().get().remaining() == 0) menu.action("repeat", canEdit(), () -> TaskClientNetwork.card(TaskPackets.CardCommand.REPEAT, boardId, cardId, "", false));
        }
        for (TaskStatus status : TaskStatus.values()) if (status != view.status() && ClientCardPermissions.canMoveTo(view, status))
            menu.action("move_" + status.name().toLowerCase(java.util.Locale.ROOT), () -> TaskClientNetwork.move(boardId, cardId, status, view.order()));
        menu.action("reorder_up", ClientCardPermissions.canMove(view), () -> TaskClientNetwork.move(boardId, cardId, view.status(), Math.max(0, view.order() - 1)));
        menu.action("reorder_down", ClientCardPermissions.canMove(view), () -> TaskClientNetwork.move(boardId, cardId, view.status(), view.order() + 1));
        minecraft.setScreen(menu);
    }
    @Override public void onClose() { BoardScreen.openOrRefresh(); }
    private void rebuild() {
        clearWidgets();
        initContent();
    }

    @Override public void dataChanged() { rebuild(); }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ingredientButtons.forEach(button -> button.visible = false);
        renderBackground(graphics, mouseX, mouseY, partialTick);
        Optional<CardView> card = ClientTaskState.card(cardId);
        if (card.isEmpty()) {
            graphics.drawCenteredString(font, Component.translatable("screen.homelink_tasks.card_gone"),
                    width / 2, height / 2, TaskTheme.TEXT_MUTED);
            super.renderContent(graphics, mouseX, mouseY, partialTick);
            return;
        }
        int left = left();
        graphics.enableScissor(left, 56, left + wide(), height - 40);
        graphics.pose().pushPose();
        graphics.pose().translate(0, -contentScroll, 0);
        int y = 58;
        graphics.drawString(font, TaskTheme.clip(font, card.get().title(), wide()), left, y, TaskTheme.TEXT, false);
        y += 14;
        if (!card.get().description().isBlank()) {
            for (var line : font.split(Component.literal(card.get().description()), wide())) {
                graphics.drawString(font, line, left, y, TaskTheme.TEXT_MUTED, false);
                y += 10;
            }
            y += 4;
        }
        if (card.get().type() == TaskType.CRAFT) y = renderCraft(graphics, card.get(), left, y);
        else {
            var lines = font.split(Component.translatable("screen.homelink_tasks.manual_hint"), wide());
            for (var line : lines) { graphics.drawString(font, line, left, y, TaskTheme.TEXT_MUTED, false); y += 11; }
        }
        contentHeight = y - 56;
        contentScroll = Math.clamp(contentScroll, 0, Math.max(0, contentHeight - (height - 96)));
        graphics.pose().popPose();
        graphics.disableScissor();
        super.renderContent(graphics, mouseX, mouseY, partialTick);
        requestPlan();
    }

    private int renderCraft(GuiGraphics graphics, CardView card, int left, int top) {
        var objective = card.objective().orElse(null);
        if (objective == null) return top;
        int y = top;
        graphics.renderItem(objective.target(), left, y - 4);
        graphics.drawString(font, Component.translatable("screen.homelink_tasks.progress",
                objective.completedQuantity(), objective.targetQuantity()), left + 22, y, TaskTheme.TEXT, false);
        y += 14;
        graphics.drawString(font, Component.translatable("screen.homelink_tasks.ingredients"), left, y, TaskTheme.TEXT_MUTED, false);
        y += 16;
        Optional<PlanView> plan = ClientTaskState.plan(cardId);
        if (plan.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.plan_pending"),
                    left, y, TaskTheme.TEXT_MUTED, false);
            return y + 12;
        }
        PlanView view = plan.get();
        if (objective.remaining() == 0) {
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.plan_finished"),
                    left, y, TaskTheme.READY, false);
            return y + 12;
        }
        for (int index = 0; index < view.ingredients().size(); index++) {
            PlanView.Entry entry = view.ingredients().get(index);
            renderIngredient(graphics, entry, view.storageConfigured(), left, y);
            if (index < ingredientButtons.size()) {
                var button = ingredientButtons.get(index);
                button.setY(y - 4 - contentScroll);
                button.visible = button.getY() >= 56 && button.getBottom() <= height - 40;
            }
            y += 30;
        }
        int colour = TaskTheme.colour(view.state());
        for (var line : font.split(Component.literal(TaskTheme.symbol(view.state()) + " ").append(TaskTheme.label(view.state(), view.storageConfigured())), wide())) {
            graphics.drawString(font, line, left, y, colour, false); y += 11;
        }
        if (view.state() == AvailabilityState.IN_STORAGE
                && view.access() == fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY) {
            // Seeing a stock is not being allowed to take it, and the screen must not promise otherwise.
            for (var line : font.split(Component.translatable("screen.homelink_tasks.withdrawal_restricted"), wide())) {
                graphics.drawString(font, line, left, y, TaskTheme.IN_STORAGE, false); y += 11;
            }
        }
        return y;
    }

    @Override public boolean mouseScrolledContent(double mouseX, double mouseY, double horizontal, double vertical) {
        contentScroll = Math.clamp(contentScroll - (int) (vertical * 18), 0, Math.max(0, contentHeight - (height - 96)));
        return true;
    }

    private void renderIngredient(GuiGraphics graphics, PlanView.Entry entry, boolean storageConfigured,
                                  int left, int y) {
        graphics.renderItem(entry.display(), left, y - 4);
        int colour = TaskTheme.colour(entry.state());
        graphics.drawString(font, TaskTheme.symbol(entry.state()), left + 20, y, colour, false);
        Component detail = switch (entry.state()) {
            case READY -> Component.translatable("screen.homelink_tasks.part_inventory", entry.fromInventory(),
                    entry.required());
            case IN_STORAGE -> Component.translatable("screen.homelink_tasks.part_split", entry.fromInventory(),
                    entry.fromStock(), entry.required());
            case MISSING -> Component.translatable(storageConfigured
                            ? "screen.homelink_tasks.part_missing" : "screen.homelink_tasks.part_missing_inventory_only",
                    entry.allocated(), entry.required(), entry.missing());
            case UNVERIFIED -> Component.translatable("screen.homelink_tasks.part_unverified",
                    entry.allocated(), entry.required());
        };
        graphics.drawString(font, TaskTheme.clip(font, entry.display().getHoverName().getString(), wide() - 62), left + 32, y - 3, TaskTheme.TEXT, false);
        graphics.drawString(font, TaskTheme.clip(font, detail.getString(), wide() - 62), left + 32, y + 8, colour, false);
    }

    private void requestPlan() {
        long now = System.currentTimeMillis();
        if (now - lastPlanRequest < 1000L) return;
        lastPlanRequest = now;
        TaskClientNetwork.personal(TaskPackets.PersonalCommand.REQUEST_PLAN, boardId, cardId);
    }

    /** Returns whether the viewer may edit this board's cards.
     * @return whether the viewer holds at least EDITOR
     */
    public boolean canEdit() {
        return ClientTaskState.board().map(board -> board.viewerRole().atLeast(BoardRole.EDITOR)).orElse(false);
    }

    @Override public boolean isPauseScreen() { return false; }
}
