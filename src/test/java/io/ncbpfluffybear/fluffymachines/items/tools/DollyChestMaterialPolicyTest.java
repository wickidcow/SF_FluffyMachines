package io.ncbpfluffybear.fluffymachines.items.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Locks Dolly's existing saved chest material and pairing semantics before
 * changing deprecated Lockable or ItemStack APIs. These are pure rules only:
 * they do not certify real inventory, PDC, lock, or restart recovery.
 */
class DollyChestMaterialPolicyTest {

    @Test
    void normalAndTrappedChestsOnlyPairWithTheirOwnType() {
        assertTrue(DollyChestMaterialPolicy.isCompatiblePair(Material.CHEST, Material.CHEST));
        assertTrue(DollyChestMaterialPolicy.isCompatiblePair(Material.TRAPPED_CHEST, Material.TRAPPED_CHEST));
        assertFalse(DollyChestMaterialPolicy.isCompatiblePair(Material.CHEST, Material.TRAPPED_CHEST));
        assertFalse(DollyChestMaterialPolicy.isCompatiblePair(Material.TRAPPED_CHEST, Material.CHEST));
    }

    @Test
    void unsupportedAndNullMaterialsNeverBecomeMovableChests() {
        for (Material material : new Material[] { Material.AIR, Material.BARREL, Material.CHEST_MINECART }) {
            assertFalse(DollyChestMaterialPolicy.isSupported(material));
            assertFalse(DollyChestMaterialPolicy.isCompatiblePair(Material.CHEST, material));
            assertFalse(DollyChestMaterialPolicy.isCompatiblePair(material, Material.CHEST));
        }
        assertFalse(DollyChestMaterialPolicy.isSupported(null));
        assertFalse(DollyChestMaterialPolicy.isCompatiblePair(Material.CHEST, null));
    }

    @Test
    void copperVariantsPreserveHistoricalPairingAcrossOxidationStates() {
        List<Material> copper = Arrays.stream(Material.values())
            .filter(material -> material.name().equals("COPPER_CHEST")
                || material.name().endsWith("_COPPER_CHEST"))
            .toList();
        assertFalse(copper.isEmpty(), "The supported 1.21.11+ API must expose copper chests");
        for (Material first : copper) {
            assertTrue(DollyChestMaterialPolicy.isCopperChest(first));
            assertTrue(DollyChestMaterialPolicy.isSupported(first));
            assertFalse(DollyChestMaterialPolicy.isCompatiblePair(first, Material.CHEST));
            assertFalse(DollyChestMaterialPolicy.isCompatiblePair(Material.TRAPPED_CHEST, first));
            for (Material second : copper) {
                assertTrue(DollyChestMaterialPolicy.isCompatiblePair(first, second),
                    first + " / " + second + " must retain Dolly's historic copper pairing");
            }
        }
    }

    @Test
    void missingOrCorruptSavedFirstChestFallsBackToOrdinaryChest() {
        assertEquals(Material.CHEST, DollyChestMaterialPolicy.storedOrChest(null));
        assertEquals(Material.CHEST, DollyChestMaterialPolicy.storedOrChest(""));
        assertEquals(Material.CHEST, DollyChestMaterialPolicy.storedOrChest("NOT_A_CHEST"));
        assertEquals(Material.CHEST, DollyChestMaterialPolicy.storedOrChest("BARREL"));
        assertEquals(Material.TRAPPED_CHEST,
            DollyChestMaterialPolicy.storedOrChest("TRAPPED_CHEST"));
    }

    @Test
    void missingOrIncompatibleSecondHalfKeepsTheOriginalFirstMaterial() {
        assertEquals(Material.CHEST, DollyChestMaterialPolicy.storedSecondOrFirst(Material.CHEST, null));
        assertEquals(Material.CHEST, DollyChestMaterialPolicy.storedSecondOrFirst(Material.CHEST, "TRAPPED_CHEST"));
        assertEquals(Material.TRAPPED_CHEST,
            DollyChestMaterialPolicy.storedSecondOrFirst(Material.TRAPPED_CHEST, "CHEST"));
        assertEquals(Material.CHEST,
            DollyChestMaterialPolicy.storedSecondOrFirst(Material.CHEST, "INVALID_CHEST"));
        assertEquals(Material.TRAPPED_CHEST,
            DollyChestMaterialPolicy.storedSecondOrFirst(Material.TRAPPED_CHEST, "TRAPPED_CHEST"));
    }

    @Test
    void savedCopperVariantRemainsSupportedAndFallsBackToItsOwnType() {
        int tested = 0;
        for (Material copper : Material.values()) {
            if (!DollyChestMaterialPolicy.isCopperChest(copper)) {
                continue;
            }
            tested++;
            assertEquals(copper, DollyChestMaterialPolicy.storedOrChest(copper.name()));
            assertEquals(copper,
                DollyChestMaterialPolicy.storedSecondOrFirst(copper, "CHEST"));
            assertEquals(copper,
                DollyChestMaterialPolicy.storedSecondOrFirst(copper, "NOT_A_CHEST"));
        }
        assertTrue(tested > 0, "Copper fallback test must exercise actual copper chest materials");
    }
}
