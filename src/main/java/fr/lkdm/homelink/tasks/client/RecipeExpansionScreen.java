package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Explicit component and child-recipe selection before a revision-bound expansion. */
public final class RecipeExpansionScreen extends TaskScreen {
    private record Choice(ItemStack variant, RecipeDescriptor recipe) { }
    private final UUID board, card;
    private final int ingredient;
    private final long revision, needed;
    private final List<Choice> choices;
    private int selected;
    private int scroll;
    private boolean partial;
    private final net.minecraft.client.gui.screens.Screen parentScreen;

    public RecipeExpansionScreen(UUID board, UUID card, int ingredient, RecipeDescriptor parent) {
        this(board, card, ingredient, parent, new RecipeScreen(board, card));
    }
    public RecipeExpansionScreen(UUID board, UUID card, int ingredient, RecipeDescriptor parent,
                                 net.minecraft.client.gui.screens.Screen parentScreen) {
        super(Component.translatable("screen.homelink_tasks.expand_recipe"));
        this.parentScreen = parentScreen;
        this.board = board; this.card = card; this.ingredient = ingredient;
        var view = ClientTaskState.card(card).orElseThrow(); revision = view.revision();
        needed = (long) parent.operationsFor(view.objective().orElseThrow().remaining()) * parent.ingredients().get(ingredient).count();
        var variants = parent.ingredients().get(ingredient).ingredient().getItems();
        var found = new ArrayList<Choice>();
        for (var variant : variants) {
            for (var recipe : ClientRecipeLookup.alternatives(variant)) {
                if (found.size() == 128) { partial = true; break; }
                found.add(new Choice(variant.copyWithCount(1), recipe));
            }
            if (partial) break;
        }
        choices = List.copyOf(found);
    }
    private int left() { return Math.max(12, width / 2 - 180); }
    private int wide() { return Math.min(360, width - 24); }
    private int rows() { return Math.max(1, (height - 152) / 18); }

    @Override protected void initContent() {
        int left = left(), wide = wide();
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.previous"), ignored -> { selected--; scroll = 0; })
                .bounds(left, 50, 28, 20).build()).active = choices.size() > 1;
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.next"), ignored -> { selected++; scroll = 0; })
                .bounds(left + wide - 28, 50, 28, 20).build()).active = choices.size() > 1;
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.back"), ignored -> onClose())
                .bounds(left, height - 26, 64, 20).build());
        var confirm = addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.create_component"), ignored -> {
            var choice = choices.get(Math.floorMod(selected, choices.size()));
            TaskClientNetwork.expand(board, card, ingredient, choice.variant(), choice.recipe().recipeId(), revision);
            minecraft.setScreen(parentScreen);
        }).bounds(left + 70, height - 26, wide - 70, 20).build());
        confirm.active = !choices.isEmpty() && needed > 0 && needed <= fr.lkdm.homelink.tasks.objective.CraftObjective.MAX_QUANTITY
                && ClientCardPermissions.editor();
        confirm.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("screen.homelink_tasks.expand_confirm")));
    }

    @Override public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick); int left = left(), wide = wide();
        graphics.drawString(font, title, left, 32, TaskTheme.TEXT, false);
        if (choices.isEmpty()) {
            graphics.drawString(font, Component.translatable("screen.homelink_tasks.no_component_recipe"), left, 84, TaskTheme.TEXT_MUTED, false);
        } else {
            var choice = choices.get(Math.floorMod(selected, choices.size()));
            graphics.drawString(font, TaskTheme.clip(font, Component.translatable("screen.homelink_tasks.recipe_selection", Math.floorMod(selected, choices.size()) + 1, choices.size()).getString(), wide - 68), left + 34, 56, TaskTheme.COPPER, false);
            graphics.renderItem(choice.variant(), left, 78);
            graphics.drawString(font, TaskTheme.clip(font, needed + " × " + choice.variant().getHoverName().getString(), wide - 26), left + 24, 82, TaskTheme.TEXT, false);
            int operations = needed > Integer.MAX_VALUE ? 0 : choice.recipe().operationsFor((int) needed);
            graphics.drawString(font, TaskTheme.clip(font, Component.translatable("screen.homelink_tasks.operations", operations,
                    choice.recipe().outputPerOperation()).getString(), wide), left, 100, TaskTheme.TEXT_MUTED, false);
            scroll = Math.clamp(scroll, 0, Math.max(0, choice.recipe().ingredients().size() - rows()));
            for (int index = scroll; index < Math.min(choice.recipe().ingredients().size(), scroll + rows()); index++) {
                int y = 116 + (index - scroll) * 18;
                var requirement = choice.recipe().ingredients().get(index); var variants = requirement.ingredient().getItems();
                if (variants.length == 0) continue;
                var display = variants[(int) (System.currentTimeMillis() / 1800 % variants.length)];
                graphics.renderItem(display, left, y);
                graphics.drawString(font, TaskTheme.clip(font, (long) operations * requirement.count() + " × " + display.getHoverName().getString(), wide - 26), left + 24, y + 4, TaskTheme.TEXT_MUTED, false);
                if (mouseX >= left && mouseX < left + 20 && mouseY >= y && mouseY < y + 18) graphics.renderTooltip(font, display, mouseX, mouseY);
            }
            if (partial) graphics.drawString(font, TaskTheme.clip(font, Component.translatable("screen.homelink_tasks.component_choices_partial").getString(), wide), left, height - 44, TaskTheme.IN_STORAGE, false);
        }
        super.renderContent(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) { scroll -= (int) vertical; return true; }
    @Override public void onClose() { minecraft.setScreen(parentScreen); }
}
