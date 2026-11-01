package io.ncbpfluffybear.fluffymachines.machines;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlternateElevatorPlateWorldBoundsTest {

    @Test
    void vanillaOverworldIncludesBottomLayerAndExcludesBuildCeiling() {
        assertArrayEquals(new int[] {319, -64}, AlternateElevatorPlate.scanBounds(-64, 320));
        assertScanCoversEveryValidY(-64, 320);
    }

    @Test
    void legacyWorldDoesNotScanBelowZero() {
        assertArrayEquals(new int[] {255, 0}, AlternateElevatorPlate.scanBounds(0, 256));
        assertScanCoversEveryValidY(0, 256);
    }

    @Test
    void customWorldSupportsNonstandardNegativeAndPositiveLimits() {
        assertScanCoversEveryValidY(-128, 512);
        assertScanCoversEveryValidY(32, 96);
    }

    @Test
    void singleLayerWorldIncludesItsOnlyValidBlock() {
        assertArrayEquals(new int[] {42, 42}, AlternateElevatorPlate.scanBounds(42, 43));
        assertScanCoversEveryValidY(42, 43);
    }

    private static void assertScanCoversEveryValidY(int minHeight, int maxHeight) {
        int[] bounds = AlternateElevatorPlate.scanBounds(minHeight, maxHeight);
        int first = Integer.MIN_VALUE;
        int last = Integer.MIN_VALUE;
        int count = 0;
        for (int y = bounds[0]; y >= bounds[1]; y--) {
            if (count == 0) {
                first = y;
            }
            last = y;
            count++;
        }
        assertEquals(maxHeight - 1, first, "must not scan exclusive upper limit");
        assertEquals(minHeight, last, "must include lowest valid block");
        assertEquals(maxHeight - minHeight, count, "must visit each valid Y exactly once");
    }
}
