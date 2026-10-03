package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkButton;
import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/** Item catalogue content on the official HomeCore button shell. */
public final class TaskItemButton extends HomeLinkButton {
    private final ItemStack icon;

    private TaskItemButton(Builder builder) {
        super(builder);
        icon = builder.icon;
    }

    public static Builder builder(ItemStack item, OnPress press) {
        return new Builder(item, press);
    }

    @Override protected void renderLabel(GuiGraphics graphics, int color, int horizontalPadding) {
        var font = Minecraft.getInstance().font;
        graphics.renderItem(icon, getX() + 6, getY() + (height - 16) / 2);
        String label = HomeLinkUi.clip(font, getMessage().getString(), Math.max(0, width - 36));
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1);
        graphics.drawString(font, label, getX() + 30, getY() + (height - 8) / 2, color, false);
        graphics.pose().popPose();
    }

    public static final class Builder extends HomeLinkButton.Builder {
        private final ItemStack icon;

        private Builder(ItemStack item, OnPress press) {
            super(item.getHoverName(), press);
            icon = item.copyWithCount(1);
        }

        @Override public TaskItemButton build() { return new TaskItemButton(this); }
    }
}
