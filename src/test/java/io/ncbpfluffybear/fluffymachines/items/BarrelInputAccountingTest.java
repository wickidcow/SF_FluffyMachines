package io.ncbpfluffybear.fluffymachines.items;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BarrelInputAccountingTest {
    @Test void fullConsumptionConservesQuantity() {
        assertEquals(64, BarrelInputAccounting.consumed(64, 0, 64, true));
    }

    @Test void partialConsumptionCountsOnlyObservedDelta() {
        assertEquals(12, BarrelInputAccounting.consumed(64, 52, 20, true));
    }

    @Test void rejectedConsumptionNeverCreditsStoredCounter() {
        assertEquals(0, BarrelInputAccounting.consumed(64, 64, 64, true));
    }

    @Test void replacementWithDifferentMetadataIsUnsafeEvenForSameId() {
        assertEquals(-1, BarrelInputAccounting.consumed(64, 52, 20, false));
        assertEquals(-1, BarrelInputAccounting.consumed(64, 64, 64, false));
    }

    @Test void unexpectedRemovalAndCorruptAmountsAreUnsafe() {
        assertEquals(-1, BarrelInputAccounting.consumed(64, 0, 20, true));
        assertEquals(-1, BarrelInputAccounting.consumed(64, 65, 20, true));
        assertEquals(-1, BarrelInputAccounting.consumed(64, -1, 20, true));
        assertEquals(-1, BarrelInputAccounting.consumed(0, 0, 1, true));
        assertEquals(-1, BarrelInputAccounting.consumed(64, 60, 0, true));
        assertEquals(-1, BarrelInputAccounting.consumed(64, 60, 65, true));
    }

    @Test void everyValidConsumptionConservesObservedInput() {
        for (int before = 1; before <= 64; before++) {
            for (int requested = 1; requested <= before; requested++) {
                for (int delta = 0; delta <= requested; delta++) {
                    int after = before - delta;
                    int observed = BarrelInputAccounting.consumed(before, after, requested, true);
                    assertEquals(delta, observed);
                    assertEquals(before, observed + after);
                }
            }
        }
    }
}
