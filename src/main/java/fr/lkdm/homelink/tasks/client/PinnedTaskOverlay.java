package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homecore.api.client.ui.HomeLinkUi;
import fr.lkdm.homecore.api.client.ui.HomeLinkTheme;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import fr.lkdm.homelink.tasks.stock.AvailabilityState;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The pinned tasks a player keeps in sight once the interface is closed.
 *
 * <p>Pins are this player's own: nothing here is imposed on anyone else, and no card of
 * a board they may not read ever reaches this overlay. The availability shown is
 * computed for them.</p>
 *
 * <p>It respects F1, the game's GUI scale and the other elements on screen, and it never
 * captures the mouse during play.</p>
 */
public final class PinnedTaskOverlay {
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 4;
    private static final Map<UUID, Long> FINISHED_AT = new HashMap<>();

    private PinnedTaskOverlay() { }

    /**
     * Draws the overlay.
     *
     * @param graphics drawing context
     * @param delta frame timing
     */
    public static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft client = Minecraft.getInstance();
        // F1 hides the interface, and a screen of our own already shows the same information.
        if (!TaskClientConfig.HUD_VISIBLE.get() || client.options.hideGui || client.player == null) return;
        if (client.screen != null) return;
        List<TaskPackets.PinnedView> pinned = visible();
        if (pinned.isEmpty()) return;

        boolean compact = TaskClientConfig.HUD_COMPACT.get();
        float scale = TaskClientConfig.HUD_SCALE.get().floatValue();
        int lines = compact ? 1 : 2;
        int panelWidth = compact ? 150 : 170;
        int panelHeight = PADDING * 2 + pinned.size() * (lines * LINE_HEIGHT + 2);
        boolean bottom = TaskClientConfig.HUD_CORNER.get() == TaskClientConfig.Corner.BOTTOM_LEFT
                || TaskClientConfig.HUD_CORNER.get() == TaskClientConfig.Corner.BOTTOM_RIGHT;
        scale = Math.min(scale, (client.getWindow().getGuiScaledHeight() - (bottom ? 52F : 8F)) / panelHeight);
        int screenWidth = (int) (client.getWindow().getGuiScaledWidth() / scale);
        int screenHeight = (int) (client.getWindow().getGuiScaledHeight() / scale);
        panelWidth = Math.min(panelWidth, screenWidth - 8);
        int x = switch (TaskClientConfig.HUD_CORNER.get()) {
            case TOP_LEFT, BOTTOM_LEFT -> 4;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenWidth - panelWidth - 4;
        };
        int y = switch (TaskClientConfig.HUD_CORNER.get()) {
            case TOP_LEFT, TOP_RIGHT -> 4;
            // Leave the vanilla hotbar, health and hunger rows accessible.
            case BOTTOM_LEFT, BOTTOM_RIGHT -> Math.max(4, screenHeight - panelHeight - (int)Math.ceil(48F / scale));
        };

        var pose = graphics.pose();
        pose.pushPose();
        pose.scale(scale, scale, 1.0F);
        graphics.fill(x, y, x + panelWidth, y + panelHeight,
                TaskClientConfig.HUD_HIGH_CONTRAST.get() ? 0xFF13151A : HomeLinkTheme.BACKGROUND);
        graphics.fill(x, y, x + 2, y + panelHeight, HomeLinkTheme.ACCENT);
        int line = y + PADDING;
        for (TaskPackets.PinnedView view : pinned) {
            renderEntry(graphics, view, x + PADDING, line, panelWidth - PADDING * 2, compact);
            line += lines * LINE_HEIGHT + 2;
        }
        pose.popPose();
    }

    private static void renderEntry(GuiGraphics graphics, TaskPackets.PinnedView view, int x, int y,
                                    int available, boolean compact) {
        var font = Minecraft.getInstance().font;
        int titleColour = view.status() == TaskStatus.DONE ? TaskAvailabilityStyle.READY : HomeLinkTheme.TEXT;
        boolean crafting = view.wanted() > 0;
        String progress = crafting ? view.completed() + " / " + view.wanted()
                : Component.translatable("column.homelink_tasks."
                        + view.status().name().toLowerCase(java.util.Locale.ROOT)).getString();
        int progressWidth = font.width(progress);
        String title = HomeLinkUi.clip(font, view.title(), available - (compact ? progressWidth + 6 : 0));
        graphics.drawString(font, title, x, y, titleColour, false);
        if (compact) {
            graphics.drawString(font, progress, x + available - progressWidth, y, HomeLinkTheme.MUTED, false);
            return;
        }
        graphics.drawString(font, progress, x, y + LINE_HEIGHT, HomeLinkTheme.MUTED, false);
        if (!crafting) return;
        // The materials hint is computed for the player reading it, never for the card's owner.
        int colour = TaskAvailabilityStyle.colour(view.availability());
        String summary = TaskAvailabilityStyle.symbol(view.availability()) + " "
                + TaskAvailabilityStyle.label(view.availability(), view.storageConfigured()).getString();
        graphics.drawString(font, HomeLinkUi.clip(font, summary, available - progressWidth - 8),
                x + progressWidth + 8, y + LINE_HEIGHT, colour, false);
    }

    /**
     * Returns the pinned cards still worth showing.
     *
     * <p>A finished card stays briefly so the player sees the confirmation, then leaves
     * on its own unless they asked to keep it.</p>
     */
    private static List<TaskPackets.PinnedView> visible() {
        long now = System.currentTimeMillis();
        int keepSeconds = TaskClientConfig.HUD_KEEP_FINISHED_SECONDS.get();
        List<TaskPackets.PinnedView> pinned = ClientTaskState.pinned();
        FINISHED_AT.keySet().removeIf(card -> pinned.stream().noneMatch(view -> view.card().equals(card)));
        return pinned.stream().filter(view -> {
            boolean finished = view.status() == TaskStatus.DONE
                    || (view.completed() >= view.wanted() && view.wanted() > 0);
            if (!finished) {
                FINISHED_AT.remove(view.card());
                return true;
            }
            long since = FINISHED_AT.computeIfAbsent(view.card(), ignored -> now);
            return TaskClientConfig.HUD_KEEP_FINISHED.get() || now - since < keepSeconds * 1000L;
        }).limit(TaskClientConfig.PIN_LIMIT.get()).toList();
    }

    /** Returns the state a card with no crafting objective is shown with.
     * @return ready, because a manual task asks for no materials
     */
    public static AvailabilityState manualState() { return AvailabilityState.READY; }
    public static void clear() { FINISHED_AT.clear(); }
}
