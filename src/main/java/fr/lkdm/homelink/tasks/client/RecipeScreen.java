package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.PlanView;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** The actual selected recipe, plus a scrollable personal allocation for all its ingredients. */
public final class RecipeScreen extends TaskScreen {
    private final UUID board;
    private final UUID card;
    private final boolean readOnly;
    private final IngredientTaskActions ingredientActions;
    private int ingredient;
    private int ingredientScroll;
    private int recipeAlternative;
    private long lastRequest;

    public RecipeScreen(UUID board, UUID card) {
        this(board, card, false);
    }
    public RecipeScreen(UUID board, UUID card, boolean readOnly) {
        super(Component.translatable("screen.homelink_tasks.recipe_panel")); this.board = board; this.card = card;
        this.readOnly = readOnly;
        ingredientActions = new IngredientTaskActions(board, card);
    }
    private CardView card() { return ClientTaskState.card(card).orElse(null); }
    private RecipeDescriptor recipe() {
        var view = card();
        return view == null ? null : view.objective().flatMap(objective -> ClientRecipeLookup.describe(objective.recipeId())).orElse(null);
    }
    private int left() { return Math.max(12, width / 2 - 180); }
    private int wide() { return Math.min(360, width - 24); }
    private int ingredientsTop() { return height < 250 ? 162 : 172; }
    private int rows() { return Math.max(1, (height - ingredientsTop() - 44) / 28); }

    @Override protected void initContent() {
        int left = left(), wide = wide();
        button("back", left, height - 26, 64, this::onClose);
        if (readOnly) return;
        button("expand_recipe", left, height < 250 ? 136 : 142, wide, this::expand).active = canEdit() && recipe() != null && !recipe().ingredients().isEmpty();
        button("next_recipe", left + 70, height - 26, wide - 70, this::chooseRecipe).active = canEdit()
                && card() != null && !ClientCardPermissions.hasExpandedIngredients(card());
        var recipe = recipe();
        if (recipe == null || card() == null || card().objective().orElseThrow().remaining() == 0) return;
        ingredientScroll = Math.clamp(ingredientScroll, 0, Math.max(0, recipe.ingredients().size() - rows()));
        for (int index = ingredientScroll; index < Math.min(recipe.ingredients().size(), ingredientScroll + rows()); index++) {
            int selected = index;
            var add = addRenderableWidget(HomeLinkButton.builder(Component.literal("+"), ignored -> {
                if (canQuickAdd(selected)) quickAdd(selected);
            }).bounds(left + wide - 24, ingredientsTop() + 1 + (index - ingredientScroll) * 28, 22, HomeLinkTheme.CONTROL_HEIGHT).build());
            add.active = canQuickAdd(index);
            add.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                    "screen.homelink_tasks." + (add.active ? "quick_component" : "component_unavailable"))));
        }
    }

    private boolean canEdit() {
        return !readOnly && card() != null && !card().archived() && ClientTaskState.board().map(board -> board.viewerRole().atLeast(fr.lkdm.homelink.tasks.board.BoardRole.EDITOR)).orElse(false);
    }
    private net.minecraft.client.gui.components.Button button(String key, int x, int y, int wide, Runnable run) {
        return addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks." + key),
                ignored -> run.run()).bounds(x, y, wide, HomeLinkTheme.CONTROL_HEIGHT).build());
    }

    private void chooseRecipe() {
        var card = card(); if (card == null || card.objective().isEmpty()) return;
        List<RecipeDescriptor> choices = ClientRecipeLookup.alternatives(card.objective().orElseThrow().target());
        if (choices.isEmpty()) return;
        int current = 0;
        for (int index = 0; index < choices.size(); index++) if (choices.get(index).recipeId().equals(card.objective().orElseThrow().recipeId())) current = index;
        minecraft.setScreen(new RecipePickerScreen(this, choices, current, index -> TaskClientNetwork.selectRecipe(board, this.card, choices.get(index).recipeId())));
    }

    private void expand() {
        var recipe = recipe(); var card = card();
        if (recipe == null || card == null || card.objective().isEmpty() || card.objective().orElseThrow().remaining() == 0) return;
        if (recipe.ingredients().isEmpty()) return;
        minecraft.setScreen(new RecipeExpansionScreen(board, this.card, Math.floorMod(ingredient, recipe.ingredients().size()), recipe));
    }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        var card = card(); var recipe = recipe(); int left = left(), wide = wide();
        if (card == null || recipe == null || card.objective().isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.recipe_missing"), left, 45, HomeLinkTheme.MUTED, false);
            super.renderContent(graphics, mouseX, mouseY, partialTick); return;
        }
        var objective = card.objective().orElseThrow();
        graphics.drawString(font, HomeLinkUi.clip(font, card.title(), wide), left, 32, HomeLinkTheme.TEXT, false);
        graphics.drawString(font, HomeLinkUi.clip(font, recipe.result().getHoverName().getString(), wide), left, 46, HomeLinkTheme.MUTED, false);
        drawRecipe(graphics, recipe, left, 62, mouseX, mouseY);
        int operations = recipe.operationsFor(objective.remaining());
        graphics.renderItem(recipe.result(), left + 92, 67);
        graphics.renderItemDecorations(font, recipe.result(), left + 92, 67);
        var operationsText = Component.translatable("screen.homelink_tasks.operations", operations, recipe.outputPerOperation());
        graphics.drawString(font, HomeLinkUi.clip(font, operationsText.getString(), wide - 92),
                left + 92, 90, HomeLinkTheme.MUTED, false);
        var stationText = Component.translatable("screen.homelink_tasks.station",
                Component.translatable("station.homelink_tasks." + recipe.station().getPath()));
        graphics.drawString(font, HomeLinkUi.clip(font, stationText.getString(), wide), left, 122, HomeLinkTheme.TEXT, false);
        int produced = operations * recipe.outputPerOperation();
        var surplusText = Component.translatable("screen.homelink_tasks.surplus", produced, Math.max(0, produced - objective.remaining()));
        graphics.drawString(font, HomeLinkUi.clip(font, surplusText.getString(), wide - 92),
                left + 92, 103, HomeLinkTheme.MUTED, false);
        if (mouseX >= left + 92 && mouseX < left + wide && mouseY >= 88 && mouseY < 115)
            graphics.renderComponentTooltip(font, List.of(operationsText, surplusText), mouseX, mouseY);
        if (mouseX >= left && mouseX < left + wide && mouseY >= 120 && mouseY < 133)
            graphics.renderComponentTooltip(font, List.of(stationText), mouseX, mouseY);
        if (objective.remaining() == 0) {
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.plan_finished"), left, 174, HomeLinkTheme.MUTED, false);
        } else {
            var plan = ClientTaskState.plan(this.card).orElse(null);
            ingredient = recipe.ingredients().isEmpty() ? 0 : Math.floorMod(ingredient, recipe.ingredients().size());
            ingredientScroll = Math.clamp(ingredientScroll, 0, Math.max(0, recipe.ingredients().size() - rows()));
            for (int index = ingredientScroll; index < Math.min(recipe.ingredients().size(), ingredientScroll + rows()); index++) {
                int y = ingredientsTop() + (index - ingredientScroll) * 28;
                graphics.fill(left, y - 2, left + wide, y + 24, index == ingredient ? HomeLinkTheme.HEADER : HomeLinkTheme.SURFACE);
                var accepted = recipe.ingredients().get(index).ingredient().getItems();
                if (accepted.length > 0) {
                    graphics.renderItem(accepted[0], left + 3, y + 2);
                    graphics.drawString(font, HomeLinkUi.clip(font, accepted[0].getHoverName().getString(), wide - (readOnly ? 32 : 58)), left + 25, y, HomeLinkTheme.TEXT, false);
                    if (mouseX >= left && mouseX < left + 22 && mouseY >= y && mouseY < y + 22)
                        graphics.renderTooltip(font, accepted[0], mouseX, mouseY);
                }
                if (plan != null && index < plan.ingredients().size()) {
                    PlanView.Entry allocation = plan.ingredients().get(index);
                    String sources = Component.translatable("screen.homelink_tasks.sources", allocation.fromInventory(), allocation.fromStock(), allocation.required()).getString();
                    graphics.drawString(font, HomeLinkUi.clip(font, sources + " " + TaskAvailabilityStyle.symbol(allocation.state()), wide - (readOnly ? 32 : 58)), left + 25, y + 11, TaskAvailabilityStyle.colour(allocation.state()), false);
                    if (mouseX >= left + 22 && mouseX < left + wide - (readOnly ? 0 : 28) && mouseY >= y && mouseY < y + 24) {
                        var details = new java.util.ArrayList<Component>();
                        details.add(TaskAvailabilityStyle.label(allocation.state(), plan.storageConfigured()));
                        details.add(Component.translatable(allocation.state() == fr.lkdm.homelink.tasks.stock.AvailabilityState.UNVERIFIED
                                ? "screen.homelink_tasks.ingredient_unknown" : "screen.homelink_tasks.ingredient_missing", allocation.missing()));
                        if (allocation.fromStock() > 0 && plan.access() == fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY)
                            details.add(Component.translatable("screen.homelink_tasks.read_only"));
                        graphics.renderComponentTooltip(font, details, mouseX, mouseY);
                    }
                }
            }
            if (plan != null) graphics.drawString(font, HomeLinkUi.clip(font, TaskAvailabilityStyle.label(plan.state(), plan.storageConfigured()).getString(), wide),
                    left, height - 43, TaskAvailabilityStyle.colour(plan.state()), false);
        }
        if (System.currentTimeMillis() - lastRequest > 1000) {
            lastRequest = System.currentTimeMillis();
            TaskClientNetwork.personal(TaskPackets.PersonalCommand.REQUEST_PLAN, board, this.card);
        }
        super.renderContent(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRecipe(GuiGraphics graphics, RecipeDescriptor recipe, int left, int top, int mouseX, int mouseY) {
        var slots = recipe.grid().map(grid -> grid.slots()).orElseGet(() -> java.util.stream.IntStream.range(0, recipe.ingredients().size()).boxed().toList());
        int columns = recipe.grid().map(grid -> grid.width()).orElse(3);
        for (int slot = 0; slot < slots.size(); slot++) {
            int x = left + slot % columns * 18, y = top + slot / columns * 18;
            HomeLinkUi.panel(graphics, x, y, 18, 18);
            int index = slots.get(slot); if (index < 0) continue;
            var requirement = recipe.ingredients().get(index);
            ItemStack[] accepted = requirement.ingredient().getItems(); if (accepted.length == 0) continue;
            // A shaped vanilla recipe consumes one item in each occupied cell.
            // Its requirement count is aggregated across matching cells, not per cell.
            int count = recipe.grid().isPresent() ? 1 : requirement.count();
            ItemStack display = accepted[(int) (System.currentTimeMillis() / 2000 % accepted.length)].copyWithCount(count);
            graphics.renderItem(display, x + 1, y + 1); graphics.renderItemDecorations(font, display, x + 1, y + 1);
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) graphics.renderTooltip(font, display, mouseX, mouseY);
        }
    }

    @Override public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) {
        ingredientScroll -= (int) vertical; clearWidgets(); initContent(); return true;
    }

    @Override public boolean mouseClickedContent(double x, double y, int button) {
        if (super.mouseClickedContent(x, y, button)) return true;
        if (recipe() != null && x >= left() && x < left() + wide() && y >= ingredientsTop() && y < Math.min(height - 44, ingredientsTop() + rows() * 28)) {
            ingredient = Math.clamp(ingredientScroll + (int)((y - ingredientsTop()) / 28), 0, Math.max(0, recipe().ingredients().size() - 1)); return true;
        }
        return false;
    }
    @Override public boolean readOnly() { return readOnly; }
    @Override public void dataChanged() { clearWidgets(); initContent(); }
    @Override public void onClose() {
        minecraft.setScreen(readOnly ? new ProjectViewerScreen(board) : new CardDetailScreen(board, card));
    }

    private boolean canQuickAdd(int index) { return !readOnly && ingredientActions.canCreate(index); }
    private void quickAdd(int index) { if (!readOnly) ingredientActions.create(index, this); }
}
