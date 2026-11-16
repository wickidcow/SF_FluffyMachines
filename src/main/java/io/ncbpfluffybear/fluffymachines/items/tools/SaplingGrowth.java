package io.ncbpfluffybear.fluffymachines.items.tools;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** The supported (1.21.11+) sapling growth path; never replaces player items or block metadata. */
final class SaplingGrowth {

    private SaplingGrowth() {}

    /**
     * A disabled ExoticGarden sapling must not spend water or emit success
     * particles. Ordinary saplings retain the original bonemeal behavior.
     */
    static boolean canConsumeWater(boolean isExoticPlant, double exoticSuccessChance) {
        return !isExoticPlant || exoticSuccessChance > 0.0D;
    }

    static void applyModernBoneMeal(Block sapling, BlockFace facing) {
        // Paper versions can return false even if growth succeeds; the original
        // modern branch deliberately ignored the result and did not retry.
        sapling.applyBoneMeal(facing);
    }
}
