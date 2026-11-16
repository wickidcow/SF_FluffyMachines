package io.ncbpfluffybear.fluffymachines.items.tools;

import org.bukkit.Material;
import org.bukkit.TreeType;
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

    /** Bukkit TreeType names are not always the same as sapling Material names. */
    static TreeType getTreeFromSapling(Material m) {
        if (m == null) return null;
        String type = m.name();
        if (type.equals("OAK_SAPLING")) return TreeType.TREE;
        if (type.equals("SPRUCE_SAPLING")) return TreeType.REDWOOD;
        if (type.equals("JUNGLE_SAPLING")) return TreeType.SMALL_JUNGLE;
        if (type.equals("MANGROVE_PROPAGULE")) return TreeType.MANGROVE;
        if (type.equals("FLOWERING_AZALEA")) return TreeType.AZALEA;
        try {
            // Future saplings whose enum has no matching TreeType are refused
            // before spending water; their metadata and block are untouched.
            if (!type.endsWith("_SAPLING")) return null;
            return TreeType.valueOf(type.substring(0, type.length() - "_SAPLING".length()));
        } catch (IllegalArgumentException unknownTree) {
            return null;
        }
    }

    static void applyModernBoneMeal(Block sapling, BlockFace facing) {
        // Paper versions can return false even if growth succeeds; the original
        // modern branch deliberately ignored the result and did not retry.
        sapling.applyBoneMeal(facing);
    }
}
