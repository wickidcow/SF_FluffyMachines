package io.ncbpfluffybear.fluffymachines.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Saved quantities must never be inferred from cosmetic barrel displays. */
class BarrelDoctorSafetyTest {

    @Test
    void unknownCounterCannotAuthorizeCapacityMutation() {
        // Even if the cosmetic slot is a barrier or missing, no persisted
        // value proves an old reserve is empty.
        assertNull(BarrelDoctorSafety.safeCapacityRepair(null, 172800));
    }

    @Test
    void negativeOrUntrustedMaximumIsAlwaysReadOnly() {
        assertNull(BarrelDoctorSafety.safeCapacityRepair(-1, 172800));
        assertNull(BarrelDoctorSafety.safeCapacityRepair(100, 0));
        assertNull(BarrelDoctorSafety.safeCapacityRepair(100, -10));
    }

    @Test
    void verifiedReserveNeverShrinksOnCapacityRepair() {
        assertEquals(172800, BarrelDoctorSafety.safeCapacityRepair(0, 172800));
        assertEquals(172800, BarrelDoctorSafety.safeCapacityRepair(20, 172800));
        assertEquals(172801, BarrelDoctorSafety.safeCapacityRepair(172801, 172800));
        assertEquals(Integer.MAX_VALUE, BarrelDoctorSafety.safeCapacityRepair(Integer.MAX_VALUE, 172800));
    }

    @Test
    void capacityDecisionDependsOnlyOnVerifiedSavedQuantityNotDisplay() {
        // A read-only missing display does not validate or invalidate the
        // separate persisted count; callers must independently check both.
        Integer capacity = BarrelDoctorSafety.safeCapacityRepair(34_560_000, 172800);
        assertEquals(34_560_000, capacity);
        assertNull(BarrelDoctorSafety.safeCapacityRepair(null, 172800));
    }
}
