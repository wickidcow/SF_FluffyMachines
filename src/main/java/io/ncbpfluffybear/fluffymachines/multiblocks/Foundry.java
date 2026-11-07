package io.ncbpfluffybear.fluffymachines.multiblocks;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.ncbpfluffybear.fluffymachines.multiblocks.components.SuperheatedFurnace;
import io.ncbpfluffybear.fluffymachines.utils.FluffyItems;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import net.kyori.adventure.text.Component;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Furnace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The shell of the {@link SuperheatedFurnace}
 *
 * @author NCBPFluffyBear
 */
public class Foundry extends MultiBlockMachine {

    public Foundry(ItemGroup category, SlimefunItemStack item) {
        super(category, item, new ItemStack[] {
            new ItemStack(Material.NETHERITE_BLOCK), FluffyItems.SUPERHEATED_FURNACE, new ItemStack(Material.NETHERITE_BLOCK),
            new ItemStack(Material.NETHERITE_BLOCK), new ItemStack(Material.GLASS), new ItemStack(Material.NETHERITE_BLOCK),
            new ItemStack(Material.NETHERITE_BLOCK), new ItemStack(Material.CAULDRON), new ItemStack(Material.NETHERITE_BLOCK)
        }, BlockFace.DOWN);
    }

    /**
     * Legacy Foundry ignition changes the held ItemStack itself, before the
     * armor-stand animation and furnace update. Replacing the stack with a new
     * BUCKET here could lose custom item state and change historic gameplay.
     *
     * Keep this deprecated mutation until native tests prove the exact item
     * metadata and Slimefun PDC survive a modern API migration. Tests below
     * cover material, quantity, reference identity and non-lava inputs only;
     * they are not proof of custom ItemMeta retention on a running server.
     */
    static Material materialAfterIgnition(Material current) {
        return current == Material.LAVA_BUCKET ? Material.BUCKET : current;
    }

    static boolean consumeLavaBucketInPlace(ItemStack held) {
        if (held.getType() != Material.LAVA_BUCKET) {
            return false;
        }
        held.setType(materialAfterIgnition(held.getType()));
        return true;
    }

    @Override
    public void onInteract(Player p, Block b) {
        // Verify a vanilla blast furnace is not being used
        SlimefunBlockData blockData = StorageCacheUtils.getBlock(b.getLocation());
        if (blockData == null || !blockData.getSfId().equals("SUPERHEATED_FURNACE")) {
            return;
        }

        if (blockData.getData("accessible") == null) {
            blockData.setData("accessible", "true");
            Utils.send(p, "&eFoundry registered. Next, hold a lava bucket and right-click the Superheated Furnace to ignite it.");
        } else if (blockData.getData("ignited") == null) {
            // Preserve the old in-place bucket transition until a native Paper
            // metadata/Slimefun-identity migration test proves a replacement safe.
            if (consumeLavaBucketInPlace(p.getInventory().getItemInMainHand())) {
                ArmorStand lavaStand = (ArmorStand) p.getWorld().spawnEntity(b.getLocation().add(0.5, -3, 0.5),
                    EntityType.ARMOR_STAND);
                lavaStand.getEquipment().setHelmet(new CustomItemStack(
                    SlimefunUtils.getCustomHead("b6965e6a58684c277d18717cec959f2833a72dfa95661019dbcdf3dbf66b048")));
                lavaStand.setCanPickupItems(false);
                lavaStand.setGravity(false);
                lavaStand.setVisible(false);
                lavaStand.customName(Component.text("hehexdfluff"));
                lavaStand.setCustomNameVisible(false);
                Furnace furnace = (Furnace) b.getState();
                furnace.setBurnTime((short) 1000000);
                furnace.update(true);

                blockData.setData("stand", String.valueOf(lavaStand.getUniqueId()));
                blockData.setData("ignited", "true");
            } else {
                Utils.send(p, "&cThis Foundry needs lava to ignite!");
            }
        } else if (blockData.getData("ignited") != null) {
            // Reheat furnace (Cosmetic)
            Furnace furnace = (Furnace) b.getState();
            furnace.setBurnTime((short) 1000000);
            furnace.update(true);
        }
    }
}
