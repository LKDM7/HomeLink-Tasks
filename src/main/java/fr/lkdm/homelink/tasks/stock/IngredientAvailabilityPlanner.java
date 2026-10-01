package fr.lkdm.homelink.tasks.stock;

import fr.lkdm.homecore.api.recipe.RecipeIngredient;
import fr.lkdm.homecore.api.stock.StockAvailability;
import fr.lkdm.homecore.api.stock.StockEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Allocates real items to a recipe's requirements without ever counting one twice.
 *
 * <p>The naive approach — asking each requirement separately whether enough matching
 * items exist — is wrong whenever requirements overlap. One oak plank satisfies "an oak
 * plank" and also satisfies "any plank", but it cannot satisfy both at once. A greedy
 * pass has the mirror problem: spending the only oak plank on the loose requirement can
 * fail a recipe that actually had a solution.</p>
 *
 * <p>So the allocation is solved as a whole, as a bounded flow from item stacks to
 * requirements. Inventory is cheaper than stock, so the solver covers as much as
 * possible and prefers the player's own items when it has a choice. Ingredient
 * predicates are the real ones, tags and alternatives included: a recipe accepting any
 * plank is not reported missing because the player has spruce rather than oak.</p>
 *
 * <p>Nothing is withdrawn or reserved. This is a calculation over an observation.</p>
 */
public final class IngredientAvailabilityPlanner {
    /** Largest number of distinct item buckets one calculation considers. */
    public static final int MAX_BUCKETS = 256;
    /** Largest number of units one calculation allocates, keeping the solver bounded. */
    public static final int MAX_UNITS = 1 << 22;

    private IngredientAvailabilityPlanner() { }

    /**
     * Plans the remaining operations for one viewing player.
     *
     * @param requirements recipe requirements for a single operation
     * @param operations operations still needed; zero means the objective is reached
     * @param inventory the viewing player's own stacks, each counted once
     * @param stock authorised stock already merged and deduplicated across providers
     * @return allocation for every requirement and the state to present
     */
    public static AvailabilityPlan plan(List<RecipeIngredient> requirements, int operations,
                                        List<ItemStack> inventory, StockContext stock) {
        Objects.requireNonNull(requirements, "requirements");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(stock, "stock");
        if (operations <= 0 || requirements.isEmpty()) return AvailabilityPlan.finished(stock.observedTick());

        int[] needs = new int[requirements.size()];
        for (int index = 0; index < requirements.size(); index++) {
            long need = (long) requirements.get(index).count() * operations;
            if (need > MAX_UNITS) return AvailabilityPlan.unsupported(stock.observedTick());
            needs[index] = (int) need;
        }
        var predicates = new java.util.AbstractList<net.minecraft.world.item.crafting.Ingredient>() {
            @Override public net.minecraft.world.item.crafting.Ingredient get(int index) { return requirements.get(index).ingredient(); }
            @Override public int size() { return requirements.size(); }
        };
        return allocate(predicates, needs, inventory, stock);
    }

    /** Allocates a whole project's remaining demand in one flow, across card boundaries. */
    public static AvailabilityPlan planDemand(List<net.minecraft.world.item.crafting.Ingredient> requirements,
                                               int[] quantities, List<ItemStack> inventory, StockContext stock) {
        if (requirements.size() != quantities.length) throw new IllegalArgumentException("Demand shape mismatch");
        return allocate(requirements, quantities.clone(), inventory, stock);
    }

    private static AvailabilityPlan allocate(List<net.minecraft.world.item.crafting.Ingredient> requirements,
                                             int[] needs, List<ItemStack> inventory, StockContext stock) {
        if (requirements.isEmpty()) return AvailabilityPlan.finished(stock.observedTick());
        if (requirements.size() > 128) return AvailabilityPlan.unsupported(stock.observedTick());
        long totalDemand = 0;
        for (int need : needs) {
            if (need < 1 || need > MAX_UNITS) return AvailabilityPlan.unsupported(stock.observedTick());
            totalDemand += need;
        }
        List<Bucket> buckets = buckets(inventory, stock);
        // Refuse to answer rather than block the server or invent a green or red result.
        if (totalDemand > MAX_UNITS || buckets.size() > MAX_BUCKETS) {
            return AvailabilityPlan.unsupported(stock.observedTick());
        }

        Allocation allocation = new Solver(requirements, needs, buckets).solve();
        List<IngredientAvailability> result = new ArrayList<>(requirements.size());
        boolean anyMissing = false;
        boolean anyUnverified = false;
        boolean anyStock = false;
        // Absence is only a fact when the whole configured scope was actually observed.
        boolean shortfallIsProven = !stock.configured() || stock.availability() == StockAvailability.COMPLETE;
        for (int index = 0; index < requirements.size(); index++) {
            int fromInventory = allocation.fromInventory()[index];
            int fromStock = allocation.fromStock()[index];
            int missing = needs[index] - fromInventory - fromStock;
            AvailabilityState state;
            if (missing == 0) {
                state = fromStock == 0 ? AvailabilityState.READY : AvailabilityState.IN_STORAGE;
            } else {
                state = shortfallIsProven ? AvailabilityState.MISSING : AvailabilityState.UNVERIFIED;
            }
            anyMissing |= state == AvailabilityState.MISSING;
            anyUnverified |= state == AvailabilityState.UNVERIFIED;
            anyStock |= state == AvailabilityState.IN_STORAGE;
            result.add(new IngredientAvailability(needs[index], fromInventory, fromStock, missing, state));
        }
        AvailabilityState overall = anyMissing ? AvailabilityState.MISSING
                : anyUnverified ? AvailabilityState.UNVERIFIED
                : anyStock ? AvailabilityState.IN_STORAGE : AvailabilityState.READY;
        return new AvailabilityPlan(result, overall, stock.access(), stock.configured(), stock.observedTick());
    }

    private static List<Bucket> buckets(List<ItemStack> inventory, StockContext stock) {
        List<Bucket> buckets = new ArrayList<>();
        // Each inventory stack is one bucket: a slot's items are available exactly once.
        for (ItemStack stack : inventory) {
            if (stack != null && !stack.isEmpty()) buckets.add(new Bucket(stack.copyWithCount(1), stack.getCount(), true));
        }
        for (StockEntry entry : stock.entries()) {
            long total = entry.total();
            if (total > 0) buckets.add(new Bucket(entry.variant(), (int) Math.min(MAX_UNITS, total), false));
        }
        return buckets;
    }

    /** One pool of identical items available exactly once. */
    private record Bucket(ItemStack variant, int count, boolean inventory) { }

    /** Units allocated to each requirement, split by where they came from. */
    private record Allocation(int[] fromInventory, int[] fromStock) { }

    /**
     * Successive shortest paths over a tiny flow network.
     *
     * <p>Maximising the flow answers "is there a valid assignment at all", which a greedy
     * choice cannot. Costing stock edges above inventory edges makes the solver prefer
     * the player's own items among the assignments that are equally good.</p>
     */
    private static final class Solver {
        private static final int INFINITE = Integer.MAX_VALUE / 4;

        private final int[] edgeTo;
        private final int[] edgeCapacity;
        private final int[] edgeCost;
        private final List<List<Integer>> outgoing = new ArrayList<>();
        private final int source;
        private final int sink;
        private final int bucketCount;
        private final int requirementCount;
        private final List<int[]> bucketEdges = new ArrayList<>();
        private final List<Boolean> bucketOrigin = new ArrayList<>();
        private int edges;

        Solver(List<net.minecraft.world.item.crafting.Ingredient> requirements, int[] needs, List<Bucket> buckets) {
            bucketCount = buckets.size();
            requirementCount = requirements.size();
            int nodes = bucketCount + requirementCount + 2;
            source = nodes - 2;
            sink = nodes - 1;
            int capacity = 2 * (bucketCount + requirementCount + bucketCount * requirementCount);
            edgeTo = new int[capacity];
            edgeCapacity = new int[capacity];
            edgeCost = new int[capacity];
            for (int node = 0; node < nodes; node++) outgoing.add(new ArrayList<>());
            for (int bucket = 0; bucket < bucketCount; bucket++) {
                Bucket pool = buckets.get(bucket);
                // Stock costs more than inventory, so an equally valid plan spends the player's own items first.
                add(source, bucket, pool.count(), pool.inventory() ? 0 : 1);
                int[] perRequirement = new int[requirementCount];
                for (int requirement = 0; requirement < requirementCount; requirement++) {
                    perRequirement[requirement] = requirements.get(requirement).test(pool.variant())
                            ? add(bucket, bucketCount + requirement, pool.count(), 0) : -1;
                }
                bucketEdges.add(perRequirement);
                bucketOrigin.add(pool.inventory());
            }
            for (int requirement = 0; requirement < requirementCount; requirement++) {
                add(bucketCount + requirement, sink, needs[requirement], 0);
            }
        }

        private int add(int from, int to, int capacity, int cost) {
            int forward = edges;
            edgeTo[edges] = to; edgeCapacity[edges] = capacity; edgeCost[edges] = cost;
            outgoing.get(from).add(edges++);
            edgeTo[edges] = from; edgeCapacity[edges] = 0; edgeCost[edges] = -cost;
            outgoing.get(to).add(edges++);
            return forward;
        }

        Allocation solve() {
            int nodes = outgoing.size();
            while (true) {
                int[] distance = new int[nodes];
                int[] previousEdge = new int[nodes];
                java.util.Arrays.fill(distance, INFINITE);
                java.util.Arrays.fill(previousEdge, -1);
                distance[source] = 0;
                // Costs are tiny non-negative integers, so a bounded relaxation is enough.
                for (int round = 0; round < nodes; round++) {
                    boolean relaxed = false;
                    for (int node = 0; node < nodes; node++) {
                        if (distance[node] == INFINITE) continue;
                        for (int edge : outgoing.get(node)) {
                            if (edgeCapacity[edge] <= 0) continue;
                            int candidate = distance[node] + edgeCost[edge];
                            if (candidate < distance[edgeTo[edge]]) {
                                distance[edgeTo[edge]] = candidate;
                                previousEdge[edgeTo[edge]] = edge ^ 1;
                                relaxed = true;
                            }
                        }
                    }
                    if (!relaxed) break;
                }
                if (distance[sink] == INFINITE) break;
                int amount = INFINITE;
                for (int node = sink; node != source; node = edgeTo[previousEdge[node]]) {
                    amount = Math.min(amount, edgeCapacity[previousEdge[node] ^ 1]);
                }
                for (int node = sink; node != source; node = edgeTo[previousEdge[node]]) {
                    edgeCapacity[previousEdge[node] ^ 1] -= amount;
                    edgeCapacity[previousEdge[node]] += amount;
                }
            }
            int[] fromInventory = new int[requirementCount];
            int[] fromStock = new int[requirementCount];
            for (int bucket = 0; bucket < bucketCount; bucket++) {
                int[] perRequirement = bucketEdges.get(bucket);
                boolean inventory = bucketOrigin.get(bucket);
                for (int requirement = 0; requirement < requirementCount; requirement++) {
                    int edge = perRequirement[requirement];
                    if (edge < 0) continue;
                    int used = edgeCapacity[edge ^ 1];
                    if (inventory) fromInventory[requirement] += used; else fromStock[requirement] += used;
                }
            }
            return new Allocation(fromInventory, fromStock);
        }
    }
}
