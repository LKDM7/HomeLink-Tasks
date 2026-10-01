package fr.lkdm.homelink.tasks.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Display preferences, which belong to the player's own client.
 *
 * <p>Nothing here is a business rule. The server decides what a player may see and how
 * many cards they may pin; these settings only decide how the client draws them.</p>
 */
public final class TaskClientConfig {
    /** Where the pinned-task overlay sits on screen. */
    public enum Corner {
        /** Top left. */ TOP_LEFT,
        /** Top right. */ TOP_RIGHT,
        /** Bottom left. */ BOTTOM_LEFT,
        /** Bottom right. */ BOTTOM_RIGHT
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DISPLAY_VISIBLE = BUILDER
            .comment("Draw project summaries on wall screens for this player.")
            .define("display.visible", true);

    /** Whether the pinned-task overlay is drawn at all. */
    public static final ModConfigSpec.BooleanValue HUD_VISIBLE = BUILDER
            .comment("Draw pinned tasks while no screen is open.")
            .define("hud.visible", true);
    /** Whether the overlay uses its compact one-line form. */
    public static final ModConfigSpec.BooleanValue HUD_COMPACT = BUILDER
            .comment("Use the compact one-line form for each pinned task.")
            .define("hud.compact", false);
    /** Corner the overlay is drawn in. */
    public static final ModConfigSpec.EnumValue<Corner> HUD_CORNER = BUILDER
            .comment("Corner the pinned-task overlay is drawn in.")
            .defineEnum("hud.corner", Corner.TOP_RIGHT);
    /** Extra scale applied on top of the game's GUI scale. */
    public static final ModConfigSpec.DoubleValue HUD_SCALE = BUILDER
            .comment("Extra scale applied on top of the game's own GUI scale.")
            .defineInRange("hud.scale", 1.0D, 0.5D, 2.0D);
    /** Whether the overlay draws an opaque background for readability. */
    public static final ModConfigSpec.BooleanValue HUD_HIGH_CONTRAST = BUILDER
            .comment("Draw an opaque background behind the overlay for readability.")
            .define("hud.highContrast", false);
    /** Whether the overlay avoids animating. */
    public static final ModConfigSpec.BooleanValue HUD_REDUCED_MOTION = BUILDER
            .comment("Avoid animating the overlay.")
            .define("hud.reducedMotion", false);
    /** How long a finished task stays on the overlay, in seconds; zero removes it at once. */
    public static final ModConfigSpec.IntValue HUD_KEEP_FINISHED_SECONDS = BUILDER
            .comment("Seconds a finished task stays on the overlay before it is removed. 0 removes it at once.")
            .defineInRange("hud.keepFinishedSeconds", 8, 0, 120);
    public static final ModConfigSpec.BooleanValue HUD_KEEP_FINISHED = BUILDER
            .comment("Keep completed cards visible until manually unpinned.")
            .define("hud.keepFinished", false);
    /** Preferred number of pinned tasks; the server applies its own limit as well. */
    public static final ModConfigSpec.IntValue PIN_LIMIT = BUILDER
            .comment("Preferred number of pinned tasks. The server applies its own limit as well.")
            .defineInRange("pins.limit", 3, 1, 5);

    /** The assembled client configuration. */
    public static final ModConfigSpec SPEC = BUILDER.build();

    private TaskClientConfig() { }
}
