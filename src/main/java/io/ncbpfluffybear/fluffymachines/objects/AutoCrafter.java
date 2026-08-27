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
import org.bukkit.Location;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final Map<String, CachedRecipe> recipeCache = new ConcurrentHashMap<>();
    private final Object recipeIndexLock = new Object();
    private volatile Map<Integer, List<IndexedRecipe>> recipeShapeIndex = Map.of();
    private volatile int indexedRecipeStorageSize = -1;

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
                Location location = e.getBlock().getLocation();
                StorageCacheUtils.setData(location, "enabled", String.valueOf(false));
                StorageCacheUtils.setData(location, SINGLE_CRAFT_READY, String.valueOf(true));
                clearRecipeCache(location);
            }

            @Override
            public void onBlockPlacerPlace(@Nonnull BlockPlacerPlaceEvent e) {
                Location location = e.getBlock().getLocation();
                StorageCacheUtils.setData(location, "enabled", String.valueOf(false));
                StorageCacheUtils.setData(location, SINGLE_CRAFT_READY, String.valueOf(true));
                clearRecipeCache(location);
            }
        };
    }

    private BlockBreakHandler onBreak() {
        return new BlockBreakHandler(false, false) {
            @Override
            public void onPlayerBreak(@Nonnull BlockBreakEvent e, @Nonnull ItemStack i, @Nonnull List<ItemStack> list) {
                Block b = e.getBlock();
                Location location = b.getLocation();
                clearRecipeCache(location);
                BlockMenu inv = StorageCacheUtils.getMenu(location);

                if (inv != null) {
                    inv.dropItems(location, getInputSlots());
                    inv.dropItems(location, getOutputSlots());
                }

            }
        };
    }

    private void clearRecipeCache(Location location) {
        SlimefunBlockData blockData = StorageCacheUtils.getBlock(location);
        if (blockData != null) {
            recipeCache.remove(blockData.getKey());
        }
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
                AutoCrafter.this.tick(b, data);
            }

            @Override
            public boolean isSynchronized() {
                return false;
            }
        });
    }

    protected void tick(Block block) {
        SlimefunBlockData blockData = StorageCacheUtils.getBlock(block.getLocation());
        if (blockData != null) {
            tick(block, blockData);
        }
    }

    private void tick(Block block, SlimefunBlockData blockData) {
        if (String.valueOf(false).equals(blockData.getData("enabled"))) {
            return;
        }

        Location location = blockData.getLocation();
        BlockMenu menu = blockData.getBlockMenu();
        if (menu == null) {
            return;
        }

        if (isInputGridEmpty(menu)) {
            if (!String.valueOf(true).equals(blockData.getData(SINGLE_CRAFT_READY))) {
                blockData.setData(SINGLE_CRAFT_READY, String.valueOf(true));
            }
            return;
        }

        if (getCharge(location) < getEnergyConsumption()) {
            return;
        }

        craftIfValid(blockData, menu, location);
    }

    private void craftIfValid(SlimefunBlockData blockData, BlockMenu menu, Location location) {
        for (int outSlot : getOutputSlots()) {
            ItemStack outItem = menu.getItemInSlot(outSlot);
            if (outItem == null || outItem.getAmount() < outItem.getMaxStackSize()) {
                break;
            } else if (outSlot == getOutputSlots()[1]) {
                return;
            }
        }

        boolean singleCraftReady = Boolean.parseBoolean(blockData.getData(SINGLE_CRAFT_READY));
        String blockKey = blockData.getKey();
        CachedRecipe cachedRecipe = recipeCache.get(blockKey);
        RecipeMatch match = RecipeMatch.NONE;

        if (cachedRecipe != null) {
            if (cachedRecipe.hasRecipe()) {
                match = getRecipeMatch(menu, cachedRecipe.recipeInput);
                if (match != RecipeMatch.NONE) {
                    craftCachedRecipe(blockData, menu, location, cachedRecipe, match, singleCraftReady);
                    return;
                }
            }

            // Keep negative caches and amount-only failures cached. Only resolve again if the
            // amount-independent recipe template actually changed.
            if (cachedRecipe.matches(menu)) {
                return;
            }
        }

        cachedRecipe = resolveRecipe(blockKey, menu);
        if (!cachedRecipe.hasRecipe()) {
            return;
        }

        match = getRecipeMatch(menu, cachedRecipe.recipeInput);
        if (match == RecipeMatch.NONE) {
            return;
        }

        craftCachedRecipe(blockData, menu, location, cachedRecipe, match, singleCraftReady);
    }

    private void craftCachedRecipe(SlimefunBlockData blockData, BlockMenu menu, Location location,
                                   CachedRecipe cachedRecipe, RecipeMatch match, boolean singleCraftReady) {
        if (match == RecipeMatch.SINGLE && !singleCraftReady) {
            return;
        }

        ItemStack output = cachedRecipe.output.clone();
        if (!menu.fits(output, getOutputSlots())) {
            return;
        }

        craft(output, menu);
        blockData.setData(SINGLE_CRAFT_READY, String.valueOf(false));
        removeCharge(location, getEnergyConsumption());
    }

    private CachedRecipe resolveRecipe(String blockKey, BlockMenu menu) {
        ItemStack[] template = snapshotTemplate(menu);
        int shape = getShape(template);

        for (IndexedRecipe recipe : getRecipeCandidates(shape)) {
            if (!matchesRecipeTemplate(menu, recipe.input)) {
                continue;
            }

            CachedRecipe resolved = new CachedRecipe(
                template,
                recipe.input,
                recipe.output == null ? null : recipe.output.clone()
            );
            recipeCache.put(blockKey, resolved);
            return resolved;
        }

        CachedRecipe noMatch = new CachedRecipe(template, null, null);
        recipeCache.put(blockKey, noMatch);
        return noMatch;
    }

    private List<IndexedRecipe> getRecipeCandidates(int shape) {
        ensureRecipeIndex();
        return recipeShapeIndex.getOrDefault(shape, List.of());
    }

    private void ensureRecipeIndex() {
        List<ItemStack[]> recipeStorage = mblock.getRecipes();
        int storageSize = recipeStorage.size();
        if (storageSize == indexedRecipeStorageSize) {
            return;
        }

        synchronized (recipeIndexLock) {
            recipeStorage = mblock.getRecipes();
            storageSize = recipeStorage.size();
            if (storageSize == indexedRecipeStorageSize) {
                return;
            }

            Map<Integer, List<IndexedRecipe>> newIndex = new HashMap<>();
            for (int i = 0; i + 1 < storageSize; i += 2) {
                ItemStack[] input = recipeStorage.get(i);
                if (input == null || input.length != getInputSlots().length) {
                    continue;
                }

                ItemStack[] outputEntry = recipeStorage.get(i + 1);
                ItemStack output = outputEntry != null && outputEntry.length > 0 ? outputEntry[0] : null;
                int shape = getShape(input);
                newIndex.computeIfAbsent(shape, ignored -> new ArrayList<>())
                    .add(new IndexedRecipe(input, output));
            }

            Map<Integer, List<IndexedRecipe>> immutableIndex = new HashMap<>();
            newIndex.forEach((shape, recipes) -> immutableIndex.put(shape, List.copyOf(recipes)));
            recipeShapeIndex = Map.copyOf(immutableIndex);
            indexedRecipeStorageSize = storageSize;
        }
    }

    private int getShape(ItemStack[] items) {
        int shape = 0;
        for (int i = 0; i < getInputSlots().length; i++) {
            ItemStack item = items[i];
            if (item != null && item.getType() != Material.AIR) {
                shape |= 1 << i;
            }
        }
        return shape;
    }

    private boolean matchesRecipeTemplate(BlockMenu inv, ItemStack[] recipe) {
        if (recipe == null || recipe.length != getInputSlots().length) {
            return false;
        }

        for (int j = 0; j < getInputSlots().length; j++) {
            ItemStack item = inv.getItemInSlot(getInputSlots()[j]);
            if (!SlimefunUtils.isItemSimilar(item, recipe[j], true, false)) {
                return false;
            }
        }

        return true;
    }

    private ItemStack[] snapshotTemplate(BlockMenu menu) {
        ItemStack[] template = new ItemStack[getInputSlots().length];
        for (int j = 0; j < getInputSlots().length; j++) {
            ItemStack item = menu.getItemInSlot(getInputSlots()[j]);
            if (item != null && item.getType() != Material.AIR) {
                ItemStack clone = item.clone();
                clone.setAmount(1);
                template[j] = clone;
            }
        }
        return template;
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

    private static final class IndexedRecipe {
        private final ItemStack[] input;
        private final ItemStack output;

        private IndexedRecipe(ItemStack[] input, ItemStack output) {
            this.input = input;
            this.output = output;
        }
    }

    private final class CachedRecipe {
        private final ItemStack[] template;
        private final ItemStack[] recipeInput;
        private final ItemStack output;

        private CachedRecipe(ItemStack[] template, ItemStack[] recipeInput, ItemStack output) {
            this.template = template;
            this.recipeInput = recipeInput;
            this.output = output;
        }

        private boolean hasRecipe() {
            return recipeInput != null && output != null;
        }

        private boolean matches(BlockMenu menu) {
            for (int j = 0; j < getInputSlots().length; j++) {
                ItemStack current = menu.getItemInSlot(getInputSlots()[j]);
                if (!SlimefunUtils.isItemSimilar(current, template[j], true, false)) {
                    return false;
                }
            }
            return true;
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
