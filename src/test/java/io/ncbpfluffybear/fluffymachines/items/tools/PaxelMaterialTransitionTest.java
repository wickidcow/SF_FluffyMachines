package io.ncbpfluffybear.fluffymachines.items.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Pins the original Paxel tool-type transitions before migrating deprecated
 * ItemStack#setType. These pure selection tests do not claim item metadata,
 * saved Slimefun identity, or native server migration parity.
 */
class PaxelMaterialTransitionTest {

    @Test
    void everyNetheriteVariantStaysNetheriteForAllMiningCategories() {
        for (Material original : new Material[] {
            Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE, Material.NETHERITE_SHOVEL
        }) {
            assertEquals(Material.NETHERITE_SHOVEL,
                Paxel.materialForBlock(original, true, false), original.toString());
            assertEquals(Material.NETHERITE_AXE,
                Paxel.materialForBlock(original, false, true), original.toString());
            assertEquals(Material.NETHERITE_PICKAXE,
                Paxel.materialForBlock(original, false, false), original.toString());
            assertEquals(Material.NETHERITE_AXE,
                Paxel.materialForHit(original), original.toString());
        }
    }

    @Test
    void everyDiamondVariantStaysDiamondForAllMiningCategories() {
        for (Material original : new Material[] {
            Material.DIAMOND_PICKAXE, Material.DIAMOND_AXE, Material.DIAMOND_SHOVEL
        }) {
            assertEquals(Material.DIAMOND_SHOVEL,
                Paxel.materialForBlock(original, true, false), original.toString());
            assertEquals(Material.DIAMOND_AXE,
                Paxel.materialForBlock(original, false, true), original.toString());
            assertEquals(Material.DIAMOND_PICKAXE,
                Paxel.materialForBlock(original, false, false), original.toString());
            assertEquals(Material.DIAMOND_AXE,
                Paxel.materialForHit(original), original.toString());
        }
    }

    @Test
    void shovelCategoryWinsWhenBothFlagsMatch() {
        assertEquals(Material.NETHERITE_SHOVEL,
            Paxel.materialForBlock(Material.NETHERITE_AXE, true, true));
        assertEquals(Material.DIAMOND_SHOVEL,
            Paxel.materialForBlock(Material.DIAMOND_AXE, true, true));
    }

    @Test
    void unknownStartingTypeKeepsHistoricalDiamondFallback() {
        assertEquals(Material.DIAMOND_PICKAXE,
            Paxel.materialForBlock(Material.STONE_PICKAXE, false, false));
        assertEquals(Material.DIAMOND_AXE,
            Paxel.materialForBlock(Material.STONE_PICKAXE, false, true));
        assertEquals(Material.DIAMOND_SHOVEL,
            Paxel.materialForBlock(Material.STONE_PICKAXE, true, false));
        assertEquals(Material.DIAMOND_AXE, Paxel.materialForHit(Material.STONE_PICKAXE));
    }
}
