package io.ncbpfluffybear.fluffymachines.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSetting;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.settings.IntRangeSetting;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.blocks.BlockPosition;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import io.ncbpfluffybear.fluffymachines.utils.FluffyItems;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AdvancedAutoDisenchanter extends SlimefunItem implements EnergyNetComponent {

    private static final int[] BACKGROUND = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 14, 21, 22, 23, 36, 37, 38, 42, 43, 44, 45, 46, 47, 51, 52, 53
    };
    private static final int[] INPUT_BORDER = {9, 10, 11, 18, 20, 27, 28, 29};
    private static final int[] OUTPUT_BORDER = {21, 22, 23, 30, 32, 39, 41, 48, 49, 50};
    private static final int[] BOOK_BORDER = {15, 16, 17, 24, 26, 33, 34, 35};

    private static final int ITEM_SLOT = 19;
    private static final int BOOK_SLOT = 25;
    private static final int[] OUTPUT_SLOTS = {31, 40};
    private static final int SELECTION_SLOT = 4;
    private static final int PROGRESS_SLOT = 13;

    public static final int ENERGY_CONSUMPTION = 1024;
    public static final int CAPACITY = 4096;

    // "Number of seconds", except 1 Slimefun "second" = 1.6 IRL seconds
    private static final int PROCESS_TIME_TICKS = 60;

    private final ItemSetting<Boolean> useLevelLimit =
        new ItemSetting<>(this, "use-enchant-level-limit", false);
    private final IntRangeSetting levelLimit =
        new IntRangeSetting(this, "enchant-level-limit", 0, 10, Short.MAX_VALUE);

    private static final Map<BlockPosition, Integer> progress = new HashMap<>();

    private static final ItemStack DEFAULT_SELECTION_ITEM = new CustomItemStack(
        Material.ENCHANTED_BOOK,
        "&5Enchantment Selector",
        "",
        "&e> Click to rescan <"
    );

    private static final ItemStack PROGRESS_ITEM =
        new CustomItemStack(Material.EXPERIENCE_BOTTLE, "&aProgress");

    public AdvancedAutoDisenchanter(
        ItemGroup category,
        SlimefunItemStack item,
        RecipeType recipeType,
        ItemStack[] recipe
    ) {
        super(category, item, recipeType, recipe);

        addItemHandler(onBreak());
        addItemSetting(useLevelLimit, levelLimit);

        new BlockMenuPreset(getId(), "&cAdvanced Auto Disenchanter") {
            @Override
            public void init() {
                constructMenu(this);
            }

            @Override
            public void newInstance(@Nonnull BlockMenu menu, @Nonnull Block b) {
                menu.replaceExistingItem(SELECTION_SLOT, DEFAULT_SELECTION_ITEM.clone());

                menu.addMenuClickHandler(SELECTION_SLOT, (p, slot, clickedItem, action) -> {
                    cycleEnchants(menu, b);
                    return false;
                });

                menu.addMenuClickHandler(ITEM_SLOT, (p, slot, clickedItem, action) -> {
                    menu.replaceExistingItem(SELECTION_SLOT, DEFAULT_SELECTION_ITEM.clone());
                    setSelectedIndex(b, -2);
                    return true;
                });

                setSelectedIndex(b, -2);
            }

            @Override
            public boolean canOpen(@Nonnull Block b, @Nonnull Player p) {
                return p.hasPermission("slimefun.inventory.bypass")
                    || Slimefun.getProtectionManager().hasPermission(
                        p,
                        b.getLocation(),
                        Interaction.INTERACT_BLOCK
                    );
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow itemTransportFlow) {
                return new int[0];
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(
                DirtyChestMenu menu,
                ItemTransportFlow flow,
                ItemStack item
            ) {
                if (flow == ItemTransportFlow.INSERT) {
                    if (item.getType() == Material.BOOK) {
                        return new int[] {BOOK_SLOT};
                    }

                    return new int[] {ITEM_SLOT};
                }

                if (flow == ItemTransportFlow.WITHDRAW) {
                    return OUTPUT_SLOTS;
                }

                return new int[0];
            }
        };
    }

    private BlockBreakHandler onBreak() {
        return new BlockBreakHandler(false, false) {
            @Override
            public void onPlayerBreak(
                @Nonnull BlockBreakEvent e,
                @Nonnull ItemStack item,
                @Nonnull List<ItemStack> drops
            ) {
                Block b = e.getBlock();
                BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());

                if (inv != null) {
                    inv.dropItems(b.getLocation(), ITEM_SLOT);
                    inv.dropItems(b.getLocation(), BOOK_SLOT);
                    inv.dropItems(b.getLocation(), OUTPUT_SLOTS);
                }
            }
        };
    }

    @Override
    public void preRegister() {
        addItemHandler(new BlockTicker() {
            @Override
            public void tick(Block b, SlimefunItem sf, SlimefunBlockData data) {
                AdvancedAutoDisenchanter.this.tick(b);
            }

            @Override
            public boolean isSynchronized() {
                return false;
            }
        });
    }

    protected void tick(Block b) {
        if (getCharge(b.getLocation()) < ENERGY_CONSUMPTION) {
            return;
        }

        BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());

        final BlockPosition pos = new BlockPosition(
            b.getWorld(),
            b.getX(),
            b.getY(),
            b.getZ()
        );

        int currentProgress = progress.getOrDefault(pos, 0);
        int selectedEnchant = getSelectedIndex(b.getLocation());

        if (selectedEnchant < 0) {
            return;
        }

        for (int slot : OUTPUT_SLOTS) {
            if (inv.getItemInSlot(slot) != null) {
                return;
            }
        }

        ItemStack input = inv.getItemInSlot(ITEM_SLOT);

        SlimefunItem sfItem = SlimefunItem.getByItem(input);
        if (input == null || input.getEnchantments().isEmpty()
            || sfItem != null && !sfItem.isDisenchantable()
        ) {
            return;
        }

        if (!SlimefunUtils.isItemSimilar(
            inv.getItemInSlot(BOOK_SLOT),
            FluffyItems.ANCIENT_BOOK.getItem().getItem(),
            false,
            false
        )) {
            return;
        }

        if (currentProgress < PROCESS_TIME_TICKS) {
            progress.put(pos, ++currentProgress);

            ChestMenuUtils.updateProgressbar(
                inv,
                PROGRESS_SLOT,
                PROCESS_TIME_TICKS - currentProgress,
                PROCESS_TIME_TICKS,
                PROGRESS_ITEM
            );

            removeCharge(b.getLocation(), ENERGY_CONSUMPTION);
            return;
        }

        Map<Enchantment, Integer> disenchants = getValidDisenchants(input);


        Enchantment outputEnchant =
            disenchants.keySet().toArray(new Enchantment[0])[selectedEnchant];

        if (outputEnchant == null) {
            return;
        }

        ItemStack enchantedBook = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta enchantedMeta =
            (EnchantmentStorageMeta) enchantedBook.getItemMeta();

        enchantedMeta.addStoredEnchant(
            outputEnchant,
            disenchants.get(outputEnchant),
            true
        );
        enchantedBook.setItemMeta(enchantedMeta);

        input.removeEnchantment(outputEnchant);

        inv.pushItem(input, OUTPUT_SLOTS);
        inv.pushItem(enchantedBook, OUTPUT_SLOTS);
        inv.consumeItem(ITEM_SLOT);
        inv.consumeItem(BOOK_SLOT);

        progress.put(pos, 0);
        currentProgress = progress.getOrDefault(pos, 0);

        ChestMenuUtils.updateProgressbar(
            inv,
            PROGRESS_SLOT,
            PROCESS_TIME_TICKS - currentProgress,
            PROCESS_TIME_TICKS,
            PROGRESS_ITEM
        );

        setSelectedIndex(b, -2);
        inv.replaceExistingItem(SELECTION_SLOT, DEFAULT_SELECTION_ITEM.clone());
    }

    private void cycleEnchants(BlockMenu inv, Block b) {
        int currentSelection = getSelectedIndex(b.getLocation());
        Map<Enchantment, Integer> itemEnchants =
            getValidDisenchants(inv.getItemInSlot(ITEM_SLOT));

        List<String> lore = new ArrayList<>();

        if (inv.getItemInSlot(ITEM_SLOT) == null) {
            lore.add(Utils.color("&cInsert an item"));
            lore.add("");
            lore.add(Utils.color("&e> Click to rescan <"));
            setSelectionItem(inv, lore);
            setSelectedIndex(b, -2);
            return;
        }

        if (itemEnchants.isEmpty()) {
            lore.add(Utils.color("&cThis item has no eligible enchantments!"));
            lore.add("");
            lore.add(Utils.color("&e> Click to rescan <"));
            setSelectionItem(inv, lore);
            setSelectedIndex(b, -2);
            return;
        }

        currentSelection++;

        if (currentSelection > itemEnchants.size() - 1) {
            currentSelection = -1;
        }

        buildAndSetSelectionItem(itemEnchants, inv, currentSelection);
        setSelectedIndex(b, currentSelection);
    }

    private void constructMenu(BlockMenuPreset preset) {
        ChestMenuUtils.drawBackground(preset, BACKGROUND);

        for (int i : INPUT_BORDER) {
            preset.addItem(
                i,
                ChestMenuUtils.getInputSlotTexture(),
                ChestMenuUtils.getEmptyClickHandler()
            );
        }

        for (int i : BOOK_BORDER) {
            preset.addItem(
                i,
                new CustomItemStack(
                    new ItemStack(Material.YELLOW_STAINED_GLASS_PANE),
                    " "
                ),
                ChestMenuUtils.getEmptyClickHandler()
            );
        }

        for (int i : OUTPUT_BORDER) {
            preset.addItem(
                i,
                ChestMenuUtils.getOutputSlotTexture(),
                ChestMenuUtils.getEmptyClickHandler()
            );
        }

        preset.addItem(
            PROGRESS_SLOT,
            PROGRESS_ITEM,
            ChestMenuUtils.getEmptyClickHandler()
        );
    }

    private void buildAndSetSelectionItem(
        Map<Enchantment, Integer> disenchants,
        BlockMenu menu,
        int selectionIndex
    ) {
        List<String> lore = new ArrayList<>();

        lore.add(Utils.color("&e> Click to select the enchantment to extract <"));
        lore.add("");

        if (selectionIndex == -1) {
            lore.add(Utils.color("&a- None"));
        } else {
            lore.add(Utils.color("&c- None"));
        }

        Enchantment[] disenchantKeys =
            disenchants.keySet().toArray(new Enchantment[0]);

        for (int i = 0; i < disenchantKeys.length; i++) {
            ChatColor textColor =
                i == selectionIndex ? ChatColor.GREEN : ChatColor.RED;

            String ench =
                textColor
                    + "- "
                    + getEnchantmentName(disenchantKeys[i])
                    + " "
                    + Utils.toRoman(disenchants.get(disenchantKeys[i]));

            lore.add(ench);
        }

        setSelectionItem(menu, lore);
    }

    /**
     * Returns a readable English enchantment name from its registry key.
     */
    private String getEnchantmentName(Enchantment enchantment) {
        String key = enchantment.getKey().getKey();
        return humanize(key);
    }

    private String humanize(String value) {
        String normalized = value
            .toLowerCase(Locale.ROOT)
            .replace(' ', '_')
            .replace('-', '_');

        StringBuilder result = new StringBuilder();

        for (String word : normalized.split("_+")) {
            if (word.isEmpty()) {
                continue;
            }

            if (!result.isEmpty()) {
                result.append(' ');
            }

            result.append(Character.toUpperCase(word.charAt(0)));

            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }

        return result.toString();
    }

    /**
     * Gets all valid disenchants for the item.
     * Does not account for isDisenchantable() == false Slimefun items.
     * Assumes the returned enchant map is in the same order every time.
     */
    private Map<Enchantment, Integer> getValidDisenchants(ItemStack item) {
        if (item == null) {
            return new HashMap<>();
        }

        SlimefunItem sfItem = SlimefunItem.getByItem(item);
        if (sfItem != null && !sfItem.isDisenchantable()) {
            return new HashMap<>();
        }

        Map<Enchantment, Integer> disenchants = item.getEnchantments();
        Map<Enchantment, Integer> filteredDisenchants =
            new HashMap<>(item.getEnchantments());

        for (Map.Entry<Enchantment, Integer> disenchantEntry : disenchants.entrySet()) {
            if (
                useLevelLimit.getValue()
                    && disenchantEntry.getValue() > levelLimit.getValue()
            ) {
                filteredDisenchants.remove(disenchantEntry.getKey());
            }
        }

        return filteredDisenchants;
    }

    private void setSelectionItem(BlockMenu menu, List<String> lore) {
        ItemStack selectionItem = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta itemMeta = selectionItem.getItemMeta();

        itemMeta.setDisplayName(Utils.color("&5Enchantment Selector"));
        itemMeta.setLore(lore);
        selectionItem.setItemMeta(itemMeta);

        menu.replaceExistingItem(SELECTION_SLOT, selectionItem);
    }

    /**
     * We need to use index addressing because the namespaced key is not always
     * minecraft, e.g. FluffyMachines' Glow enchantment.
     */
    private int getSelectedIndex(Location l) {
        return Integer.parseInt(StorageCacheUtils.getData(l, "selection"));
    }

    private void setSelectedIndex(Block b, int index) {
        StorageCacheUtils.setData(
            b.getLocation(),
            "selection",
            String.valueOf(index)
        );
    }

    @Nonnull
    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public int getCapacity() {
        return CAPACITY;
    }
}
