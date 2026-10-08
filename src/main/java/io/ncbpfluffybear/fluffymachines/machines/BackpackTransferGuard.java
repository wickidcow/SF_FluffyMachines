package io.ncbpfluffybear.fluffymachines.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;

/** Validates a machine again after an asynchronous backpack lookup. */
final class BackpackTransferGuard {

    private BackpackTransferGuard() {
    }

    // Call from the server thread. Checking the cache must not reload an unloaded chunk.
    static boolean isCurrentMachine(BlockMenu menu, String machineId) {
        Location location = menu.getLocation();
        World world = location.getWorld();
        if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)
            || menu.locked()) {
            return false;
        }

        SlimefunBlockData data = StorageCacheUtils.getBlock(location);
        return data != null && data.isDataLoaded() && !data.isPendingRemove()
            && machineId.equals(data.getSfId()) && data.getBlockMenu() == menu;
    }

    static boolean isCurrentBackpack(BlockMenu menu, String machineId, int slot, ItemStack expected) {
        return isCurrentMachine(menu, machineId) && expected.equals(menu.getItemInSlot(slot));
    }
}
