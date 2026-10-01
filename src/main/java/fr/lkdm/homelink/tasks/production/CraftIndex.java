package fr.lkdm.homelink.tasks.production;

import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.task.TaskCard;
import fr.lkdm.homelink.tasks.task.TaskType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Finds the crafting cards a produced item could advance, without walking every board.
 *
 * <p>Cards are indexed by the item their objective wants, so crediting a batch touches
 * a handful of candidates rather than every project on the server. The index narrows
 * the search only: the exact variant, including components, is still checked by the
 * objective itself, so two items are never merged because they share an item type.</p>
 */
public final class CraftIndex {
    private final Map<Item, Set<TaskCard>> byResult = new LinkedHashMap<>();

    /** Creates an empty index. */
    public CraftIndex() { }

    /** Indexes a card when it is a crafting objective that can still advance.
     * @param card card to index
     */
    public void add(TaskCard card) {
        Objects.requireNonNull(card, "card");
        if (card.type() != TaskType.CRAFT) return;
        card.objective().ifPresent(objective ->
                byResult.computeIfAbsent(objective.target().getItem(), ignored -> new LinkedHashSet<>()).add(card));
    }

    /** Removes a card from the index.
     * @param card card to drop
     */
    public void remove(TaskCard card) {
        Objects.requireNonNull(card, "card");
        card.objective().ifPresent(objective -> {
            Set<TaskCard> cards = byResult.get(objective.target().getItem());
            if (cards == null) return;
            cards.remove(card);
            if (cards.isEmpty()) byResult.remove(objective.target().getItem());
        });
    }

    /** Rebuilds the whole index from the boards, for example after a load.
     * @param boards every board of the server
     */
    public void rebuild(List<TaskBoard> boards) {
        byResult.clear();
        for (TaskBoard board : Objects.requireNonNull(boards, "boards")) {
            for (TaskCard card : board.cards()) add(card);
        }
    }

    /** Returns the cards whose objective wants this item.
     * @param produced finished items
     * @return candidate cards, exact variant still to be checked by the objective
     */
    public List<TaskCard> candidates(ItemStack produced) {
        Objects.requireNonNull(produced, "produced");
        if (produced.isEmpty()) return List.of();
        Set<TaskCard> cards = byResult.get(produced.getItem());
        return cards == null ? List.of() : List.copyOf(new ArrayList<>(cards));
    }

    /** Returns how many result types are indexed.
     * @return indexed item count
     */
    public int size() { return byResult.size(); }
}
