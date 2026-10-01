package fr.lkdm.homelink.tasks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Native keyboard/narration behavior with the HomeLink steel and copper surfaces. */
public final class TaskButton extends Button {
    private boolean selected;
    private net.minecraft.world.item.ItemStack icon = net.minecraft.world.item.ItemStack.EMPTY;
    private TaskButton(Button.Builder builder) {
        super(builder);
        setHeight(18);
        setTooltip(net.minecraft.client.gui.components.Tooltip.create(getMessage()));
    }

    public static Button.Builder builder(Component message, Button.OnPress press) {
        return new Button.Builder(message, press) {
            @Override public Button build() { return new TaskButton(this); }
        };
    }

    public static Button.Builder primary(Component message, Button.OnPress press) {
        return builder(message, press);
    }

    public static Button.Builder tab(Component message, Button.OnPress press, boolean selected) {
        return new Button.Builder(message, press) {
            @Override public Button build() { var button = new TaskButton(this); button.selected = selected; button.active = !selected; return button; }
        };
    }

    public static Button.Builder item(net.minecraft.world.item.ItemStack item, Button.OnPress press) {
        return new Button.Builder(item.getHoverName(), press) {
            @Override public Button build() { var button = new TaskButton(this); button.icon = item.copyWithCount(1); return button; }
        };
    }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = getX(), top = getY();
        boolean highlighted = active && isHoveredOrFocused();
        int background = selected ? 0xFF292B2D : highlighted ? 0xFF5A5D60 : active ? 0xFF474A4D : 0xFF36383A;
        graphics.fill(left, top, left + width, top + height, 0xFF181A1B);
        graphics.fill(left + 1, top + 1, left + width - 1, top + height - 1, background);
        graphics.fill(left + 1, top + 1, left + width - 1, top + 2, selected ? 0xFF202224 : active ? 0xFF74787A : 0xFF484B4D);
        graphics.fill(left + 1, top + 2, left + 2, top + height - 1, selected ? 0xFF202224 : 0xFF626669);
        if (selected) graphics.fill(left + 5, top + height / 2 - 1, left + 7, top + height / 2 + 1, TaskTheme.COPPER);
        if (isFocused() && active) graphics.renderOutline(left, top, width, height, TaskTheme.COPPER);
        var font = Minecraft.getInstance().font;
        String label = TaskTheme.clip(font, getMessage().getString(), Math.max(0, width - (icon.isEmpty() ? 8 : 36)));
        if (!icon.isEmpty()) graphics.renderItem(icon, left + 6, top + (height - 16) / 2);
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1);
        graphics.drawString(font, label, icon.isEmpty() ? left + (width - font.width(label)) / 2 : left + 30, top + (height - 8) / 2,
                selected ? TaskTheme.COPPER : active ? TaskTheme.TEXT : 0xFF91948F, false);
        graphics.pose().popPose();
    }
}
