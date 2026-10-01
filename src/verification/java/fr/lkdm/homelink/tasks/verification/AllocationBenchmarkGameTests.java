package fr.lkdm.homelink.tasks.verification;

import fr.lkdm.homecore.api.recipe.RecipeIngredient;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import fr.lkdm.homelink.tasks.stock.AvailabilityPlan;
import fr.lkdm.homelink.tasks.verification.baseline.BaselineAvailabilityPlanner;
import fr.lkdm.homelink.tasks.stock.IngredientAvailabilityPlanner;
import fr.lkdm.homelink.tasks.stock.StockContext;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Synthetic 20-player/3-pin allocator workload and real payload serialization; no network clients. */
@GameTestHolder(TasksValidation.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AllocationBenchmarkGameTests {
    private static final List<RecipeIngredient> REQUIREMENTS = java.util.stream.IntStream.range(0, 9)
            .mapToObj(index -> new RecipeIngredient(Ingredient.of(Items.OAK_PLANKS, Items.SPRUCE_PLANKS), index + 1)).toList();
    private record Measurement(long nanoseconds, long allocatedBytes, int calculations) { }

    private static long allocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean allocation && allocation.isThreadAllocatedMemorySupported()) {
            if (!allocation.isThreadAllocatedMemoryEnabled()) allocation.setThreadAllocatedMemoryEnabled(true);
            return allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
        return -1;
    }

    private static AvailabilityPlan calculate(boolean baseline, int player) {
        var inventory = List.of(new ItemStack(Items.OAK_PLANKS, 16 + player), new ItemStack(Items.SPRUCE_PLANKS, 32));
        return baseline ? BaselineAvailabilityPlanner.plan(REQUIREMENTS, 2, inventory, StockContext.inventoryOnly(100))
                : IngredientAvailabilityPlanner.plan(REQUIREMENTS, 2, inventory, StockContext.inventoryOnly(100));
    }

    private static Measurement measure(boolean baseline) {
        long allocated = allocatedBytes(), start = System.nanoTime();
        for (int period = 0; period < 5; period++) for (int player = 0; player < 20; player++)
            for (int pin = 0; pin < 3; pin++) calculate(baseline, player);
        long nanos = System.nanoTime() - start, end = allocatedBytes();
        return new Measurement(nanos, allocated < 0 ? -1 : end - allocated, 300);
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void twentyPlayersWithThreePinsHaveBoundedWorkAndMeasuredPayloads(GameTestHelper helper) {
        for (int warmup = 0; warmup < 500; warmup++) {
            calculate(true, warmup % 20); calculate(false, warmup % 20);
        }
        for (int player = 0; player < 20; player++) helper.assertTrue(calculate(true, player).equals(calculate(false, player)),
                "Allocator refactor preserves the baseline's allocation for every synthetic player");
        var baseline = measure(true); var candidate = measure(false);
        int total = 0, peak = 0;
        var budget = new fr.lkdm.homelink.tasks.server.CalculationBudget();
        for (int tick = 0; tick < 20; tick++) {
            int work = 0;
            for (int player = 0; player < 20; player++) if (player % 20 == tick % 20) {
                for (int pin = 0; pin < 3; pin++) {
                    helper.assertTrue(budget.claim(tick), "All normal staggered HUD work fits the global ceiling");
                    work++; total++;
                }
            }
            peak = Math.max(peak, work);
        }
        helper.assertTrue(total == 60 && peak == 3, "Twenty players still get sixty plans, spread into three per tick");
        var limiter = new fr.lkdm.homelink.tasks.server.RequestBudget();
        int requests = 0;
        for (int player = 0; player < 20; player++) {
            UUID identity = UUID.randomUUID();
            for (int tick = 0; tick < 100; tick++) if (limiter.claim(identity, tick)) requests++;
        }
        helper.assertTrue(requests == 160, "Twenty abusive request streams get eight recalculations each per window");
        long bytes = 0;
        for (int player = 0; player < 20; player++) {
            var views = new ArrayList<TaskPackets.PinnedView>();
            for (int pin = 0; pin < 3; pin++) views.add(new TaskPackets.PinnedView(UUID.randomUUID(), UUID.randomUUID(),
                    "Wood components " + player + "/" + pin, TaskStatus.IN_PROGRESS, new ItemStack(Items.OAK_PLANKS), 4, 64,
                    calculate(false, player).state(), false));
            var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess(),
                    net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
            try {
                TaskPackets.PinnedTasks.CODEC.encode(buffer, new TaskPackets.PinnedTasks(views, Optional.empty()));
                bytes += buffer.readableBytes();
                helper.assertTrue(TaskPackets.PinnedTasks.CODEC.decode(buffer).pinned().size() == 3, "Measured payload also decodes");
            } finally { buffer.release(); }
        }
        try {
            var root = Path.of(System.getProperty("tasks.sourceRoot"));
            var digest = MessageDigest.getInstance("SHA-256");
            String sourceHash = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(root.resolve(
                    "src/main/java/fr/lkdm/homelink/tasks/stock/IngredientAvailabilityPlanner.java"))));
            String baselineHash = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(root.resolve(
                    "integration/benchmark-baseline/IngredientAvailabilityPlanner.java.txt"))));
            var result = Map.ofEntries(Map.entry("workload", "synthetic 20 identities / 3 pins, nine overlapping ingredients, inventory only"),
                    Map.entry("baselineAllocatorSha256", baselineHash), Map.entry("candidateAllocatorSha256", sourceHash),
                    Map.entry("baseline", baseline), Map.entry("candidate", candidate), Map.entry("pinnedPayloadBytesPer20Ticks", bytes),
                    Map.entry("baselinePeakCalculations", 60), Map.entry("staggeredPeakCalculations", peak),
                    Map.entry("acceptedRequestsPer100Ticks", requests), Map.entry("javaVersion", System.getProperty("java.version")));
            var output = root.resolve("build/validation/benchmark.json"); Files.createDirectories(output.getParent());
            Files.writeString(output, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
            com.mojang.logging.LogUtils.getLogger().info("TASKS_SYNTHETIC_BENCHMARK_OK: {}", output);
        } catch (Exception failure) { throw new IllegalStateException("Could not record benchmark evidence", failure); }
        helper.succeed();
    }
}
