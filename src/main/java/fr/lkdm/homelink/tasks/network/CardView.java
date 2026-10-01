package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.objective.ContributionPolicy;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskPriority;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import fr.lkdm.homelink.tasks.task.TaskType;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The copy of a card a client is allowed to draw.
 *
 * <p>It carries what the card itself says, never anything derived from somebody else's
 * rights: no stock quantity, no storage location and no inventory of another player.
 * Availability is personal and travels separately, computed for the viewer.</p>
 *
 * @param id card identity
 * @param type manual task or crafting objective
 * @param title card label
 * @param description card description
 * @param status column the card sits in
 * @param priority urgency shown on the card
 * @param archived whether the card is archived
 * @param order position within its column
 * @param nodeX mind map horizontal position
 * @param nodeY mind map vertical position
 * @param parent parent card in the mind map
 * @param dependencies cards this one waits for
 * @param assignees assigned players
 * @param objective crafting objective, absent for a manual task
 * @param revision revision a client must echo back when it asks for a change
 */
public record CardView(UUID id, TaskType type, String title, String description, TaskStatus status,
                       TaskPriority priority, boolean archived, int order, int nodeX, int nodeY,
                       Optional<UUID> parent, List<UUID> dependencies, List<UUID> assignees,
                       Optional<ObjectiveView> objective, long revision, UUID creator, int derivedIngredient) {
    /** Largest number of cards one board snapshot carries. */
    public static final int MAX_CARDS = 256;

    /** Validates and copies the collections. */
    public CardView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(creator, "creator");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(objective, "objective");
        title = Objects.requireNonNull(title, "title");
        description = Objects.requireNonNull(description, "description");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        assignees = List.copyOf(Objects.requireNonNull(assignees, "assignees"));
        if (title.length() > TaskCard.MAX_TITLE || description.length() > TaskCard.MAX_DESCRIPTION
                || dependencies.size() > TaskCard.MAX_DEPENDENCIES || assignees.size() > TaskCard.MAX_ASSIGNEES) {
            throw new IllegalArgumentException("Card view exceeds its bounds");
        }
    }

    /** The crafting part of a card.
     *
     * @param target wanted item prototype
     * @param targetQuantity finished items wanted
     * @param completedQuantity finished items already credited
     * @param recipeId reference recipe chosen for planning
     * @param lockedToRecipe whether only that recipe contributes
     * @param policy who may contribute
     */
    public record ObjectiveView(ItemStack target, int targetQuantity, int completedQuantity,
                                ResourceLocation recipeId, boolean lockedToRecipe, ContributionPolicy policy) {
        /** Validates the objective view. */
        public ObjectiveView {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(recipeId, "recipeId");
            Objects.requireNonNull(policy, "policy");
            if (targetQuantity < 1 || targetQuantity > CraftObjective.MAX_QUANTITY
                    || completedQuantity < 0 || completedQuantity > targetQuantity) {
                throw new IllegalArgumentException("Objective view out of bounds");
            }
        }

        /** Returns how many finished items are still missing.
         * @return remaining quantity
         */
        public int remaining() { return targetQuantity - completedQuantity; }

        static final StreamCodec<RegistryFriendlyByteBuf, ObjectiveView> CODEC = StreamCodec.of(
                (buffer, view) -> {
                    ItemStack.STREAM_CODEC.encode(buffer, view.target());
                    buffer.writeVarInt(view.targetQuantity());
                    buffer.writeVarInt(view.completedQuantity());
                    buffer.writeResourceLocation(view.recipeId());
                    buffer.writeBoolean(view.lockedToRecipe());
                    buffer.writeEnum(view.policy());
                },
                buffer -> new ObjectiveView(ItemStack.STREAM_CODEC.decode(buffer), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readResourceLocation(), buffer.readBoolean(),
                        buffer.readEnum(ContributionPolicy.class)));
    }

    /** Builds the view of a card.
     * @param card server-owned card
     * @return wire copy
     */
    public static CardView of(TaskCard card) {
        Objects.requireNonNull(card, "card");
        Optional<ObjectiveView> objective = card.objective().map(craft -> new ObjectiveView(craft.target(),
                craft.targetQuantity(), craft.completedQuantity(), craft.recipeId(),
                craft.lockedToRecipe(), craft.policy()));
        return new CardView(card.id(), card.type(), card.title(), card.description(), card.status(),
                card.priority(), card.archived(), card.order(), card.nodeX(), card.nodeY(), card.parent(),
                List.copyOf(card.dependencies()), List.copyOf(card.assignees()), objective, card.revision(), card.creator(), card.derivedIngredient());
    }

    /** Wire codec for one card. */
    public static final StreamCodec<RegistryFriendlyByteBuf, CardView> CODEC = StreamCodec.of(
            CardView::encode, CardView::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, CardView view) {
        buffer.writeUUID(view.id());
        buffer.writeEnum(view.type());
        buffer.writeUtf(view.title(), TaskCard.MAX_TITLE);
        buffer.writeUtf(view.description(), TaskCard.MAX_DESCRIPTION);
        buffer.writeEnum(view.status());
        buffer.writeEnum(view.priority());
        buffer.writeBoolean(view.archived());
        buffer.writeVarInt(view.order());
        buffer.writeVarInt(view.nodeX() + Short.MAX_VALUE);
        buffer.writeVarInt(view.nodeY() + Short.MAX_VALUE);
        buffer.writeOptional(view.parent(), (ByteBuf target, UUID value) -> buffer.writeUUID(value));
        writeIds(buffer, view.dependencies());
        writeIds(buffer, view.assignees());
        buffer.writeOptional(view.objective(), (target, value) -> ObjectiveView.CODEC.encode(buffer, value));
        buffer.writeVarLong(view.revision());
        buffer.writeUUID(view.creator());
        buffer.writeVarInt(view.derivedIngredient() + 1);
    }

    private static CardView decode(RegistryFriendlyByteBuf buffer) {
        return new CardView(buffer.readUUID(), buffer.readEnum(TaskType.class),
                buffer.readUtf(TaskCard.MAX_TITLE), buffer.readUtf(TaskCard.MAX_DESCRIPTION),
                buffer.readEnum(TaskStatus.class), buffer.readEnum(TaskPriority.class), buffer.readBoolean(),
                buffer.readVarInt(), buffer.readVarInt() - Short.MAX_VALUE, buffer.readVarInt() - Short.MAX_VALUE,
                buffer.readOptional(source -> buffer.readUUID()),
                readIds(buffer, TaskCard.MAX_DEPENDENCIES), readIds(buffer, TaskCard.MAX_ASSIGNEES),
                buffer.readOptional(source -> ObjectiveView.CODEC.decode(buffer)),
                buffer.readVarLong(), buffer.readUUID(), buffer.readVarInt() - 1);
    }

    private static void writeIds(RegistryFriendlyByteBuf buffer, List<UUID> ids) {
        buffer.writeVarInt(ids.size());
        ids.forEach(buffer::writeUUID);
    }

    private static List<UUID> readIds(RegistryFriendlyByteBuf buffer, int max) {
        int count = ByteBufCodecs.readCount(buffer, max);
        List<UUID> ids = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) ids.add(buffer.readUUID());
        return ids;
    }
}
