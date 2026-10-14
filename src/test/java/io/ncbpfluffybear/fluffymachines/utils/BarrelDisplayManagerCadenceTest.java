package io.ncbpfluffybear.fluffymachines.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression tests for the cosmetic-only barrel display inspection schedule. */
class BarrelDisplayManagerCadenceTest {

    @Test
    void skipsUnchangedDisplayWorkUntilTheDeadline() {
        assertFalse(BarrelDisplayManager.shouldInspect(100L, 120L));
        assertFalse(BarrelDisplayManager.shouldInspect(119L, 120L));
        assertTrue(BarrelDisplayManager.shouldInspect(120L, 120L));
        assertTrue(BarrelDisplayManager.shouldInspect(121L, 120L));
    }

    @Test
    void unevenMachineTickCadenceNeverGetsPhaseLockedOut() {
        List<Long> inspections = new ArrayList<>();
        long nextInspection = 20L;

        // A repeating modulo check can permanently skip a machine whose ticker is phase-aligned.
        // Deadlines based on its last actual inspection must not.
        for (long gameTime = 0L; gameTime <= 100L; gameTime += 7L) {
            if (BarrelDisplayManager.shouldInspect(gameTime, nextInspection)) {
                inspections.add(gameTime);
                nextInspection = gameTime + 20L;
            }
        }

        assertEquals(List.of(21L, 42L, 63L, 84L), inspections);
    }

    @Test
    void resettingWorldGameTimeRefreshesImmediately() {
        assertTrue(BarrelDisplayManager.shouldInspect(0L, 100020L));
        assertTrue(BarrelDisplayManager.shouldInspect(100L, 100000L));
    }

    @Test
    void abandonedEmptyStatesExpireWithoutRemovingActiveBarrelStates() {
        assertFalse(BarrelDisplayManager.shouldPruneEmpty(105L, 120L));
        assertFalse(BarrelDisplayManager.shouldPruneEmpty(720L, 120L));
        assertTrue(BarrelDisplayManager.shouldPruneEmpty(721L, 120L));
        assertTrue(BarrelDisplayManager.shouldPruneEmpty(0L, 100020L));
    }

    @Test
    void everyBlockStillGetsItsOwnDeadline() {
        assertTrue(BarrelDisplayManager.shouldInspect(110L, 110L));
        assertFalse(BarrelDisplayManager.shouldInspect(110L, 125L));
        assertTrue(BarrelDisplayManager.shouldInspect(130L, 125L));
    }
}
