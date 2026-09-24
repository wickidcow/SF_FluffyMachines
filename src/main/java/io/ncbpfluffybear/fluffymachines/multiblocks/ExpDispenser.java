package io.ncbpfluffybear.fluffymachines.multiblocks;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Dispenses multiple bottles of exp at once
 */
public class ExpDispenser extends MultiBlockMachine {

    private static final int EXP_PER_BOTTLE = 7; // Average exp per bottle

    public ExpDispenser(ItemGroup itemGroup, SlimefunItemStack item, ItemStack[] recipe) {
        super(itemGroup, item, recipe, BlockFace.SELF);
    }

    @Override
    public void onInteract(Player p, Block b) {
        Block dispenser = b.getRelative(0, -1, 0);
        Container container = (Container) dispenser.getState();
        int experience = 0;

        for (ItemStack bottle : container.getInventory().getContents()) {
            if (bottle != null && bottle.getType() == Material.EXPERIENCE_BOTTLE) { // Search for xp bottles
                experience += EXP_PER_BOTTLE * bottle.getAmount(); // Collect experience from bottle
                bottle.setAmount(0); // Delete bottle
            }
        }

        Block barrel = dispenser.getRelative(((Directional) dispenser.getBlockData()).getFacing());
        SlimefunItem sfItem = StorageCacheUtils.getSlimefunItem(barrel.getLocation());

        if (sfItem instanceof Barrel) {
            Barrel sfBarrel = (Barrel) sfItem;
            if (sfBarrel.getStoredItem(barrel).getType() == Material.EXPERIENCE_BOTTLE) {
                experience += sfBarrel.getStored(barrel) * EXP_PER_BOTTLE;
                sfBarrel.setStored(barrel, 0);
                sfBarrel.updateMenu(barrel, StorageCacheUtils.getMenu(barrel.getLocation()), true, sfBarrel.getCapacity(b));
            }
        }

        if (experience == 0) {
            Utils.send(p, "&cNo experience was collected!");
        } else {
            p.giveExp(experience);
            Utils.send(p, "&a+" + experience + " XP");
        }
    }
}
