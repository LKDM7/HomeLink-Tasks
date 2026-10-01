package fr.lkdm.homelink.tasks.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Secondary actions are grouped behind one menu, with accessible labelled buttons. */
public final class TaskMenuScreen extends TaskScreen {
    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int scroll;
    public TaskMenuScreen(Screen parent) { super(Component.translatable("screen.homelink_tasks.more")); this.parent = parent; }
    public TaskMenuScreen action(String key, Runnable run) { return action(key, true, run); }
    public TaskMenuScreen action(Component label, Runnable run) { entries.add(new Entry(label, true, run)); return this; }
    public TaskMenuScreen action(String key, boolean active, Runnable run) { entries.add(new Entry(Component.translatable("screen.homelink_tasks." + key), active, run)); return this; }
    private int rows() { return Math.max(1, (height - 80) / 26); }
    @Override protected void initContent() {
        int w = Math.min(280, width - 32), x = (width - w) / 2;
        for (int i = scroll; i < Math.min(entries.size(), scroll + rows()); i++) {
            Entry entry = entries.get(i);
            addRenderableWidget(TaskButton.builder(entry.label(), ignored -> { minecraft.setScreen(parent); entry.run().run(); }).bounds(x, 53 + (i - scroll) * 26, w, 22).build()).active = entry.active();
        }
        boolean overflow = entries.size() > rows();
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.back"), ignored -> onClose()).bounds(x, height - 26, overflow ? w - 72 : w, 20).build());
        if (overflow) {
            addRenderableWidget(TaskButton.builder(Component.literal("<"), ignored -> move(-1)).bounds(x + w - 66, height - 26, 30, 20).build()).active = scroll > 0;
            addRenderableWidget(TaskButton.builder(Component.literal(">"), ignored -> move(1)).bounds(x + w - 30, height - 26, 30, 20).build()).active = scroll + rows() < entries.size();
        }
    }
    @Override public void renderContent(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta);
        g.drawString(font, title, (width - Math.min(280, width - 32)) / 2, 33, TaskTheme.TEXT, false);
        super.renderContent(g, mx, my, delta);
    }
    private void move(int step) { scroll = Math.clamp(scroll + step, 0, Math.max(0, entries.size() - rows())); clearWidgets(); initContent(); }
    @Override public boolean mouseScrolledContent(double x, double y, double horizontal, double vertical) { move(-(int)vertical); return true; }
    @Override public void onClose() { minecraft.setScreen(parent); }
    private record Entry(Component label, boolean active, Runnable run) { }
}
