package io.ncbpfluffybear.fluffymachines.diagnostics;

import io.ncbpfluffybear.fluffymachines.items.BarrelContents;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Saved quantities must never be inferred from cosmetic barrel displays. */
class BarrelDoctorSafetyTest {

    private record Output(String identity, long amount) {}

    private static BarrelContents<Output> contents(long saved, Output first, Output second) {
        return BarrelContents.inspect(saved, null, first, second,
                (one, two) -> one.identity().equals(two.identity()), Output::amount);
    }

    @Test
    void zeroReserveWithTwoCargoOutputStacksIsNotAnEmptyBarrel() {
        var verified = contents(0, new Output("same-owner", 64), new Output("same-owner", 31));
        assertTrue(verified.safe());
        assertEquals(95L,
                BarrelDoctorSafety.confirmedOutputWithZeroReserve(0, verified));
        assertEquals(95L, verified.amount());
    }

    @Test
    void unsafeOldAndNewOutputIdentitiesCannotFabricateAReconciledCount() {
        var conflict = contents(0,
                new Output("historical-adventure-owner-A", 37),
                new Output("fresh-adventure-owner-A", 12));
        assertFalse(conflict.safe());
        assertEquals(BarrelContents.Rejection.OUTPUT_IDENTITY_MISMATCH, conflict.rejection());
        assertNull(BarrelDoctorSafety.confirmedOutputWithZeroReserve(0, conflict));
    }

    @Test
    void missingOrNonzeroOrCorruptReserveCannotClaimOutputOnlyClassification() {
        var verified = contents(0, new Output("same", 64), new Output("same", 31));
        assertNull(BarrelDoctorSafety.confirmedOutputWithZeroReserve(null, verified));
        assertNull(BarrelDoctorSafety.confirmedOutputWithZeroReserve(-1, verified));
        assertNull(BarrelDoctorSafety.confirmedOutputWithZeroReserve(95, verified));
        assertNull(BarrelDoctorSafety.confirmedOutputWithZeroReserve(0, null));
        assertNull(BarrelDoctorSafety.confirmedOutputWithZeroReserve(0, contents(0, null, null)));
    }

    @Test
    void physicalOutputDiagnosticNeverMutatesStoredItemOrAmounts() {
        Output first = new Output("typed-owner-A", 64);
        Output second = new Output("typed-owner-A", 31);
        var verified = contents(0, first, second);
        assertEquals(95L, BarrelDoctorSafety.confirmedOutputWithZeroReserve(0, verified));
        assertEquals(64, first.amount());
        assertEquals(31, second.amount());
        assertEquals("typed-owner-A", first.identity());
        assertEquals("typed-owner-A", second.identity());
    }

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
