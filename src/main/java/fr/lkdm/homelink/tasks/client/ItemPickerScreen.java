package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Searchable, keyboard accessible catalogue of outputs supported by the server. */
public final class ItemPickerScreen extends TaskScreen {
    private final Screen parent;
    private final Consumer<ItemStack> select;
    private final List<ItemStack> catalogue;
    private List<ItemStack> matches;
    private EditBox search;
    private String query = "";
    private int page;
    public ItemPickerScreen(Screen parent, Consumer<ItemStack> select) {
        super(Component.translatable("screen.homelink_tasks.choose_item"));
        this.parent = parent; this.select = select;
        catalogue = ClientRecipeLookup.outputs(); matches = catalogue;
    }
    private int wide() { return Math.min(400, width - 32); }
    private int left() { return (width - wide()) / 2; }
    private int rows() { return Math.max(1, (height - 120) / 28); }
    private static String normalized(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
    @Override protected void initContent() {
        int x = left(), w = wide();
        search = new EditBox(font, x, 51, w, 20, Component.translatable("screen.homelink_tasks.search_items"));
        search.setMaxLength(100); search.setHint(Component.translatable("screen.homelink_tasks.search_items"));
        search.setValue(query);
        search.setResponder(text -> { query = text; page = 0; filter(); rebuild(); });
        rebuild();
    }
    private void rebuild() {
        clearWidgets();
        int x = left(), w = wide();
        page = Math.clamp(page, 0, Math.max(0, (matches.size() - 1) / rows()));
        addRenderableWidget(search); setInitialFocus(search);
        int start = page * rows();
        for (int i = start; i < Math.min(matches.size(), start + rows()); i++) {
            ItemStack item = matches.get(i);
            int y = 81 + (i - start) * 28;
            addRenderableWidget(TaskItemButton.builder(item, ignored -> {
                select.accept(item.copy()); minecraft.setScreen(parent);
            }).bounds(x, y, w, HomeLinkTheme.CONTROL_HEIGHT).build());
        }
        int bottom = height - 26;
        addRenderableWidget(HomeLinkButton.builder(Component.translatable("screen.homelink_tasks.back"), ignored -> onClose()).bounds(x, bottom, w - 112, HomeLinkTheme.CONTROL_HEIGHT).build());
        addRenderableWidget(HomeLinkButton.builder(Component.literal("<"), ignored -> { page--; rebuild(); }).bounds(x + w - 104, bottom, 48, HomeLinkTheme.CONTROL_HEIGHT).build()).active = page > 0;
        addRenderableWidget(HomeLinkButton.builder(Component.literal(">"), ignored -> { page++; rebuild(); }).bounds(x + w - 48, bottom, 48, HomeLinkTheme.CONTROL_HEIGHT).build()).active = start + rows() < matches.size();
    }
    private void filter() {
        String term = normalized(query.strip());
        matches = catalogue.stream().filter(item -> normalized(item.getHoverName().getString()).contains(term)
                || net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString().contains(term)).toList();
    }
    @Override public void renderContent(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        g.drawString(font, title, left(), 33, HomeLinkTheme.TEXT, false);
        if (matches.isEmpty()) g.drawWordWrap(font, Component.translatable("screen.homelink_tasks.no_items"), left(), 87, wide(), HomeLinkTheme.MUTED);
        g.drawString(font, Component.translatable("screen.homelink_tasks.item_results", matches.size(), page + 1, Math.max(1, (matches.size() + rows() - 1) / rows())), left(), height - 43, HomeLinkTheme.MUTED, false);
        super.renderContent(g, mx, my, delta);
    }
    @Override public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) {
        page = Math.clamp(page - (int)Math.signum(vertical), 0, Math.max(0, (matches.size() - 1) / rows())); rebuild(); return true;
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if ((key == 257 || key == 335) && search.isFocused() && !matches.isEmpty()) { select.accept(matches.get(page * rows()).copy()); onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
}
