package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.stock.AvailabilityState;
import net.minecraft.network.chat.Component;

/** Availability colors, symbols and translated labels; the shared shell belongs to HomeCore. */
public final class TaskAvailabilityStyle {
    /** Everything needed is in the player's own inventory. */
    public static final int READY = 0xFF4CAF50;
    /** Verified authorized stock covers what the inventory does not. */
    public static final int IN_STORAGE = 0xFFE59B3D;
    /** The verified sources are not enough. */
    public static final int MISSING = 0xFFE05252;
    /** Unknown is not zero. */
    public static final int UNVERIFIED = 0xFF8A9099;

    private TaskAvailabilityStyle() { }

    /** Returns the semantic availability color. */
    public static int colour(AvailabilityState state) {
        return switch (state) {
            case READY -> READY;
            case IN_STORAGE -> IN_STORAGE;
            case MISSING -> MISSING;
            case UNVERIFIED -> UNVERIFIED;
        };
    }

    /** Every color is accompanied by a readable symbol and translated label. */
    public static String symbol(AvailabilityState state) {
        return switch (state) {
            case READY -> "\u2714";
            case IN_STORAGE -> "\u25C6";
            case MISSING -> "\u2716";
            case UNVERIFIED -> "?";
        };
    }

    /** Without configured storage, a shortfall is specifically about the inventory. */
    public static Component label(AvailabilityState state, boolean storageConfigured) {
        if (state == AvailabilityState.MISSING && !storageConfigured) {
            return Component.translatable("availability.homelink_tasks.missing_inventory_only");
        }
        return Component.translatable("availability.homelink_tasks."
                + state.name().toLowerCase(java.util.Locale.ROOT));
    }
}
