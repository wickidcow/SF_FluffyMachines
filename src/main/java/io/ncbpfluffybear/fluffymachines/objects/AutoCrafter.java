package io.ncbpfluffybear.fluffymachines.objects;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.events.BlockPlacerPlaceEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class AutoCrafter extends SlimefunItem implements EnergyNetComponent {

    private static final String WIKI_PAGE = "machines/auto-crafters";
    private static final String SINGLE_CRAFT_READY = "single-craft-ready";

    public static final int ENERGY_CONSUMPTION = 128;
    public static final int CAPACITY = ENERGY_CONSUMPTION * 3;
    private final int[] border = {0, 1, 3, 4, 5, 7, 8, 13, 14, 15, 16, 17, 50, 51, 52, 53};
    private final int[] inputBorder = {9, 10, 11, 12, 13, 18, 22, 27, 31, 36, 40, 45, 46, 47, 48, 49};
    private final int[] outputBorder = {23, 24, 25, 26, 32, 35, 41, 42, 43, 44};
    private final int[] inputSlots = {19, 20, 21, 28, 29, 30, 37, 38, 39};
    private final int[] outputSlots = {33, 34};
    private final String machineName;
    private final Material material;
    private final MultiBlockMachine mblock;
    private final ConcurrentHashMap<BlockMenu, CachedRecipe> recipeCache = new ConcurrentHashMap<>();

    public AutoCrafter(ItemGroup category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe, String displayName, Material material, String machineName, RecipeType machineRecipes) {
        super(category, item, recipeType, recipe);

        this.machineName = machineName;
        this.material = material;
        this.mblock = (MultiBlockMachine) machineRecipes.getMachine();

        constructMenu(displayName);
        addItemHandler(onPlace());
        addItemHandler(onBreak());
    }

    @Override
    public void postRegister() {
        addWikiPage(WIKI_PAGE);
    }

    private void constructMenu(String displayName) {
        new BlockMenuPreset(getId(), displayName) {

            @Override
            public void init() {
                constructMenu(this);
            }

            @Override
            public void newInstance(@Nonnull BlockMenu menu, @Nonnull Block b) {
                SlimefunBlockData blockData = StorageCacheUtils.getBlock(b.getLocation());
                if (blockData.getData("enabled") == null || String.valueOf(false).equals(blockData.getData("enabled"))) {
                    menu.replaceExistingItem(6, new CustomItemStack(Material.GUNPOWDER, "&7Enabled: &4\u2718", "",
                        "&e> Click to enable")
                    );
                    menu.addMenuClickHandler(6, (p, slot, item, action) -> {
                        blockData.setData("enabled", String.valueOf(true));
                        // Explicitly enabling the machine re-arms a complete one-shot recipe.
                        blockData.setData(SINGLE_CRAFT_READY, String.valueOf(true));
                        newInstance(menu, b);
                        return false;
                    });
                } else {
                    menu.replaceExistingItem(6, new CustomItemStack(Material.REDSTONE, "&7Enabled: &2\u2714",
                        "", "&e> Click to disable")
                    );
                    menu.addMenuClickHandler(6, (p, slot, item, action) -> {
                        blockData.setData("enabled", String.valueOf(false));
                        newInstance(menu, b);
                        return false;
                    });
                }
            }

            @Override
            public boolean canOpen(@Nonnull Block b, @Nonnull Player p) {
                return p.hasPermission("slimefun.inventory.bypass")
                    || Slimefun.getProtectionManager().hasPermission(p, b.getLocation(),
                    Interaction.INTERACT_BLOCK);
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return new int[0];
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(DirtyChestMenu menu, ItemTransportFlow flow, ItemStack item) {
                return getCustomItemTransport(menu, flow, item);
            }
        };
    }

    protected int[] getCustomItemTransport(DirtyChestMenu menu, ItemTransportFlow flow, ItemStack item) {
        if (flow == ItemTransportFlow.WITHDRAW) {
            return getOutputSlots();
        }

        List<Integer> slots = new ArrayList<>();
        for (int slot : getInputSlots()) {
            if (menu.getItemInSlot(slot) != null) {
                slots.add(slot);
            }
        }

        slots.sort(compareSlots(menu));

        int[] array = new int[slots.size()];

        for (int i = 0; i < slots.size(); i++) {
            array[i] = slots.get(i);
        }

        return array;
    }

    private BlockPlaceHandler onPlace() {
        return new BlockPlaceHandler(true) {

            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                StorageCacheUtils.setData(e.getBlock().getLocation(), "enabled", String.valueOf(false));
                StorageCacheUtils.setData(e.getBlock().getLocation(), SINGLE_CRAFT_READY, String.valueOf(true));
            }

            @Override
            public void onBlockPlacerPlace(@Nonnull BlockPlacerPlaceEvent e) {
                StorageCacheUtils.setData(e.getBlock().getLocation(), "enabled", String.valueOf(false));
                StorageCacheUtils.setData(e.getBlock().getLocation(), SINGLE_CRAFT_READY, String.valueOf(true));
            }
        };
    }

    private BlockBreakHandler onBreak() {
        return new BlockBreakHandler(false, false) {
            @Override
            public void onPlayerBreak(@Nonnull BlockBreakEvent e, @Nonnull ItemStack i, @Nonnull List<ItemStack> list) {
                Block b = e.getBlock();
                BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());

                if (inv != null) {
                    recipeCache.remove(inv);
                    inv.dropItems(b.getLocation(), getInputSlots());
                    inv.dropItems(b.getLocation(), getOutputSlots());
                }

            }
        };
    }

    protected Comparator<Integer> compareSlots(DirtyChestMenu menu) {
        return Comparator.comparingInt(slot -> menu.getItemInSlot(slot).getAmount());
    }

    protected void constructMenu(BlockMenuPreset preset) {
        borders(preset, border, inputBorder, outputBorder);

        for (int i : getOutputSlots()) {
            preset.addMenuClickHandler(i, new ChestMenu.AdvancedMenuClickHandler() {

                @Override
                public boolean onClick(Player p, int slot, ItemStack cursor, ClickAction action) {
                    return false;
                }

                @Override
                public boolean onClick(InventoryClickEvent e, Player p, int slot, ItemStack cursor,
                                       ClickAction action) {
                    if (cursor == null) return true;
                    return cursor.getType() == Material.AIR;
                }
            });
        }

        preset.addItem(2, new CustomItemStack(new ItemStack(material), "&eHow to use",
                "", "&bPlace the recipe for the desired item inside",
                "&bOne item in each occupied slot can craft once",
                "&bFor automation, leave one template item and supply extras",
                "&4Only " + machineName + "&4 recipes are supported"
            ),
            (p, slot, item, action) -> false);
    }

    public int getEnergyConsumption() {
        return ENERGY_CONSUMPTION;
    }

    public int getCapacity() {
        return CAPACITY;
    }

    public int[] getInputSlots() {
        return inputSlots;
    }

    public int[] getOutputSlots() {
        return outputSlots;
    }

    @Nonnull
    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    @Override
    public void preRegister() {
        addItemHandler(new BlockTicker() {

            @Override
            public void tick(Block b, SlimefunItem sf, SlimefunBlockData data) {
                AutoCrafter.this.tick(b);
            }

            @Override
            public boolean isSynchronized() {
                return false;
            }
        });
    }

    protected void tick(Block block) {
        if (String.valueOf(false).equals(StorageCacheUtils.getData(block.getLocation(), "enabled"))) {
            return;
        }

        BlockMenu menu = StorageCacheUtils.getMenu(block.getLocation());
        if (menu == null) {
            return;
        }

        // Re-arm immediately after the grid is cleared, even when the machine currently has no power.
        if (isInputGridEmpty(menu)) {
            recipeCache.remove(menu);
            StorageCacheUtils.setData(block.getLocation(), SINGLE_CRAFT_READY, String.valueOf(true));
            return;
        }

        if (getCharge(block.getLocation()) < getEnergyConsumption()) {
            return;
        }

        craftIfValid(block, menu);
    }

    private void craftIfValid(Block block, BlockMenu menu) {
        // Make sure at least 1 slot is free
        for (int outSlot : getOutputSlots()) {
            ItemStack outItem = menu.getItemInSlot(outSlot);
            if (outItem == null || outItem.getAmount() < outItem.getMaxStackSize()) {
                break;
            } else if (outSlot == getOutputSlots()[1]) {
                return;
            }
        }

        boolean singleCraftReady = Boolean.parseBoolean(
            StorageCacheUtils.getData(block.getLocation(), SINGLE_CRAFT_READY)
        );

        CachedRecipe cachedRecipe = recipeCache.get(menu);
        if (cachedRecipe != null) {
            RecipeMatch cachedMatch = getRecipeMatch(menu, cachedRecipe.input);
            if (cachedMatch != RecipeMatch.NONE) {
                if (cachedMatch == RecipeMatch.SINGLE && !singleCraftReady) {
                    return;
                }

                craftRecipe(block, menu, cachedRecipe.output);
                return;
            }

            recipeCache.remove(menu, cachedRecipe);
        }

        // Resolve the recipe only when the grid no longer matches the cached recipe.
        for (ItemStack[] input : RecipeType.getRecipeInputList(mblock)) {
            RecipeMatch match = getRecipeMatch(menu, input);
            if (match == RecipeMatch.NONE) {
                continue;
            }

            ItemStack output = RecipeType.getRecipeOutputList(mblock, input).clone();
            CachedRecipe resolvedRecipe = new CachedRecipe(input.clone(), output.clone());
            recipeCache.put(menu, resolvedRecipe);

            // A retained one-shot template is still a valid recipe. Cache it and wait for refill
            // instead of scanning the complete recipe list on every ticker pass.
            if (match == RecipeMatch.SINGLE && !singleCraftReady) {
                return;
            }

            craftRecipe(block, menu, output);
            return;
        }

        // we're only executing the last possible shaped recipe
        // we don't want to allow this to be pressed instead of the default timer-based
        // execution to prevent abuse and auto clickers
    }

    private void craftRecipe(Block block, BlockMenu menu, ItemStack outputTemplate) {
        ItemStack output = outputTemplate.clone();
        if (!menu.fits(output, getOutputSlots())) {
            return;
        }

        craft(output, menu);
        StorageCacheUtils.setData(block.getLocation(), SINGLE_CRAFT_READY, String.valueOf(false));
        removeCharge(block.getLocation(), getEnergyConsumption());
    }

    private boolean isInputGridEmpty(BlockMenu menu) {
        for (int slot : getInputSlots()) {
            ItemStack item = menu.getItemInSlot(slot);
            if (item != null && item.getType() != Material.AIR) {
                return false;
            }
        }

        return true;
    }

    private RecipeMatch getRecipeMatch(BlockMenu inv, ItemStack[] recipe) {
        if (recipe == null || recipe.length != getInputSlots().length) {
            return RecipeMatch.NONE;
        }

        boolean hasSingleStackableIngredient = false;
        boolean hasBufferedStackableIngredient = false;

        for (int j = 0; j < getInputSlots().length; j++) {
            ItemStack item = inv.getItemInSlot(getInputSlots()[j]);
            if (!SlimefunUtils.isItemSimilar(item, recipe[j], true)) {
                return RecipeMatch.NONE;
            }

            if (item != null && item.getType() != Material.AIR && item.getType().getMaxStackSize() != 1) {
                if (item.getAmount() == 1) {
                    hasSingleStackableIngredient = true;
                } else {
                    hasBufferedStackableIngredient = true;
                }
            }
        }

        // A partially refilled retained template must wait until every stackable slot is buffered.
        if (hasSingleStackableIngredient && hasBufferedStackableIngredient) {
            return RecipeMatch.NONE;
        }

        return hasSingleStackableIngredient ? RecipeMatch.SINGLE : RecipeMatch.BUFFERED;
    }

    private void craft(ItemStack output, BlockMenu inv) {
        for (int j = 0; j < 9; j++) {
            ItemStack item = inv.getItemInSlot(getInputSlots()[j]);

            if (item != null && item.getType() != Material.AIR) {
                inv.consumeItem(getInputSlots()[j]);
            }
        }

        inv.pushItem(output, outputSlots);
    }

    private static final class CachedRecipe {
        private final ItemStack[] input;
        private final ItemStack output;

        private CachedRecipe(ItemStack[] input, ItemStack output) {
            this.input = input;
            this.output = output;
        }
    }

    private enum RecipeMatch {
        NONE,
        SINGLE,
        BUFFERED
    }

    static void borders(BlockMenuPreset preset, int[] border, int[] inputBorder, int[] outputBorder) {
        for (int i : border) {
            preset.addItem(i, new CustomItemStack(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), " "),
                (p, slot, item, action) -> false);
        }

        for (int i : inputBorder) {
            preset.addItem(i, new CustomItemStack(new ItemStack(Material.CYAN_STAINED_GLASS_PANE), " "),
                (p, slot, item, action) -> false);
        }

        for (int i : outputBorder) {
            preset.addItem(i, new CustomItemStack(new ItemStack(Material.ORANGE_STAINED_GLASS_PANE), " "),
                (p, slot, item, action) -> false);
        }
    }
}
