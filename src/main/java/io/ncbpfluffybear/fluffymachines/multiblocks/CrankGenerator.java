package io.ncbpfluffybear.fluffymachines.multiblocks;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
import io.ncbpfluffybear.fluffymachines.multiblocks.components.GeneratorCore;
import io.ncbpfluffybear.fluffymachines.utils.FluffyItems;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class CrankGenerator extends MultiBlockMachine {

    public static final int RATE = 16;
    public static final int CAPACITY = 64;

    public CrankGenerator(ItemGroup category, SlimefunItemStack item) {
        super(category, item, new ItemStack[] {null, null, null, null, new ItemStack(Material.LEVER), null, null,
            FluffyItems.GENERATOR_CORE, null}, BlockFace.SELF);
    }

    public void onInteract(Player p, Block b) {
        Block coreBlock = b.getRelative(BlockFace.DOWN);
        SlimefunItem core = StorageCacheUtils.getSlimefunItem(coreBlock.getLocation());
        if (core instanceof GeneratorCore) {
            ((GeneratorCore) core).addCharge(coreBlock.getLocation(), RATE);
            p.playSound(p.getLocation(), Sound.BLOCK_PISTON_EXTEND, 0.5F, 0.5F);
        } else {
            Utils.send(p, "&cMissing Generator Core.");
        }
    }

}
