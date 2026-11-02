package io.ncbpfluffybear.fluffymachines.items.tools;

import javax.annotation.Nullable;
import org.bukkit.Material;

/**
 * Original Dolly chest material and double-chest pairing rules.
 *
 * <p>Kept separate from Dolly's plugin-backed item state so that the historic
 * fallback and copper-chest behavior can be regression-tested without a
 * live Slimefun database. This does not change lock, inventory, or item-meta
 * persistence and is not evidence of native restart compatibility.</p>
 */
final class DollyChestMaterialPolicy {

    private DollyChestMaterialPolicy() {
    }

    static boolean isSupported(@Nullable Material material) {
        return material == Material.CHEST
            || material == Material.TRAPPED_CHEST
            || isCopperChest(material);
    }

    static boolean isCopperChest(@Nullable Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.equals("COPPER_CHEST") || name.endsWith("_COPPER_CHEST");
    }

    static boolean isCompatiblePair(Material first, @Nullable Material second) {
        if (!isSupported(first) || !isSupported(second)) {
            return false;
        }

        if (isCopperChest(first)) {
            return isCopperChest(second);
        }

        // Normal and trapped chests may only form doubles with the same type.
        return first == second;
    }
}
