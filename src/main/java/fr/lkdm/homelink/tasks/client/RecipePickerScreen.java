package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Recipe choice is previewed before it is applied. */
public final class RecipePickerScreen extends TaskScreen {
    private final Screen parent;
    private final List<RecipeDescriptor> choices;
    private final IntConsumer select;
    private int index;
    public RecipePickerScreen(Screen parent, List<RecipeDescriptor> choices, int index, IntConsumer select) {
        super(Component.translatable("screen.homelink_tasks.recipe_panel"));
        this.parent = parent; this.choices = List.copyOf(choices); this.index = index; this.select = select;
    }
    private int wide() { return Math.min(320, width - 32); }
    private int left() { return (width - wide()) / 2; }
    @Override protected void initContent() {
        int x = left(), w = wide(), half = (w - 6) / 2;
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.previous"), ignored -> { index--; rebuild(); }).bounds(x, 153, half, HomeLinkTheme.CONTROL_HEIGHT).build()).active = index > 0;
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.next"), ignored -> { index++; rebuild(); }).bounds(x + half + 6, 153, half, HomeLinkTheme.CONTROL_HEIGHT).build()).active = index + 1 < choices.size();
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.cancel"), ignored -> onClose()).bounds(x, height - 26, half, HomeLinkTheme.CONTROL_HEIGHT).build());
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.use_recipe"), ignored -> { select.accept(index); onClose(); }).bounds(x + half + 6, height - 26, half, HomeLinkTheme.CONTROL_HEIGHT).build()).active = !choices.isEmpty();
    }
    private void rebuild() { clearWidgets(); initContent(); }
    @Override public void renderContent(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta); int x = left(), w = wide();
        g.drawString(font, title, x, 33, HomeLinkTheme.TEXT, false);
        if (!choices.isEmpty()) {
            var recipe = choices.get(index);
            g.drawString(font, HomeLinkUi.clip(font, recipe.result().getHoverName().getString(), w), x, 53, HomeLinkTheme.TEXT, false);
            var slots = recipe.grid().map(grid -> grid.slots()).orElseGet(() -> java.util.stream.IntStream.range(0, recipe.ingredients().size()).boxed().toList());
            int columns = recipe.grid().map(grid -> grid.width()).orElse(3);
            for (int slot = 0; slot < Math.min(slots.size(), 9); slot++) {
                int sx = x + slot % columns * 20, sy = 76 + slot / columns * 20;
                HomeLinkUi.panel(g, sx, sy, 18, 18);
                int requirement = slots.get(slot); if (requirement < 0) continue;
                var entry = recipe.ingredients().get(requirement); var accepted = entry.ingredient().getItems();
                if (accepted.length == 0) continue;
                var item = accepted[(int)(System.currentTimeMillis() / 2000 % accepted.length)].copyWithCount(recipe.grid().isPresent() ? 1 : entry.count());
                g.renderItem(item, sx + 1, sy + 1); g.renderItemDecorations(font, item, sx + 1, sy + 1);
                if (mx >= sx && mx < sx + 18 && my >= sy && my < sy + 18) g.renderTooltip(font, item, mx, my);
            }
            g.drawString(font, ">", x + 72, 94, HomeLinkTheme.MUTED, false);
            var output = recipe.result().copyWithCount(recipe.outputPerOperation());
            g.renderItem(output, x + 92, 88); g.renderItemDecorations(font, output, x + 92, 88);
            g.drawString(font, HomeLinkUi.clip(font, Component.translatable("station.homelink_tasks." + recipe.station().getPath()).getString(), w - 122), x + 120, 92, HomeLinkTheme.MUTED, false);
            g.drawString(font, Component.translatable("screen.homelink_tasks.recipe_selection", index + 1, choices.size()), x, 138, HomeLinkTheme.MUTED, false);
        }
        super.renderContent(g, mx, my, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
