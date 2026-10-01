package fr.lkdm.homelink.tasks.stock;

import fr.lkdm.homecore.api.stock.StockAccess;
import fr.lkdm.homecore.api.stock.StockAvailability;
import fr.lkdm.homecore.api.stock.StockEntry;
import fr.lkdm.homecore.api.stock.StockSourceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * The authorised stock a plan may draw on, already merged across providers.
 *
 * <p>Merging happens here because it is the only place that can be done safely: each
 * provider attributes its quantities to canonical {@link StockSourceId} values, so a
 * chest covered by two controllers contributes once. Where that identity is missing,
 * nothing is summed blindly.</p>
 *
 * @param entries merged per-variant quantities
 * @param availability how much of the requested scope was actually observed
 * @param access whether the player may also take the items out
 * @param configured whether any storage is configured for this board at all
 * @param observedTick server tick of the observation
 */
public record StockContext(List<StockEntry> entries, StockAvailability availability,
                           StockAccess access, boolean configured, long observedTick) {
    public static final long MAX_AGE_TICKS = 200;
    /** Validates the context and copies its entries. */
    public StockContext {
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(access, "access");
    }

    /**
     * Returns the context of a board with no storage configured in this situation.
     *
     * <p>This is not a failure: the plan is computed from the inventory alone and says
     * so, instead of showing a permanent grey breakdown.</p>
     *
     * @param observedTick server tick of the observation
     * @return inventory-only context
     */
    public static StockContext inventoryOnly(long observedTick) {
        return new StockContext(List.of(), StockAvailability.COMPLETE, StockAccess.READ_ONLY, false, observedTick);
    }

    /**
     * Merges the snapshots of several providers of one network into a single context.
     *
     * <p>The same physical inventory reported by two controllers keeps one quantity: per
     * source the larger observation wins rather than both being added. Availability
     * degrades to the weakest snapshot, because one unreadable controller makes an
     * absence unprovable for the whole scope.</p>
     *
     * @param snapshots snapshots of providers of the same network
     * @param configured whether storage is configured for this board
     * @param observedTick server tick of the observation
     * @return merged context
     */
    public static StockContext merge(List<fr.lkdm.homecore.api.stock.StockSnapshot> snapshots,
                                     boolean configured, long observedTick) {
        Objects.requireNonNull(snapshots, "snapshots");
        if (snapshots.isEmpty()) {
            return new StockContext(List.of(),
                    configured ? StockAvailability.UNAVAILABLE : StockAvailability.COMPLETE,
                    StockAccess.READ_ONLY, configured, observedTick);
        }
        Map<VariantKey, Map<StockSourceId, Long>> merged = new LinkedHashMap<>();
        Map<VariantKey, ItemStack> prototypes = new LinkedHashMap<>();
        StockAvailability availability = StockAvailability.COMPLETE;
        StockAccess access = StockAccess.READ_AND_WITHDRAW;
        long currentTick = observedTick;
        for (var snapshot : snapshots) {
            observedTick = Math.min(observedTick, snapshot.observedTick());
            if (snapshot.observedTick() > currentTick || currentTick - snapshot.observedTick() > MAX_AGE_TICKS) {
                availability = StockAvailability.UNAVAILABLE; access = StockAccess.READ_ONLY;
                continue;
            }
            availability = weakest(availability, snapshot.availability());
            if (snapshot.access() == StockAccess.READ_ONLY) access = StockAccess.READ_ONLY;
            for (StockEntry entry : snapshot.entries()) {
                ItemStack prototype = entry.variant();
                VariantKey key = VariantKey.of(prototype);
                prototypes.putIfAbsent(key, prototype);
                Map<StockSourceId, Long> bySource = merged.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
                // The same source seen twice describes one inventory, so take it once.
                entry.bySource().forEach((source, count) -> bySource.merge(source, count, Math::max));
            }
        }
        List<StockEntry> entries = new ArrayList<>(merged.size());
        merged.forEach((key, bySource) -> entries.add(new StockEntry(prototypes.get(key), bySource)));
        return new StockContext(entries, availability, access, configured, observedTick);
    }

    private static StockAvailability weakest(StockAvailability first, StockAvailability second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    /** Identity of a variant, components included, used only to merge observations. */
    private record VariantKey(net.minecraft.world.item.Item item,
                              net.minecraft.core.component.DataComponentPatch components) {
        static VariantKey of(ItemStack stack) {
            return new VariantKey(stack.getItem(), stack.getComponentsPatch());
        }
    }
}
