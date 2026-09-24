package io.ncbpfluffybear.fluffymachines.items.tools;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.ncbpfluffybear.fluffymachines.FluffyMachines;
import io.ncbpfluffybear.fluffymachines.utils.Utils;

import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;

import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * used to quickly manipulate cargo nodes
 *
 * @author NCBPFluffyBear
 */
public class CargoManipulator extends SimpleSlimefunItem<ItemUseHandler> implements Listener {

    private static final Gson GSON = new Gson();

    private static final int[] CARGO_SLOTS = {19, 20, 21, 28, 29, 30, 37, 38, 39};
    private Map<Player, CargoNodeConfig> storedFilters = new HashMap<>();

    public CargoManipulator(ItemGroup category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, recipeType, recipe);

        Bukkit.getPluginManager().registerEvents(this, FluffyMachines.getInstance());
    }

    @Nonnull
    @Override
    public ItemUseHandler getItemHandler() {
        return e -> e.setUseBlock(Event.Result.DENY); // Prevent opening inventories
    }

    @EventHandler
    private void onCargoManipulatorUse(PlayerInteractEvent e) {

        ItemStack manipulator = e.getItem();

        // Check item is cargo manipulator
        if (manipulator == null || !this.isItem(manipulator)) {
            return;
        }

        e.setCancelled(true);

        Action act = e.getAction();
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();

        // Check if targeted block is cargo node
        SlimefunItemStack nodeType = getCargoNodeType(b);
        if (nodeType == null || (
            nodeType != SlimefunItems.CARGO_OUTPUT_NODE &&
                nodeType != SlimefunItems.CARGO_OUTPUT_NODE_2 &&
                nodeType != SlimefunItems.CARGO_INPUT_NODE
        )) {
            return;
        }

        if (!Slimefun.getProtectionManager().hasPermission(e.getPlayer(), b.getLocation(), Interaction.INTERACT_BLOCK)) {
            return;
        }

        if (act == Action.RIGHT_CLICK_BLOCK) {
            if (p.isSneaking()) {
                clearNode(b, p, getCargoNodeType(b));
            } else {
                copyNode(b, p, getCargoNodeType(b));
            }
        } else {
            pasteNode(b, p, getCargoNodeType(b));
        }
    }

    /**
     * Copy's a node's data into the manipulator. Cargo inventories stored in map.
     * Action: Right Click Block
     */
    private void copyNode(Block parent, Player p, SlimefunItemStack nodeType) {
        // Copy BlockStorage data
        SlimefunBlockData blockData = StorageCacheUtils.getBlock(parent.getLocation());
        String nodeData = GSON.toJson(blockData.getAllData());

        ItemStack[] filterItems = new ItemStack[9];
        if (nodeType != SlimefunItems.CARGO_OUTPUT_NODE) { // No inventory
            // Copy inventory into map
            BlockMenu parentInventory = blockData.getBlockMenu();
            for (int i = 0; i < 9; i++) { // Iterate through all slots in cargo filter
                ItemStack menuItem = parentInventory.getItemInSlot(CARGO_SLOTS[i]);
                if (menuItem != null) {
                    filterItems[i] = new CustomItemStack(menuItem, 1);
                } else {
                    filterItems[i] = null;
                }
            }
        }

        storedFilters.put(p, new CargoNodeConfig(blockData.getSfId(), nodeData, filterItems)); // Save cargo slots into map

        Utils.send(p, "&aCopied settings from " + SlimefunItem.getById(blockData.getSfId()).getItemName() + "&a.");
        createParticle(parent, Color.fromRGB(255, 252, 51)); // Bright Yellow
    }

    /**
     * Pastes stored node contents
     * Action: Left Click
     */
    private void pasteNode(Block child, Player p, SlimefunItemStack nodeType) {
        CargoNodeConfig nodeSettings = storedFilters.getOrDefault(p, null);

        // No data saved yet
        if (nodeSettings == null) {
            Utils.send(p, "&cYou have not copied a Cargo Node configuration yet.");
            return;
        }

        // Get saved data
        String jsonData = nodeSettings.json();

        SlimefunItemStack savedNodeType = (SlimefunItemStack) SlimefunItem.getById(nodeSettings.id()).getItem();
        if (savedNodeType != nodeType) {
            Utils.send(p, "&cThe saved configuration is for " + savedNodeType.getDisplayName() + "&c and cannot be applied to " +
                nodeType.getDisplayName() + "&c!");
            createParticle(child, Color.RED);
            return;
        }

        // Set the data
        SlimefunBlockData blockData = StorageCacheUtils.getBlock(child.getLocation());
        Map<String, String> storedData = GSON.fromJson(jsonData, new TypeToken<Map<String, String>>() {}.getType());
        storedData.forEach((k, v) -> blockData.setData(k, v));

        if (nodeType != SlimefunItems.CARGO_OUTPUT_NODE) {
            // Set the filter
            BlockMenu nodeMenu = blockData.getBlockMenu();
            ItemStack[] filterItems = nodeSettings.filter();
            Inventory playerInventory = p.getInventory();

            for (int i = 0; i < 9; i++) {

                // Check if item already exists in slot
                if (SlimefunUtils.isItemSimilar(filterItems[i], nodeMenu.getItemInSlot(CARGO_SLOTS[i]), true, false)) {
                    continue;
                }

                // Drop item in filter slot
                clearFilterSlot(nodeMenu, CARGO_SLOTS[i], p);

                // No need to insert new items in
                if (filterItems[i] == null) {
                    continue;
                }

                // Check if item not in inventory
                if (!SlimefunUtils.containsSimilarItem(playerInventory, filterItems[i], true)) {
                    createParticle(child, Color.AQUA);
                    Utils.send(p, "&cYour inventory does not contain filter item " + Utils.getViewableName(filterItems[i]) + "&c; that filter was skipped.");
                    continue;
                }

                // Consume item in player inventory
                for (ItemStack playerItem : playerInventory) {
                    if (SlimefunUtils.isItemSimilar(playerItem, filterItems[i], false, false)) {
                        playerItem.setAmount(playerItem.getAmount() - 1);

                        // Insert item into node menu
                        nodeMenu.replaceExistingItem(CARGO_SLOTS[i], new CustomItemStack(playerItem, 1));
                        break;
                    }
                }
            }
        }

        // Force menu update
        Utils.send(p, "&aApplied settings for " + savedNodeType.getDisplayName() + "&a.");
        createParticle(child, Color.LIME);

    }

    /**
     * Clears the data of a targeted node
     * Action: Sneak + Right Click Block
     */
    private void clearNode(Block node, Player p, SlimefunItemStack nodeType) {
        // Clear node settings
        SlimefunBlockData blockData = StorageCacheUtils.getBlock(node.getLocation());
        blockData.setData("owner", p.getUniqueId().toString());
        blockData.setData("frequency", "0");

        // These settings are only for Input and Advanced Output nodes
        if (nodeType != SlimefunItems.CARGO_OUTPUT_NODE) {
            // AbstractFilterNode settings
            blockData.setData("index", "0");
            blockData.setData("filter-type", "whitelist");
            blockData.setData("filter-lore", String.valueOf(true));
            blockData.setData("filter-durability", String.valueOf(false));

            if (nodeType == SlimefunItems.CARGO_INPUT_NODE) {
                // CargoInputNode settings
                blockData.setData("round-robin", String.valueOf(false));
                blockData.setData("smart-fill", String.valueOf(false));
            }

            clearNodeFilter(node, p);

            Utils.send(p, "&aThe saved Cargo Node configuration was cleared.");
            createParticle(node, Color.fromRGB(255, 152, 56)); // Light orange
        }
    }

    private void clearNodeFilter(Block node, Player p) {
        // Empty filter contents
        BlockMenu nodeMenu = StorageCacheUtils.getMenu(node.getLocation());
        for (int i = 0; i < 9; i++) {
            clearFilterSlot(nodeMenu, CARGO_SLOTS[i], p);
        }
    }

    private void clearFilterSlot(BlockMenu nodeMenu, int slot, Player p) {
        ItemStack filterItem = nodeMenu.getItemInSlot(slot);
        if (filterItem != null) {
            Utils.giveOrDropItem(p, filterItem); // Give player item in filter
            nodeMenu.replaceExistingItem(slot, null); // Clear item in filter
        }
    }

    /**
     * Get the SlimefunItemStack of the cargo node
     */
    private SlimefunItemStack getCargoNodeType(Block b) {
        if (b == null) {
            return null;
        }

        SlimefunItem item = StorageCacheUtils.getSlimefunItem(b.getLocation());
        return item == null ? null : (SlimefunItemStack) item.getItem();
    }

    private void createParticle(Block b, Color color) {
        Particle.DustOptions dustOption = new Particle.DustOptions(color, 1);
        b.getLocation().getWorld().spawnParticle(Particle.DUST, b.getLocation().add(0.5, 0.5, 0.5), 1, dustOption);
    }

    private record CargoNodeConfig(String id, String json, ItemStack[] filter) {}
}
