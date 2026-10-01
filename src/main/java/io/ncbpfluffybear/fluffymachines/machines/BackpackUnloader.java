package io.ncbpfluffybear.fluffymachines.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.backpacks.SlimefunBackpack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.inventory.DirtyChestMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import java.util.List;

public class BackpackUnloader extends SlimefunItem implements EnergyNetComponent {

    public static final int ENERGY_CONSUMPTION = 16;
    public static final int CAPACITY = ENERGY_CONSUMPTION * 3;

    private static final int[] PLAIN_BORDER = {2, 3, 4, 5, 6, 7, 8, 11, 12, 13, 14, 15, 16, 17};
    private static final int[] INPUT_BORDER = {1, 9, 10};
    private static final int[] OUTPUT_BORDER = {18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 35, 36, 44, 45, 46, 47, 48,
        49, 50, 51, 52, 53};
    private static final int[] INPUT_SLOTS = {0};
    private static final int[] OUTPUT_SLOTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    public BackpackUnloader(ItemGroup category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, recipeType, recipe);
        addItemHandler(onBreak());

        new BlockMenuPreset(getId(), "&eBackpack Unloader") {
            @Override
            public void init() {
                BackpackLoader.buildBorder(this, PLAIN_BORDER, INPUT_BORDER, OUTPUT_BORDER);
            }

            @Override
            public boolean canOpen(@Nonnull Block b, @Nonnull Player p) {
                return p.hasPermission("slimefun.inventory.bypass")
                    || Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), Interaction.INTERACT_BLOCK);
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                return new int[0];
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(DirtyChestMenu menu, ItemTransportFlow flow, ItemStack item) {
                return flow == ItemTransportFlow.WITHDRAW ? getOutputSlots() : getInputSlots();
            }
        };
    }

    private BlockBreakHandler onBreak() {
        return new BlockBreakHandler(false, false) {
            @Override
            public void onPlayerBreak(@Nonnull BlockBreakEvent e, @Nonnull ItemStack item, @Nonnull List<ItemStack> drops) {
                Block b = e.getBlock();
                BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), getInputSlots());
                    inv.dropItems(b.getLocation(), getOutputSlots());
                }
            }
        };
    }

    @Override
    public void preRegister() {
        this.addItemHandler(new BlockTicker() {
            public void tick(Block b, SlimefunItem sf, SlimefunBlockData data) {
                BackpackUnloader.this.tick(b);
            }

            public boolean isSynchronized() {
                return false;
            }
        });
    }

    private void tick(@Nonnull Block b) {
        if (getCharge(b.getLocation()) < ENERGY_CONSUMPTION) {
            return;
        }

        final BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());
        if (inv == null) {
            return;
        }

        for (int outputSlot : getOutputSlots()) {
            if (inv.getItemInSlot(outputSlot) == null) {
                break;
            } else if (outputSlot == getOutputSlots()[13]) {
                return;
            }
        }

        ItemStack inputItem = inv.getItemInSlot(getInputSlots()[0]);
        if (inputItem != null) {
            SlimefunItem sfItem = SlimefunItem.getByItem(inputItem);
            if (sfItem instanceof SlimefunBackpack) {
                if (!PlayerBackpack.hasBackpackIdentity(inputItem.getItemMeta())) {
                    rejectInput(inv);
                    return;
                }

                PlayerBackpack.getAsync(inputItem, backpack -> {
                    if (backpack == null) {
                        return;
                    }

                    Utils.runSync(() -> {
                        Inventory backpackInventory = backpack.getInventory();
                        for (int slot = 0; slot < backpackInventory.getSize(); slot++) {
                            ItemStack transferItem = backpackInventory.getItem(slot);
                            if (transferItem != null) {
                                if (!inv.fits(transferItem, getOutputSlots())) {
                                    return;
                                }

                                inv.pushItem(transferItem.clone(), getOutputSlots());
                                backpackInventory.setItem(slot, null);
                                Slimefun.getDatabaseManager().getProfileDataController().saveBackpackInventory(backpack);
                                removeCharge(b.getLocation(), ENERGY_CONSUMPTION);
                                return;
                            }
                        }

                        rejectInput(inv);
                    });
                }, false);
            } else {
                rejectInput(inv);
            }
        }
    }

    private void rejectInput(BlockMenu inv) {
        ItemStack transferItem = inv.getItemInSlot(getInputSlots()[0]);
        inv.replaceExistingItem(getInputSlots()[0], null);
        inv.pushItem(transferItem, getOutputSlots());
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

    public int[] getInputSlots() {
        return INPUT_SLOTS;
    }

    public int[] getOutputSlots() {
        return OUTPUT_SLOTS;
    }
}
