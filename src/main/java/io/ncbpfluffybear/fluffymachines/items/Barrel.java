package io.ncbpfluffybear.fluffymachines.items;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemHandler;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSetting;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.settings.IntRangeSetting;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.SlimefunItems;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.ncbpfluffybear.fluffymachines.objects.DoubleHologramOwner;
import io.ncbpfluffybear.fluffymachines.objects.NonHopperableBlock;
import io.ncbpfluffybear.fluffymachines.utils.BarrelDisplayManager;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import javax.annotation.Nonnull;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;

/**
 * A Remake of Barrels by John000708
 *
 * @author NCBPFluffyBear
 */

public class Barrel extends NonHopperableBlock implements DoubleHologramOwner {

    private final String WIKI_PAGE = "barrels";
    private final int[] inputBorder = {9, 10, 11, 12, 18, 21, 27, 28, 29, 30};
    private final int[] outputBorder = {14, 15, 16, 17, 23, 26, 32, 33, 34, 35};
    private final int[] plainBorder = {0, 1, 2, 3, 4, 5, 6, 7, 8, 13, 36, 37, 38, 39, 40, 41, 42, 43, 44};

    protected final int[] INPUT_SLOTS = {19, 20};
    protected final int[] OUTPUT_SLOTS = {24, 25};
    private final int STATUS_SLOT = 22;
    private final int DISPLAY_SLOT = 31;
    private final int HOLOGRAM_TOGGLE_SLOT = 36;
    private final int TRASH_TOGGLE_SLOT = 37;

    private final int OVERFLOW_AMOUNT = 3240;
    public static final DecimalFormat STORAGE_INDICATOR_FORMAT = new DecimalFormat("###,###.####",
        DecimalFormatSymbols.getInstance(Locale.ROOT));
    private final ItemStack HOLOGRAM_OFF_ITEM = new CustomItemStack(Material.QUARTZ_SLAB, "&3Hologram &c(Disabled)");
    private final ItemStack HOLOGRAM_ON_ITEM = new CustomItemStack(Material.QUARTZ_SLAB, "&3Hologram &a(Enabled)");
    private final ItemStack TRASH_ON_ITEM = new CustomItemStack(SlimefunItems.TRASH_CAN, "&3Void excess input when full &a(Enabled)",
        "&7When enabled, items that cannot be stored are voided.");
    private final ItemStack TRASH_OFF_ITEM = new CustomItemStack(SlimefunItems.TRASH_CAN, "&3Void excess input when full &c(Disabled)",
        "&7When enabled, items that cannot be stored are voided."
    );
    private final ItemSetting<Boolean> showHologram = new ItemSetting<>(this, "show-hologram", true);
    private final ItemSetting<Boolean> breakOnlyWhenEmpty = new ItemSetting<>(this, "break-only-when-empty", false);

    protected final ItemSetting<Integer> barrelCapacity;

    public Barrel(ItemGroup category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe,
                  int MAX_STORAGE) {
        super(category, item, recipeType, recipe);
        this.barrelCapacity = new IntRangeSetting(this, "capacity", 0, MAX_STORAGE, Integer.MAX_VALUE);

        addItemSetting(barrelCapacity);

        new BlockMenuPreset(getId(), getItemName()) {

            @Override
            public void init() {
                constructMenu(this);
            }

            @Override
            public void newInstance(@Nonnull BlockMenu menu, @Nonnull Block b) {
                buildMenu(menu, b);
            }
            @Override
            public boolean canOpen(@Nonnull Block b, @Nonnull Player p) {
                if (Utils.canOpen(b, p)) {
                    updateMenu(b, StorageCacheUtils.getMenu(b.getLocation()), true, getCapacity(b));
                    return true;
                }

                return false;
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow itemTransportFlow) {
                return new int[0];
            }
            @Override
            public int[] getSlotsAccessedByItemTransport(DirtyChestMenu menu, ItemTransportFlow flow, ItemStack item) {
                if (flow == ItemTransportFlow.INSERT) {
                    return INPUT_SLOTS;
                } else if (flow == ItemTransportFlow.WITHDRAW) {
                    return OUTPUT_SLOTS;
                } else {
                    return new int[0];
                }
            }
        };
        addItemHandler(onBreak());
        addItemSetting(showHologram, breakOnlyWhenEmpty);

    }
    private ItemHandler onBreak() {
        return new BlockBreakHandler(false, false) {
            @Override
            public void onPlayerBreak(@Nonnull BlockBreakEvent e, @Nonnull ItemStack item, @Nonnull List<ItemStack> drops) {
                Block b = e.getBlock();
                Player p = e.getPlayer();
                BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());
                int capacity = getCapacity(b);
                int stored = getStored(b);
                if (inv != null) {

                    int itemCount = 0;

                    if (breakOnlyWhenEmpty.getValue() && stored != 0) {
                        Utils.send(p, "&cPlease empty this barrel before breaking it!");
                        e.setCancelled(true);
                        return;
                    }
                    for (Entity en : p.getNearbyEntities(5, 5, 5)) {
                        if (en instanceof Item) {
                            itemCount++;
                        }
                    }

                    if (itemCount > 5) {
                        Utils.send(p, "&cYou should empty this barrel before breaking it!");
                        e.setCancelled(true);
                        return;
                    }
                    inv.dropItems(b.getLocation(), INPUT_SLOTS);
                    inv.dropItems(b.getLocation(), OUTPUT_SLOTS);

                    if (stored > 0) {
                        int stackSize = inv.getItemInSlot(DISPLAY_SLOT).getMaxStackSize();
                        ItemStack unKeyed = getStoredItem(b);
                        if (unKeyed.getType() == Material.BARRIER) {
                            setStored(b, 0);
                            updateMenu(b, inv, true, capacity);
                            BarrelDisplayManager.remove(b);
                            return;
                        }

                        if (stored > OVERFLOW_AMOUNT) {
                            Utils.send(p, "&eThis Fluffy Barrel contains more than " + OVERFLOW_AMOUNT + " items! " +
                                "Only dropping " + OVERFLOW_AMOUNT + " items!");
                            int toRemove = OVERFLOW_AMOUNT;
                            while (toRemove >= stackSize) {

                                b.getWorld().dropItemNaturally(b.getLocation(), new CustomItemStack(unKeyed, stackSize));
                                toRemove = toRemove - stackSize;
                            }

                            if (toRemove > 0) {
                                b.getWorld().dropItemNaturally(b.getLocation(), new CustomItemStack(unKeyed, toRemove));
                            }

                            setStored(b, stored - OVERFLOW_AMOUNT);
                            updateMenu(b, inv, true, capacity);
                            e.setCancelled(true);
                        } else {

                            // Everything greater than 1 stack
                            while (stored >= stackSize) {

                                b.getWorld().dropItemNaturally(b.getLocation(), new CustomItemStack(unKeyed, stackSize));

                                stored = stored - stackSize;
                            }
                            // Drop remaining, if there is any
                            if (stored > 0) {
                                b.getWorld().dropItemNaturally(b.getLocation(), new CustomItemStack(unKeyed, stored));
                            }
                            // In case they use an explosive pick
                            setStored(b, 0);
                            updateMenu(b, inv, true, capacity);
                            removeHologram(b);
                        }
                    } else {
                        removeHologram(b);
                    }

                }

                if (!e.isCancelled()) {
                    BarrelDisplayManager.remove(b);
                }
            }
        };
    }
    private void constructMenu(BlockMenuPreset preset) {
        Utils.createBorder(preset, ChestMenuUtils.getOutputSlotTexture(), outputBorder);
        Utils.createBorder(preset, ChestMenuUtils.getInputSlotTexture(), inputBorder);
        ChestMenuUtils.drawBackground(preset, plainBorder);
    }

    protected void buildMenu(BlockMenu menu, Block b) {
        int capacity = getCapacity(b);

        // Initialize an empty barrel
        if (StorageCacheUtils.getData(b.getLocation(), "stored") == null) {
            menu.replaceExistingItem(STATUS_SLOT, new CustomItemStack(
                    Material.LIME_STAINED_GLASS_PANE, "&6Stored items: &e0" + " / " + capacity, "&70%"));
            menu.replaceExistingItem(DISPLAY_SLOT, new CustomItemStack(Material.BARRIER, "&cNo item"));

            setStored(b, 0);

            if (showHologram.getValue() && isHologramEnabled(b)) {
                updateHologram(b, null, "&cNo item");
            }
            // Change hologram settings
        }

        // Remove any existing hologram when the global setting or per-barrel setting is disabled.
        // This also cleans up holograms on legacy barrels that had no saved "holo" value.
        if (!showHologram.getValue() || !isHologramEnabled(b)) {
            removeHologram(b);
        }

        // Every time setup
        menu.addMenuClickHandler(STATUS_SLOT, ChestMenuUtils.getEmptyClickHandler());
        menu.addMenuClickHandler(DISPLAY_SLOT, ChestMenuUtils.getEmptyClickHandler());
        // Toggle hologram (Dynamic button)
        if (isHologramEnabled(b)) {
            menu.replaceExistingItem(HOLOGRAM_TOGGLE_SLOT, HOLOGRAM_ON_ITEM);
        } else {
            menu.replaceExistingItem(HOLOGRAM_TOGGLE_SLOT, HOLOGRAM_OFF_ITEM);
        }
        menu.addMenuClickHandler(HOLOGRAM_TOGGLE_SLOT, (pl, slot, item, action) -> {
            toggleHolo(b, capacity);
            return false;
        });
        // Toggle trash (Dynamic button)
        if (isTrashEnabled(b)) {
            menu.replaceExistingItem(TRASH_TOGGLE_SLOT, TRASH_ON_ITEM);
        } else {
            menu.replaceExistingItem(TRASH_TOGGLE_SLOT, TRASH_OFF_ITEM);
        }
        menu.addMenuClickHandler(TRASH_TOGGLE_SLOT, (pl, slot, item, action) -> {
            toggleTrash(b);
            return false;
        });
        // Insert all
        int INSERT_ALL_SLOT = 43;
        menu.replaceExistingItem(INSERT_ALL_SLOT,
                new CustomItemStack(Material.LIME_STAINED_GLASS_PANE, "&bQuick deposit",
                        "&7> Click to move every matching item in your inventory", "&7into this Fluffy Barrel"));
        menu.addMenuClickHandler(INSERT_ALL_SLOT, (pl, slot, item, action) -> {
            insertAll(pl, menu, b);
            return false;
        });
        // Extract all
        int EXTRACT_SLOT = 44;
        menu.replaceExistingItem(EXTRACT_SLOT,
                new CustomItemStack(Material.RED_STAINED_GLASS_PANE, "&6Quick withdraw",
                        "&7> Left-click to fill your inventory",
                        "&7> Right-click to withdraw one item"
                ));
        menu.addMenuClickHandler(EXTRACT_SLOT, (pl, slot, item, action) -> {
            extract(pl, menu, b, action);
            return false;
        });
    }
    @Override
    public void preRegister() {
        addItemHandler(new BlockTicker() {

            @Override
            public void tick(Block b, SlimefunItem sf, SlimefunBlockData data) {
                Barrel.this.tick(b);
                BarrelDisplayManager.update(b, Barrel.this);
            }

            @Override
            public boolean isSynchronized() {
                return true;
            }
        });
    }

    @Override
    public void postRegister() {
        addWikiPage(WIKI_PAGE);
        BarrelDisplayManager.initialize();
    }
    protected void tick(Block b) {
        BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());
        int capacity = getCapacity(b);

        for (int slot : INPUT_SLOTS) {
            acceptInput(inv, b, slot, capacity);
        }

        for (int ignored : OUTPUT_SLOTS) {
            pushOutput(inv, b, capacity);
        }
    }

    void acceptInput(BlockMenu inv, Block b, int slot, int capacity) {
        if (inv.getItemInSlot(slot) == null) {
            return;
        }
        int stored = getStored(b);
        ItemStack item = inv.getItemInSlot(slot);

        if (stored == 0) {
            registerItem(b, inv, slot, item, capacity, stored);
        } else if (stored > 0 && inv.getItemInSlot(DISPLAY_SLOT) != null
                && matchMeta(Utils.unKeyItem(inv.getItemInSlot(DISPLAY_SLOT)), item)) {

            if (stored < capacity) {
                // Can fit entire itemstack
                if (stored + item.getAmount() <= capacity) {
                    storeItem(b, inv, slot, item, capacity, stored);

                    // Split itemstack
                } else {
                    int amount = capacity - stored;
                    inv.consumeItem(slot, amount);
                    setStored(b, stored + amount);
                    updateMenu(b, inv, false, capacity);
                }
            } else {
                if (isTrashEnabled(b)) {
                    inv.replaceExistingItem(slot, null);
                }

            }
        }
    }
    void pushOutput(BlockMenu inv, Block b, int capacity) {
        ItemStack displayItem = inv.getItemInSlot(DISPLAY_SLOT);
        if (displayItem != null && displayItem.getType() != Material.BARRIER) {

            int stored = getStored(b);

            // Output stack
            if (stored > displayItem.getMaxStackSize()) {

                ItemStack clone = new CustomItemStack(Utils.unKeyItem(displayItem), displayItem.getMaxStackSize());

                if (inv.fits(clone, OUTPUT_SLOTS)) {
                    int amount = clone.getMaxStackSize();

                    setStored(b, stored - amount);
                    inv.pushItem(clone, OUTPUT_SLOTS);
                    updateMenu(b, inv, false, capacity);
                }

            } else if (stored != 0) {   // Output remaining

                ItemStack clone = new CustomItemStack(Utils.unKeyItem(displayItem), stored);
                if (inv.fits(clone, OUTPUT_SLOTS)) {
                    setStored(b, 0);
                    inv.pushItem(clone, OUTPUT_SLOTS);
                    updateMenu(b, inv, false, capacity);
                }
            }
        }
    }

    private void registerItem(Block b, BlockMenu inv, int slot, ItemStack item, int capacity, int stored) {
        int amount = item.getAmount();

        inv.replaceExistingItem(DISPLAY_SLOT, new CustomItemStack(Utils.keyItem(item), 1));
        // Fit all
        if (amount <= capacity) {
            storeItem(b, inv, slot, item, capacity, stored);
        } else {
            amount = capacity;
            inv.consumeItem(slot, amount);

            setStored(b, stored + amount);
            updateMenu(b, inv, false, capacity);
        }
    }

    private void storeItem(Block b, BlockMenu inv, int slot, ItemStack item, int capacity, int stored) {
        int amount = item.getAmount();
        inv.consumeItem(slot, amount);
        setStored(b, stored + amount);
        updateMenu(b, inv, false, capacity);
    }
    /**
     * This method checks if two items have the same metadata
     *
     * @param item1 is the first item to compare
     * @param item2 is the second item to compare
     * @return if the items have the same meta
     */
    private boolean matchMeta(ItemStack item1, ItemStack item2) {
        // Type checks are cheaper and avoid unnecessary metadata comparisons.
        if (!item1.getType().equals(item2.getType())) {
            return false;
        }
        if (!item1.hasItemMeta() && !item2.hasItemMeta()) {
            return true;
        }
        if (item1.hasItemMeta() != item2.hasItemMeta()) {
            return false;
        }
        return item1.getItemMeta().equals(item2.getItemMeta());
    }
    /**
     * This method updates the barrel's menu and hologram displays
     *
     * @param b   is the barrel block
     * @param inv is the barrel's inventory
     */
    public void updateMenu(Block b, BlockMenu inv, boolean force, int capacity) {
        int stored = getStored(b);
        String itemName = "";
        ItemStack item = inv.getItemInSlot(DISPLAY_SLOT);
        if (item == null) {
            return;
        }
        String storedPercent = doubleRoundAndFade((double) stored / (double) capacity * 100);
        String storedStacks =
            doubleRoundAndFade((double) stored / (double) inv.getItemInSlot(DISPLAY_SLOT).getMaxStackSize());
        // This helps a bit with lag, but may have visual impacts
        if (inv.hasViewer() || force) {
            inv.replaceExistingItem(STATUS_SLOT, new CustomItemStack(
                Material.LIME_STAINED_GLASS_PANE, "&6Stored items: &e" + stored + " / " + capacity,
                "&b" + storedStacks + " stacks &8| &7" + storedPercent + "&7%"));
        }
        if (inv.getItemInSlot(DISPLAY_SLOT) != null) {
            itemName = Utils.getViewableName(inv.getItemInSlot(DISPLAY_SLOT));
        }

        if (showHologram.getValue() && isHologramEnabled(b)) {
            updateHologram(b, itemName, " &9x" + stored + " &7(" + storedPercent + "&7%)");
        }
        if (stored == 0) {
            inv.replaceExistingItem(DISPLAY_SLOT, new CustomItemStack(Material.BARRIER, "&cNo item"));
            if (showHologram.getValue() && isHologramEnabled(b)) {
                updateHologram(b, null, "&cNo item");
            }
        }
    }
    /**
     * This method toggles if a hologram is present above the barrel.
     *
     * @param b is the block the hologram is linked to
     */
    private void toggleHolo(Block b, int capacity) {
        if (isHologramEnabled(b)) {
            putBlockData(b, HOLOGRAM_TOGGLE_SLOT, "holo", HOLOGRAM_OFF_ITEM, false);
            removeHologram(b);
        } else {
            putBlockData(b, HOLOGRAM_TOGGLE_SLOT, "holo", HOLOGRAM_ON_ITEM, true);
            updateMenu(b, StorageCacheUtils.getMenu(b.getLocation()), false, capacity);
        }
    }
    /**
     * Toggle auto dispose status of barrel
     */
    private void toggleTrash(Block b) {
        if (isTrashEnabled(b)) {
            putBlockData(b, TRASH_TOGGLE_SLOT, "trash", TRASH_OFF_ITEM, false);
        } else {
            putBlockData(b, TRASH_TOGGLE_SLOT, "trash", TRASH_ON_ITEM, true);
        }
    }

    /**
     * Holograms default to disabled when no per-barrel value has been saved.
     */
    private boolean isHologramEnabled(Block b) {
        return "true".equals(StorageCacheUtils.getData(b.getLocation(), "holo"));
    }

    /**
     * Excess-input voiding defaults to enabled when no per-barrel value has been saved.
     */
    private boolean isTrashEnabled(Block b) {
        String value = StorageCacheUtils.getData(b.getLocation(), "trash");
        return value == null || "true".equals(value);
    }

    /**
     * Sets a key in BlockStorage and replaces an item
     */
    private void putBlockData(Block b, int slot, String key, ItemStack displayItem, boolean data) {
        StorageCacheUtils.setData(b.getLocation(), key, String.valueOf(data));
        StorageCacheUtils.getMenu(b.getLocation()).replaceExistingItem(slot, displayItem);
    }
    public void insertAll(Player p, BlockMenu menu, Block b) {
        ItemStack storedItem = Utils.unKeyItem(menu.getItemInSlot(DISPLAY_SLOT));
        PlayerInventory inv = p.getInventory();
        int capacity = getCapacity(b);

        int stored = getStored(b);
        for (int i = 0; i < inv.getContents().length; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null) {
                continue;
            }
            int amount = item.getAmount();
            if (matchMeta(item, storedItem) && stored + amount <= capacity) {
                inv.setItem(i, null);
                stored += amount;
            }
        }
        StorageCacheUtils.setData(b.getLocation(), "stored", String.valueOf(stored));
        updateMenu(b, menu, false, capacity);
    }

    public void extract(Player p, BlockMenu menu, Block b, ClickAction action) {
        ItemStack storedItem = getStoredItem(b);
        int capacity = getCapacity(b);

        PlayerInventory inv = p.getInventory();
        int stored = getStored(b);
        // Extract single
        if (action.isRightClicked()) {
            if (stored > 0) { // Extract from stored
                Utils.giveOrDropItem(p, new CustomItemStack(storedItem));
                setStored(b, --stored);
                updateMenu(b, menu, false, capacity);
                return;
            } else {
                for (int slot : OUTPUT_SLOTS) { // Extract from slot
                    if (menu.getItemInSlot(slot) != null) {
                        Utils.giveOrDropItem(p, new CustomItemStack(menu.getItemInSlot(slot), 1));
                        menu.consumeItem(slot);
                        return;
                    }
                }
            }
            Utils.send(p, "&cThis Fluffy Barrel is empty!");
            return;
        }
        if (storedItem.getType() == Material.BARRIER) {
            Utils.send(p, "&cThis Fluffy Barrel is empty!");
            return;
        }

        // Extract all
        ItemStack[] contents = inv.getStorageContents().clone();
        int maxStackSize = storedItem.getMaxStackSize();
        int outI = 0;

        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null) {
                if (stored >= maxStackSize) {
                    inv.setItem(i, new CustomItemStack(storedItem, maxStackSize));
                    stored -= maxStackSize;
                } else if (stored > 0) {
                    inv.setItem(i, new CustomItemStack(storedItem, stored));
                    stored = 0;
                } else {
                    if (outI > 1) {
                        break;
                    }
                    ItemStack item = menu.getItemInSlot(OUTPUT_SLOTS[outI]);

                    if (item == null) {
                        continue;
                    }

                    inv.setItem(i, item.clone());
                    menu.replaceExistingItem(OUTPUT_SLOTS[outI], null);

                    outI++;
                }
            }
        }

        setStored(b, stored);
        updateMenu(b, menu, false, capacity);
    }
    public static String doubleRoundAndFade(double num) {
        // Using same format that is used on lore power
        String formattedString = STORAGE_INDICATOR_FORMAT.format(num);
        if (formattedString.indexOf('.') != -1) {
            return formattedString.substring(0, formattedString.indexOf('.')) + ChatColor.DARK_GRAY
                + formattedString.substring(formattedString.indexOf('.')) + ChatColor.GRAY;
        } else {
            return formattedString;
        }
    }
    public int getStored(Block b) {
        return Integer.parseInt(StorageCacheUtils.getData(b.getLocation(), "stored"));
    }

    public void setStored(Block b, int amount) {
        StorageCacheUtils.setData(b.getLocation(), "stored", String.valueOf(amount));
    }

    public ItemStack getStoredItem(Block b) {
        return Utils.unKeyItem(StorageCacheUtils.getMenu(b.getLocation()).getItemInSlot(DISPLAY_SLOT));
    }
    /**
     * Gets capacity of barrel
     * Includes Block parameter for MiniBarrel
     */
    public int getCapacity(Block b) {
        return barrelCapacity.getValue();
    }

    public static int getDisplayCapacity(Barrel.BarrelType barrel) {
        int capacity = Slimefun.getItemCfg().getInt(barrel.getKey() + ".capacity");
        if (capacity == 0) {
            capacity = barrel.getDefaultSize();
        }

        return capacity;
    }
    @Nonnull
    @Override
    public Vector getHologramOffset(@Nonnull Block b) {
        return new Vector(0.5, 0.9, 0.5);
    }

    public enum BarrelType {
        SMALL(17280000, "&eFluffy Barrel I", Material.BEEHIVE, SlimefunItems.REINFORCED_PLATE, new ItemStack(Material.OAK_LOG)),
        MEDIUM(34560000, "&6Fluffy Barrel II", Material.BARREL, SlimefunItems.REINFORCED_PLATE, new ItemStack(Material.SMOOTH_STONE)),
        BIG(69120000, "&bFluffy Barrel III", Material.SMOKER, SlimefunItems.REINFORCED_PLATE, new ItemStack(Material.BRICKS)),
        LARGE(138240000, "&aFluffy Barrel IV", Material.LODESTONE, SlimefunItems.REINFORCED_PLATE, new ItemStack(Material.IRON_BLOCK)),
        MASSIVE(276480000, "&5Fluffy Barrel V", Material.CRYING_OBSIDIAN, SlimefunItems.REINFORCED_PLATE, new ItemStack(Material.OBSIDIAN)),
        BOTTOMLESS(1728000000, "&cFluffy Barrel VI", Material.RESPAWN_ANCHOR, SlimefunItems.BLISTERING_INGOT_3, SlimefunItems.REINFORCED_PLATE);
        private final int defaultSize;
        private final String displayName;
        private final Material itemMaterial;
        private final ItemStack reinforcement;
        private final ItemStack border;
        BarrelType(int defaultSize, String displayName, Material itemMaterial, ItemStack reinforcement, ItemStack border) {
            this.defaultSize = defaultSize;
            this.displayName = displayName;
            this.itemMaterial = itemMaterial;
            this.reinforcement = reinforcement;
            this.border = border;
        }

        public int getDefaultSize() {
            return defaultSize;
        }
        public String getDisplayName() {
            return displayName;
        }

        public Material getType() {
            return itemMaterial;
        }

        public String getKey() {
            return this.name().toUpperCase() + "_FLUFFY_BARREL";
        }

        public ItemStack getReinforcement() {
            return reinforcement;
        }

        public ItemStack getBorder() {
            return border;
        }
    }

}
