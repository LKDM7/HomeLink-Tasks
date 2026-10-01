package fr.lkdm.homelink.tasks.server;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homecore.api.production.ProductionLog;
import fr.lkdm.homecore.api.recipe.RecipeDescriptor;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.objective.CraftObjective;
import fr.lkdm.homelink.tasks.production.ContributionAllocator;
import fr.lkdm.homelink.tasks.production.CraftIndex;
import fr.lkdm.homelink.tasks.production.CraftTracker;
import fr.lkdm.homelink.tasks.recipe.RecipeResolver;
import fr.lkdm.homelink.tasks.stock.AuthorizedStockQuery;
import fr.lkdm.homelink.tasks.stock.AvailabilityPlan;
import fr.lkdm.homelink.tasks.stock.IngredientAvailabilityPlanner;
import fr.lkdm.homelink.tasks.stock.PlayerInventoryView;
import fr.lkdm.homelink.tasks.stock.StockContext;
import fr.lkdm.homelink.tasks.task.TaskCard;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The server-side owner of boards, progress and every decision about them.
 *
 * <p>Boards, members, permissions, recipes actually used, progress, receipts, stock
 * reads and the assignment of production to cards are decided here. A client draws and
 * asks; it never decides. In particular, no packet claiming that a player crafted
 * something is believed: progress comes only from the production receipts this manager
 * subscribes to.</p>
 */
public final class TaskManager {
    private static final Map<MinecraftServer, TaskManager> INSTANCES = new IdentityHashMap<>();

    private final MinecraftServer server;
    private final TaskSavedData data;
    private final CraftIndex index = new CraftIndex();
    private final CraftTracker tracker;
    private final RequestBudget budget = new RequestBudget();
    private final CalculationBudget calculations = new CalculationBudget();
    public CalculationBudget calculations() { return calculations; }
    private final DisplayQueries displayQueries = new DisplayQueries();
    private final RequestLedger requests = new RequestLedger();
    public RequestLedger requests() { return requests; }
    public DisplayQueries displayQueries() { return displayQueries; }
    private ProductionLog.Subscription subscription;

    private TaskManager(MinecraftServer server) {
        this.server = server;
        this.data = TaskSavedData.get(server);
        this.tracker = new CraftTracker(data.deduplicator());
        index.rebuild(data.boards());
    }

    /** Returns the manager of a running server, creating it on first use.
     * @param server running server
     * @return server-scoped manager
     */
    public static synchronized TaskManager get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) throw new IllegalStateException("Task management requires the server thread");
        return INSTANCES.computeIfAbsent(server, TaskManager::new);
    }

    /** Starts listening for finished batches.
     * @param server running server
     */
    public static synchronized void start(MinecraftServer server) {
        TaskManager manager = get(server);
        if (manager.subscription != null) return;
        manager.subscription = DashboardAPI.production(server).subscribe(manager::onProduced);
    }

    /**
     * Releases listeners, caches and subscriptions when the server stops.
     *
     * <p>Nothing is carried over to another run, and no production is caught up for the
     * time the world was not simulated.</p>
     *
     * @param server stopping server
     */
    public static synchronized void stop(MinecraftServer server) {
        TaskManager manager = INSTANCES.remove(Objects.requireNonNull(server, "server"));
        if (manager != null && manager.subscription != null) manager.subscription.close();
        fr.lkdm.homelink.tasks.network.TaskSubscriptions.stop(server);
    }

    private void onProduced(fr.lkdm.homecore.api.production.ProductionReceipt receipt) {
        List<ContributionAllocator.Share> credited = tracker.credit(receipt, new CraftTracker.Context() {
            @Override public List<TaskCard> candidates(ItemStack produced) { return index.candidates(produced); }
            @Override public Optional<TaskBoard> board(UUID boardId) { return data.board(boardId); }
            @Override public Optional<UUID> trackedCard(UUID player) { return data.trackedCard(player); }
            @Override public Optional<UUID> currentEpoch() { return Optional.of(DashboardAPI.production(server).epoch()); }
        });
        // The deduplicator changed even when no card accepted this receipt.
        data.setDirty();
        java.util.Set<UUID> changedBoards = new java.util.HashSet<>();
        for (var share : credited) {
            index.candidates(receipt.result()).stream().filter(card -> card.id().equals(share.card()))
                    .map(TaskCard::boardId).forEach(changedBoards::add);
        }
        for (UUID boardId : changedBoards) data.board(boardId).ifPresent(board -> {
            board.touch();
            fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(server).broadcast(board);
            for (var share : credited) board.card(share.card()).filter(card -> card.status() == fr.lkdm.homelink.tasks.task.TaskStatus.DONE)
                    .ifPresent(card -> {
                        for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                            if (BoardPermissions.canView(board, viewer.getUUID())) viewer.displayClientMessage(
                                    net.minecraft.network.chat.Component.translatable("screen.homelink_tasks.finished_notice", card.title()), true);
                        }
                    });
        });
    }

    /** Returns the persistent data this manager owns.
     * @return saved data
     */
    public TaskSavedData data() { return data; }

    /** Returns the index of crafting cards by wanted item.
     * @return craft index
     */
    public CraftIndex index() { return index; }

    /** Returns the per-player request budget.
     * @return request budget
     */
    public RequestBudget budget() { return budget; }

    /** Returns the owning server.
     * @return running server
     */
    public MinecraftServer server() { return server; }

    /** Records a card that may now receive production.
     * @param card card to index
     */
    public void indexCard(TaskCard card) {
        card.objective().filter(objective -> objective.activationStart().isEmpty()
                && objective.activationTick() == server.overworld().getGameTime())
                .ifPresent(objective -> objective.setActivationStart(DashboardAPI.production(server).startStamp()));
        index.add(card); data.setDirty();
    }

    /** Drops a card from the production index.
     * @param card card to drop
     */
    public void unindexCard(TaskCard card) { index.remove(card); data.setDirty(); }

    /**
     * Computes what one viewing player still needs for a crafting card.
     *
     * <p>The result is personal: the inventory part is the asking player's own, so two
     * players looking at the same shared card legitimately see different colours while
     * the card's production counter stays common.</p>
     *
     * @param player authenticated player asking
     * @param board board the card belongs to
     * @param card crafting card
     * @return allocation and the state to present
     */
    public AvailabilityPlan plan(ServerPlayer player, TaskBoard board, TaskCard card) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(card, "card");
        long tick = server.overworld().getGameTime();
        if (!BoardPermissions.canView(board, player.getUUID())) return AvailabilityPlan.unsupported(tick);
        CraftObjective objective = card.objective().orElse(null);
        if (objective == null) return card.type() == fr.lkdm.homelink.tasks.task.TaskType.CRAFT
                ? AvailabilityPlan.unsupported(tick) : AvailabilityPlan.finished(tick);
        // A finished objective asks for nothing; its materials are not requested again.
        if (objective.complete()) return AvailabilityPlan.finished(tick);
        if (!calculations.claim(server.getTickCount())) return new AvailabilityPlan(List.of(),
                fr.lkdm.homelink.tasks.stock.AvailabilityState.UNVERIFIED,
                fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY, board.networkId().isPresent(), tick);

        // Resolved again from the running server, so a reloaded datapack cannot leave a stale plan.
        RecipeDescriptor descriptor = RecipeResolver.describe(server, objective.recipeId()).orElse(null);
        if (descriptor == null) return AvailabilityPlan.unsupported(tick);
        int operations = descriptor.operationsFor(objective.remaining());
        return IngredientAvailabilityPlanner.plan(descriptor.ingredients(), operations,
                PlayerInventoryView.countable(player), stockFor(player, board, descriptor.ingredients().stream()
                        .map(fr.lkdm.homecore.api.recipe.RecipeIngredient::ingredient).toList()));
    }

    public StockContext stockFor(ServerPlayer player, TaskBoard board, List<net.minecraft.world.item.crafting.Ingredient> ingredients) {
        long tick = server.overworld().getGameTime();
        List<ItemStack> candidates = ingredients.stream()
                .map(net.minecraft.world.item.crafting.Ingredient::getItems)
                .flatMap(java.util.Arrays::stream)
                .map(stack -> stack.copyWithCount(1))
                .limit(fr.lkdm.homecore.api.stock.StockRequest.MAX_VARIANTS + 1L)
                .toList();
        List<ItemStack> wanted = candidates.stream().limit(fr.lkdm.homecore.api.stock.StockRequest.MAX_VARIANTS).toList();
        var physical = fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(server).stockScreen(player);
        StockContext stock;
        boolean hasStorage = net.neoforged.fml.ModList.get().isLoaded("homelink_storage")
                || board.networkId().map(network -> fr.lkdm.homecore.api.DashboardAPI.networks(server).getDevices(network).stream()
                        .map(id -> fr.lkdm.homecore.api.DashboardAPI.devices(server).get(id).orElse(null))
                        .anyMatch(device -> device != null && device.capability(fr.lkdm.homecore.api.stock.StockProvider.CAPABILITY).isPresent())).orElse(false);
        if (!hasStorage) stock = StockContext.inventoryOnly(tick);
        else if (board.networkId().isPresent() && (physical == null
                || !board.networkId().equals(physical.networkId())
                || !fr.lkdm.homelink.tasks.server.TaskAccess.canUseScreen(player, physical))) {
            stock = new StockContext(List.of(), fr.lkdm.homecore.api.stock.StockAvailability.UNAVAILABLE,
                    fr.lkdm.homecore.api.stock.StockAccess.READ_ONLY, true, tick);
        } else stock = AuthorizedStockQuery.observe(player, board, wanted);
        if (candidates.size() > wanted.size() && stock.configured()) stock = new StockContext(stock.entries(),
                fr.lkdm.homecore.api.stock.StockAvailability.PARTIAL, stock.access(), true, stock.observedTick());
        return stock;
    }
}
