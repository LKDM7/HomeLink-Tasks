package fr.lkdm.homelink.tasks.stock;

import fr.lkdm.homecore.api.stock.*;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockContextTest {
    private static StockSnapshot snapshot(long tick) {
        return new StockSnapshot(StockAvailability.COMPLETE, StockAccess.READ_AND_WITHDRAW,
                List.of(new StockEntry(new ItemStack(Items.DIAMOND), Map.of(new StockSourceId(ResourceLocation.parse("minecraft:overworld"), BlockPos.ZERO), 8L))), 1, tick);
    }
    @Test void oldProviderCannotAssertAvailabilityOrDiscloseOldQuantities() {
        var context = StockContext.merge(List.of(snapshot(10)), true, 300);
        assertEquals(StockAvailability.UNAVAILABLE, context.availability()); assertTrue(context.entries().isEmpty());
        assertEquals(10, context.observedTick());
    }
    @Test void timestampFromTheFutureIsNotFreshEvidence() {
        var context = StockContext.merge(List.of(snapshot(101)), true, 100);
        assertEquals(StockAvailability.UNAVAILABLE, context.availability()); assertTrue(context.entries().isEmpty());
    }
    @Test void ageAtTheBoundaryAndDuplicatePhysicalSourceRemainCountedOnce() {
        var context = StockContext.merge(List.of(snapshot(100), snapshot(200)), true, 300);
        assertEquals(StockAvailability.COMPLETE, context.availability());
        assertEquals(8, context.entries().getFirst().total()); assertEquals(100, context.observedTick());
    }
}
