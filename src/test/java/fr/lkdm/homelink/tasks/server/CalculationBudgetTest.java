package fr.lkdm.homelink.tasks.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculationBudgetTest {
    @Test void projectPlanFitsAlongsideThreeHudPinsAndARefusalConsumesNothing() {
        var budget = new CalculationBudget();
        for (int i = 0; i < 3; i++) assertTrue(budget.claim(100));
        assertTrue(budget.claim(100, CalculationBudget.PROJECT_COST));
        assertFalse(budget.claim(100, CalculationBudget.PROJECT_COST));
        assertTrue(budget.claim(100));
        assertFalse(budget.claim(100));
        assertTrue(budget.claim(101, CalculationBudget.PROJECT_COST));
        assertTrue(budget.claim(101, CalculationBudget.PROJECT_COST));
        assertFalse(budget.claim(101));
    }
    @Test void simultaneousHudAndRequestsShareOneCeiling() {
        var budget = new CalculationBudget();
        for (int i = 0; i < CalculationBudget.MAX_PER_TICK; i++) assertTrue(budget.claim(100));
        for (int i = 0; i < 60; i++) assertFalse(budget.claim(100));
        assertEquals(8, budget.accepted());
        assertEquals(60, budget.refused());
        assertTrue(budget.claim(101));
    }
}
