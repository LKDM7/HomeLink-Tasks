package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homecore.api.stock.StockAccess;
import fr.lkdm.homelink.tasks.stock.AvailabilityPlan;
import fr.lkdm.homelink.tasks.stock.AvailabilityState;
import fr.lkdm.homelink.tasks.stock.IngredientAvailability;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * What one viewing player still needs for a card, as drawn on their screen.
 *
 * <p>Personal by construction: the inventory part is the receiving player's own. Two
 * players looking at the same shared card legitimately see different colours while the
 * card's production counter stays common to both.</p>
 *
 * <p>It carries quantities the player is allowed to know and nothing else: no storage
 * position, no inventory of anybody else and no stock they may not read.</p>
 *
 * @param card card the plan belongs to
 * @param state overall state to present
 * @param access whether the player may also take the stock part out
 * @param storageConfigured whether any storage was configured for this calculation
 * @param observedTick server tick the stock part was observed at
 * @param operations operations still needed
 * @param outputPerOperation yield of one operation
 * @param station workstation the recipe requires
 * @param ingredients per-requirement allocation with the item shown for each
 */
public record PlanView(java.util.UUID card, AvailabilityState state, StockAccess access,
                       boolean storageConfigured, long observedTick, int operations, int outputPerOperation,
                       Optional<ResourceLocation> station, List<Entry> ingredients) {
    /** Largest number of requirements one plan carries. */
    public static final int MAX_ENTRIES = 9;

    /** Validates and copies the entries. */
    public PlanView {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(station, "station");
        ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
        if (ingredients.size() > MAX_ENTRIES) throw new IllegalArgumentException("Plan view exceeds its bounds");
    }

    /**
     * One requirement as shown to the player.
     *
     * @param display item shown for the requirement
     * @param required units needed for the remaining operations
     * @param fromInventory units allocated from the player's own inventory
     * @param fromStock units allocated from verified authorised stock
     * @param missing units nothing accounted for
     * @param state how this requirement should be presented
     */
    public record Entry(ItemStack display, int required, int fromInventory, int fromStock, int missing,
                        AvailabilityState state) {
        /** Validates the entry. */
        public Entry {
            Objects.requireNonNull(display, "display");
            Objects.requireNonNull(state, "state");
        }

        /** Returns the units accounted for.
         * @return allocated units
         */
        public int allocated() { return fromInventory + fromStock; }

        static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.of(
                (buffer, entry) -> {
                    ItemStack.STREAM_CODEC.encode(buffer, entry.display());
                    buffer.writeVarInt(entry.required());
                    buffer.writeVarInt(entry.fromInventory());
                    buffer.writeVarInt(entry.fromStock());
                    buffer.writeVarInt(entry.missing());
                    buffer.writeEnum(entry.state());
                },
                buffer -> new Entry(ItemStack.STREAM_CODEC.decode(buffer), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readVarInt(), buffer.readEnum(AvailabilityState.class)));
    }

    /**
     * Builds the view of a computed plan.
     *
     * @param card card the plan belongs to
     * @param plan server-computed allocation
     * @param descriptor described recipe, absent when the recipe is unsupported
     * @param operations operations still needed for the remaining quantity
     * @return wire copy
     */
    public static PlanView of(java.util.UUID card, AvailabilityPlan plan, Optional<RecipeDescriptor> descriptor,
                              int operations) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(descriptor, "descriptor");
        List<Entry> entries = new ArrayList<>(plan.ingredients().size());
        for (int index = 0; index < plan.ingredients().size() && index < MAX_ENTRIES; index++) {
            final int position = index;
            IngredientAvailability allocation = plan.ingredients().get(index);
            ItemStack display = descriptor
                    .filter(described -> position < described.ingredients().size())
                    .map(described -> described.ingredients().get(position).ingredient().getItems())
                    .filter(items -> items.length > 0)
                    .map(items -> items[0].copyWithCount(1))
                    .orElse(ItemStack.EMPTY);
            entries.add(new Entry(display, allocation.required(), allocation.fromInventory(),
                    allocation.fromStock(), allocation.missing(), allocation.state()));
        }
        return new PlanView(card, plan.state(), plan.access(), plan.storageConfigured(), plan.observedTick(),
                Math.max(0, operations), descriptor.map(RecipeDescriptor::outputPerOperation).orElse(1),
                descriptor.map(RecipeDescriptor::station), entries);
    }

    /** Wire codec for one plan. */
    public static final StreamCodec<RegistryFriendlyByteBuf, PlanView> CODEC = StreamCodec.of(
            (buffer, view) -> {
                buffer.writeUUID(view.card());
                buffer.writeEnum(view.state());
                buffer.writeEnum(view.access());
                buffer.writeBoolean(view.storageConfigured());
                buffer.writeVarLong(view.observedTick());
                buffer.writeVarInt(view.operations());
                buffer.writeVarInt(view.outputPerOperation());
                buffer.writeOptional(view.station(), (target, value) -> buffer.writeResourceLocation(value));
                buffer.writeVarInt(view.ingredients().size());
                view.ingredients().forEach(entry -> Entry.CODEC.encode(buffer, entry));
            },
            buffer -> {
                java.util.UUID card = buffer.readUUID();
                AvailabilityState state = buffer.readEnum(AvailabilityState.class);
                StockAccess access = buffer.readEnum(StockAccess.class);
                boolean configured = buffer.readBoolean();
                long observed = buffer.readVarLong();
                int operations = buffer.readVarInt();
                int yield = buffer.readVarInt();
                Optional<ResourceLocation> station = buffer.readOptional(source -> buffer.readResourceLocation());
                int count = ByteBufCodecs.readCount(buffer, MAX_ENTRIES);
                List<Entry> entries = new ArrayList<>(count);
                for (int index = 0; index < count; index++) entries.add(Entry.CODEC.decode(buffer));
                return new PlanView(card, state, access, configured, observed, operations, yield, station, entries);
            });
}
