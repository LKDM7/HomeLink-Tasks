package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** One short form: choose a supported output, quantity, then create. */
public final class CardEditorScreen extends TaskScreen {
    private final UUID boardId, existing;
    private EditBox name, quantity;
    private Button submit;
    private List<RecipeDescriptor> alternatives = List.of();
    private int alternative;
    private boolean crafting;
    private ItemStack target = ItemStack.EMPTY;
    public CardEditorScreen(UUID boardId, UUID existing) {
        super(Component.translatable("screen.homelink_tasks.new_card"));
        this.boardId = boardId; this.existing = existing;
    }
    private int wide() { return Math.min(360, width - 32); }
    private int left() { return (width - wide()) / 2; }
    @Override protected void initContent() {
        String keptName = name == null ? (existing == null ? "" : ClientTaskState.card(existing).map(fr.lkdm.homelink.tasks.network.CardView::title).orElse("")) : name.getValue();
        String keptQuantity = quantity == null ? "1" : quantity.getValue();
        int x = left(), w = wide(), half = (w - 6) / 2;
        if (existing == null) {
            addRenderableWidget(TaskButton.tab(Component.translatable("screen.homelink_tasks.type_manual"), ignored -> { crafting = false; rebuild(); }, !crafting).bounds(x, 51, half, 22).build());
            addRenderableWidget(TaskButton.tab(Component.translatable("screen.homelink_tasks.type_craft"), ignored -> { crafting = true; rebuild(); }, crafting).bounds(x + half + 6, 51, half, 22).build());
        }
        boolean craft = existing == null && crafting;
        name = new EditBox(font, x, craft ? 173 : 100, w, 20, Component.translatable("screen.homelink_tasks.title"));
        name.setMaxLength(fr.lkdm.homelink.tasks.task.TaskCard.MAX_TITLE); name.setValue(keptName);
        name.setHint(Component.translatable(craft ? "screen.homelink_tasks.optional_title" : "screen.homelink_tasks.task_example"));
        name.setResponder(ignored -> updateSubmit()); addRenderableWidget(name);
        quantity = new EditBox(font, x, 132, 64, 20, Component.translatable("screen.homelink_tasks.quantity"));
        quantity.setMaxLength(4); quantity.setFilter(value -> value.isEmpty() || value.chars().allMatch(Character::isDigit));
        quantity.setValue(keptQuantity); quantity.setResponder(ignored -> updateSubmit());
        if (craft) {
            addRenderableWidget(quantity);
            Button.OnPress pick = ignored -> minecraft.setScreen(new ItemPickerScreen(this, this::selectItem));
            addRenderableWidget((target.isEmpty() ? TaskButton.builder(Component.translatable("screen.homelink_tasks.choose_item"), pick) : TaskButton.item(target, pick)).bounds(x, 91, w, 22).build());
            if (!alternatives.isEmpty()) addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.recipe_selection", alternative + 1, alternatives.size()), ignored -> minecraft.setScreen(new RecipePickerScreen(this, alternatives, alternative, index -> alternative = index))).bounds(x + 72, 132, w - 72, 20).build());
        }
        addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks.cancel"), ignored -> onClose()).bounds(x, height - 26, half, 20).build());
        submit = addRenderableWidget(TaskButton.primary(Component.translatable(existing == null ? "screen.homelink_tasks.create" : "screen.homelink_tasks.save_title"), ignored -> submit()).bounds(x + half + 6, height - 26, half, 20).build());
        updateSubmit(); setInitialFocus(craft ? quantity : name);
    }
    private void selectItem(ItemStack item) { target = item.copyWithCount(1); alternatives = ClientRecipeLookup.alternatives(target); alternative = 0; }
    private void rebuild() { clearWidgets(); initContent(); }
    private int amount() { try { return Integer.parseInt(quantity.getValue()); } catch (NumberFormatException ignored) { return 0; } }
    private void updateSubmit() {
        if (submit != null && name != null && quantity != null) submit.active = existing != null || !crafting
                ? !name.getValue().isBlank() : !target.isEmpty() && !alternatives.isEmpty() && amount() > 0 && amount() <= CraftObjective.MAX_QUANTITY;
    }
    private void submit() {
        updateSubmit(); if (!submit.active) return;
        String label = name.getValue().strip();
        if (existing != null) TaskClientNetwork.card(fr.lkdm.homelink.tasks.network.TaskPackets.CardCommand.RENAME, boardId, existing, label, false);
        else if (!crafting) TaskClientNetwork.createManual(boardId, label);
        else {
            if (label.isEmpty()) label = target.getHoverName().getString();
            if (label.length() > fr.lkdm.homelink.tasks.task.TaskCard.MAX_TITLE) label = label.substring(0, fr.lkdm.homelink.tasks.task.TaskCard.MAX_TITLE);
            TaskClientNetwork.createCraft(boardId, label, target, amount(), alternatives.get(alternative).recipeId(), true);
        }
        onClose();
    }
    @Override public void renderContent(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta); int x = left(), w = wide();
        g.drawString(font, Component.translatable(existing == null ? "screen.homelink_tasks.new_card" : "screen.homelink_tasks.save_title"), x, 33, TaskTheme.TEXT, false);
        boolean craft = existing == null && crafting;
        g.drawString(font, Component.translatable(craft ? "screen.homelink_tasks.optional_title" : "screen.homelink_tasks.title"), x, craft ? 161 : 87, TaskTheme.TEXT_MUTED, false);
        if (craft) {
            g.drawString(font, Component.translatable("screen.homelink_tasks.object"), x, 79, TaskTheme.TEXT_MUTED, false);
            g.drawString(font, Component.translatable("screen.homelink_tasks.quantity"), x, 120, TaskTheme.TEXT_MUTED, false);
            if (height >= 280) g.drawWordWrap(font, Component.translatable("screen.homelink_tasks.creation_hint"), x, 202, w, TaskTheme.TEXT_MUTED);
        } else g.drawWordWrap(font, Component.translatable("screen.homelink_tasks.manual_hint"), x, 134, w, TaskTheme.TEXT_MUTED);
        super.renderContent(g, mx, my, delta);
    }
    @Override public void onClose() { if (existing == null) BoardScreen.openOrRefresh(); else minecraft.setScreen(new CardDetailScreen(boardId, existing)); }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { if ((key == 257 || key == 335) && (name.isFocused() || quantity.isFocused())) { submit(); return true; } return super.keyPressed(key, scan, modifiers); }
    public UUID editing() { return existing; }
}
