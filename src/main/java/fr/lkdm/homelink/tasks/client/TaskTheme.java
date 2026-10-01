package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.stock.AvailabilityState;
import net.minecraft.network.chat.Component;

/**
 * The HomeLink palette and the labels that go with it.
 *
 * <p>Anthracite, steel and copper carry the interface. Green, orange, red and grey mean
 * availability and nothing else, so the screen never turns into a wall of colour.</p>
 *
 * <p>Every availability colour is paired with a symbol and a translated label. Colour is
 * never the only thing carrying the information.</p>
 */
public final class TaskTheme {
    /** Interface background. */
    public static final int ANTHRACITE = 0xFF303234;
    /** Panel background. */
    public static final int PANEL = 0xFF45474A;
    /** Raised surface, such as a card. */
    public static final int SURFACE = 0xFF252729;
    /** Surface under the cursor. */
    public static final int SURFACE_HOVER = 0xFF4A4D50;
    /** Steel border. */
    public static final int STEEL = 0xFF626568;
    /** Copper accent. */
    public static final int COPPER = 0xFFD2B181;
    /** Primary text. */
    public static final int TEXT = 0xFFE7E5E0;
    /** Secondary text. */
    public static final int TEXT_MUTED = 0xFFAFB1AD;

    public static void panel(net.minecraft.client.gui.GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, SURFACE);
        graphics.fill(x, y, x + width, y + 1, 0xFF17191A);
        graphics.fill(x, y, x + 1, y + height, 0xFF17191A);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF535659);
    }

    public static void screw(net.minecraft.client.gui.GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 4, y + 4, 0xFF242628);
        graphics.fill(x, y, x + 3, y + 1, 0xFF727578);
        graphics.fill(x + 1, y + 2, x + 3, y + 3, 0xFF858887);
    }

    /** Everything needed for the requirement is in the player's own inventory. */
    public static final int READY = 0xFF4CAF50;
    /** Verified authorised stock covers what the inventory does not. */
    public static final int IN_STORAGE = 0xFFE59B3D;
    /** The verified sources are not enough. */
    public static final int MISSING = 0xFFE05252;
    /** The answer could not be verified; unknown is not zero. */
    public static final int UNVERIFIED = 0xFF8A9099;

    private TaskTheme() { }

    /** Truncate at a glyph boundary and make the omission visible. */
    public static String clip(net.minecraft.client.gui.Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        int suffix = font.width("…");
        return width < suffix ? font.plainSubstrByWidth(text, Math.max(0, width))
                : font.plainSubstrByWidth(text, width - suffix) + "…";
    }

    /** Returns the colour of an availability state.
     * @param state state to present
     * @return packed ARGB colour
     */
    public static int colour(AvailabilityState state) {
        return switch (state) {
            case READY -> READY;
            case IN_STORAGE -> IN_STORAGE;
            case MISSING -> MISSING;
            case UNVERIFIED -> UNVERIFIED;
        };
    }

    /**
     * Returns the symbol that accompanies a colour.
     *
     * <p>Shown next to the colour so the state is readable without relying on it.</p>
     *
     * @param state state to present
     * @return short symbol
     */
    public static String symbol(AvailabilityState state) {
        return switch (state) {
            case READY -> "✔";
            case IN_STORAGE -> "◆";
            case MISSING -> "✖";
            case UNVERIFIED -> "?";
        };
    }

    /**
     * Returns the translated label of an availability state.
     *
     * @param state state to present
     * @param storageConfigured whether any storage was configured for the calculation
     * @return translated label
     */
    public static Component label(AvailabilityState state, boolean storageConfigured) {
        // Without any storage configured a shortfall is a fact about the inventory, and says so.
        if (state == AvailabilityState.MISSING && !storageConfigured) {
            return Component.translatable("availability.homelink_tasks.missing_inventory_only");
        }
        return Component.translatable("availability.homelink_tasks."
                + state.name().toLowerCase(java.util.Locale.ROOT));
    }
}
