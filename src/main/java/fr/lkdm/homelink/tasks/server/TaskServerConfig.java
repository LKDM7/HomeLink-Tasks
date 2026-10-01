package fr.lkdm.homelink.tasks.server;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Authoritative world rules. Client display preferences cannot increase these limits. */
public final class TaskServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec.IntValue PIN_LIMIT = BUILDER.defineInRange("pins.limit", 3, 1, 5);
    public static final ModConfigSpec.IntValue MAX_QUANTITY = BUILDER.defineInRange("craft.maxQuantity", 4096, 1, 4096);
    public static final ModConfigSpec SPEC = BUILDER.build();
    private TaskServerConfig() { }
}
