package io.ncbpfluffybear.fluffymachines.listeners;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
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

/**
 * Makes player-placed item frames on Fluffy Barrels behave like click-through
 * decorations. ItemDisplay entities used by the native barrel display already have
 * no interaction hitbox, but real ItemFrame/GlowItemFrame entities normally consume
 * the player's right-click before Slimefun can open the barrel behind them.
 */
public final class BarrelItemFrameListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBarrelItemFrameClick(PlayerInteractEntityEvent event) {
        // Bukkit can fire entity interaction for both hands. Handle the main-hand pass
        // only so one physical click cannot schedule the barrel menu twice.
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }

        // ItemFrame#getAttachedFace points toward the supporting block. GlowItemFrame
        // is also covered because it extends ItemFrame.
        Block attachedBlock = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        SlimefunItem slimefunItem = StorageCacheUtils.getSfItem(attachedBlock.getLocation());
        if (!(slimefunItem instanceof Barrel)) {
            return;
        }

        BlockMenu menu = StorageCacheUtils.getMenu(attachedBlock.getLocation());
        BlockMenuPreset preset = BlockMenuPreset.getPreset(slimefunItem.getId());
        Player player = event.getPlayer();

        // Reuse the exact BlockMenuPreset permission/open check that a direct barrel
        // click uses. This preserves Slimefun protection integrations and also refreshes
        // the Fluffy Barrel menu before it is shown.
        if (menu == null || preset == null || !preset.canOpen(attachedBlock, player)) {
            return;
        }

        // Stop the frame from rotating/accepting an item. Opening one tick later also
        // wins over invisible-item-frame plugins that try to pass the same click through
        // to the underlying vanilla Barrel inventory during this event tick.
        event.setCancelled(true);
        Utils.runSync(() -> openBarrelIfStillValid(player, attachedBlock), 1L);
    }

    private static void openBarrelIfStillValid(Player player, Block attachedBlock) {
        if (!player.isOnline()) {
            return;
        }

        SlimefunItem currentItem = StorageCacheUtils.getSfItem(attachedBlock.getLocation());
        if (!(currentItem instanceof Barrel)) {
            return;
        }

        BlockMenu currentMenu = StorageCacheUtils.getMenu(attachedBlock.getLocation());
        if (currentMenu != null) {
            currentMenu.open(player);
        }
    }
}
