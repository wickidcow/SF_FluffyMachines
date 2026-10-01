package io.ncbpfluffybear.fluffymachines.listeners;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Click-through only for allowed, still-attached frames on this addon's barrels. */
public final class BarrelItemFrameListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBarrelItemFrameClick(PlayerInteractEntityEvent event) {
        if (event.isCancelled() || event.getHand() != EquipmentSlot.HAND
                || !(event.getRightClicked() instanceof ItemFrame frame)) return;
        Player player = event.getPlayer();
        Block block = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        if (!nearby(player, frame, block)) return;
        var item = StorageCacheUtils.getSlimefunItem(block.getLocation());
        if (!(item instanceof Barrel)) return;
        BlockMenu menu = StorageCacheUtils.getMenu(block.getLocation());
        BlockMenuPreset preset = BlockMenuPreset.getPreset(item.getId());
        if (menu == null || preset == null || !preset.canOpen(block, player)) return;
        event.setCancelled(true);
        Utils.runSync(() -> {
            if (!nearby(player, frame, block)) return;
            if (StorageCacheUtils.getSlimefunItem(block.getLocation()) != item
                    || StorageCacheUtils.getMenu(block.getLocation()) != menu) return;
            BlockMenuPreset current = BlockMenuPreset.getPreset(item.getId());
            if (current != null && current.canOpen(block, player)) menu.open(player);
        }, 1L);
    }
    private static boolean nearby(Player player, ItemFrame frame, Block block) {
        return player.isOnline() && frame.isValid() && player.getWorld() == block.getWorld()
                && frame.getWorld() == block.getWorld()
                && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)
                && player.getLocation().distanceSquared(block.getLocation().add(0.5,0.5,0.5)) <= 36.0
                && frame.getLocation().getBlock().getRelative(frame.getAttachedFace()).equals(block);
    }
}
