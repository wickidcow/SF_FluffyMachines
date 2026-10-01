package io.ncbpfluffybear.fluffymachines.items.tools;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSetting;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.HologramOwner;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.data.persistent.PersistentDataAPI;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.ncbpfluffybear.fluffymachines.FluffyMachines;
import io.ncbpfluffybear.fluffymachines.utils.FluffyItems;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

public class WarpPadConfigurator extends SlimefunItem implements HologramOwner, Listener {

    private final NamespacedKey xCoord = new NamespacedKey(FluffyMachines.getInstance(), "xCoordinate");
    private final NamespacedKey yCoord = new NamespacedKey(FluffyMachines.getInstance(), "yCoordinate");
    private final NamespacedKey zCoord = new NamespacedKey(FluffyMachines.getInstance(), "zCoordinate");
    private final NamespacedKey world = new NamespacedKey(FluffyMachines.getInstance(), "world");

    private static final int LORE_COORDINATE_INDEX = 4;
    private final ItemSetting<Integer> MAX_DISTANCE = new ItemSetting<>(this, "max-distance", 100);

    public WarpPadConfigurator(ItemGroup category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, recipeType, recipe);

        Bukkit.getPluginManager().registerEvents(this, FluffyMachines.getInstance());

        addItemSetting(MAX_DISTANCE);

    }

    @EventHandler
    private void onInteract(PlayerInteractEvent e) {

        if (e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) {
            return;
        }

        Block b = e.getClickedBlock();
        Player p = e.getPlayer();

        SlimefunBlockData blockData = StorageCacheUtils.getBlock(b.getLocation());
        if (blockData != null && blockData.getSfId().equals(FluffyItems.WARP_PAD.getItem().getId())
            && Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), Interaction.PLACE_BLOCK)) {
            if (SlimefunUtils.isItemSimilar(p.getInventory().getItemInMainHand(), FluffyItems.WARP_PAD_CONFIGURATOR,
                false)) {

                ItemStack item = p.getInventory().getItemInMainHand();
                ItemMeta meta = item.getItemMeta();
                if (meta == null) {
                    Utils.send(p, "&cThis Warp Pad Configurator has invalid item data.");
                    return;
                }

                List<Component> existingLore = meta.hasLore() ? meta.lore() : null;
                List<Component> lore = existingLore == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existingLore);
                while (lore.size() <= LORE_COORDINATE_INDEX) {
                    lore.add(Component.empty());
                }

                if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    // Destination
                    if (p.isSneaking()) {
                        StorageCacheUtils.executeAfterLoad(blockData, () -> {
                            PersistentDataAPI.setString(meta, world, b.getWorld().getName());
                            PersistentDataAPI.setInt(meta, xCoord, b.getX());
                            PersistentDataAPI.setInt(meta, yCoord, b.getY());
                            PersistentDataAPI.setInt(meta, zCoord, b.getZ());
                            lore.set(
                                LORE_COORDINATE_INDEX,
                                Component.text("Linked coordinates: ", NamedTextColor.YELLOW)
                                    .append(Component.text(
                                        b.getX() + ", " + b.getY() + ", " + b.getZ(),
                                        NamedTextColor.GRAY
                                    ))
                            );

                            meta.lore(lore);
                            item.setItemMeta(meta);

                            updateHologram(b, "&a&lDestination");
                            blockData.setData("type", "destination");
                            Utils.send(p, "&3This Warp Pad is now marked as the &aDestination&3. Its coordinates were saved.");
                        }, false);
                    } else if (PersistentDataAPI.hasString(meta, world) && b.getWorld().getName().equals(
                        PersistentDataAPI.getString(meta, world))) {
                        // Origin
                        StorageCacheUtils.executeAfterLoad(blockData, () -> {
                            int x = PersistentDataAPI.getInt(meta, xCoord, 0);
                            int y = PersistentDataAPI.getInt(meta, yCoord, 0);
                            int z = PersistentDataAPI.getInt(meta, zCoord, 0);

                            if (Math.abs(x - b.getX()) > MAX_DISTANCE.getValue()
                                || Math.abs(z - b.getZ()) > MAX_DISTANCE.getValue()) {

                                Utils.send(p, "&cThe straight-line distance between Warp Pads cannot exceed "
                                    + MAX_DISTANCE.getValue() + " blocks!");

                                return;
                            }

                            blockData.setData("type", "origin");
                            blockData.setData("x", String.valueOf(x));
                            blockData.setData("y", String.valueOf(y));
                            blockData.setData("z", String.valueOf(z));

                            updateHologram(b, "&a&lOrigin");

                            Utils.send(p, "&3This Warp Pad is now marked as the &aOrigin&3 and linked to the destination Warp Pad!");
                        }, false);
                    } else {
                        Utils.send(p, "&cSneak-right-click a Warp Pad to select the destination, then right-click another Warp Pad to set the origin!");
                    }
                }
            } else {
                Utils.send(p, "&cUse a Warp Pad Configurator to configure Warp Pads.");
            }
        }
    }
}
