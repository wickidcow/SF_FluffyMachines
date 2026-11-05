package io.ncbpfluffybear.fluffymachines.items;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BarrelTransferMathTest {
    @Test void fullyAcceptedDeductsOnlyTheOffer() {
        assertEquals(64, BarrelTransferMath.accepted(64, 0));
    }

    @Test void partialTransferDeductsOnlyAcceptedPortion() {
        assertEquals(7, BarrelTransferMath.accepted(64, 57));
    }

    @Test void rejectedTransferCannotDeductStorage() {
        assertEquals(0, BarrelTransferMath.accepted(64, 64));
    }

    @Test void invalidTransferReportsDoNotSilentlyCorruptCounts() {
        assertThrows(IllegalArgumentException.class, () -> BarrelTransferMath.accepted(64, 65));
        assertThrows(IllegalArgumentException.class, () -> BarrelTransferMath.accepted(64, -1));
        assertThrows(IllegalArgumentException.class, () -> BarrelTransferMath.accepted(0, 0));
    }

    @Test void everyValidRemainderConservesTheOffer() {
        for (int amount = 1; amount <= 64; amount++) {
            for (int remainder = 0; remainder <= amount; remainder++) {
                int accepted = BarrelTransferMath.accepted(amount, remainder);
                assertEquals(amount, accepted + remainder);
            }
        }
    }
}
