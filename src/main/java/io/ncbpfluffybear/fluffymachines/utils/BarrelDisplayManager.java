package io.ncbpfluffybear.fluffymachines.utils;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.ncbpfluffybear.fluffymachines.FluffyMachines;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the non-interactive item icon shown on the face of a Fluffy Barrel.
 *
 * <p>The display is an ItemDisplay rather than an ItemFrame, so it cannot intercept
 * barrel right-clicks. Hover information is shown through the action bar by ray
 * tracing the barrel block itself.</p>
 */
public final class BarrelDisplayManager {

    private static final Map<String, DisplayState> STATES = new HashMap<>();
    private static final float DISPLAY_SCALE = 0.50F;
    private static final double HOVER_RADIUS = 0.34D;
    private static boolean initialized;
    private static NamespacedKey displayKey;

    private BarrelDisplayManager() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        FluffyMachines plugin = FluffyMachines.getInstance();
        if (plugin == null) {
            return;
        }

        initialized = true;
        displayKey = new NamespacedKey(plugin, "barrel_item_display");

        Bukkit.getScheduler().runTaskTimer(plugin, BarrelDisplayManager::showHoverText, 10L, 5L);
        Bukkit.getScheduler().runTaskTimer(plugin, BarrelDisplayManager::pruneStateCache, 600L, 600L);
    }

    /**
     * Ensures that the barrel has exactly one ItemDisplay showing its current item.
     * This method is safe to call from the barrel ticker because unchanged displays
     * return immediately without searching nearby entities.
     */
    public static void update(@Nonnull Block block, @Nonnull Barrel barrel) {
        initialize();
        if (!initialized) {
            return;
        }

        int stored;
        ItemStack storedItem;
        try {
            stored = barrel.getStored(block);
            storedItem = barrel.getStoredItem(block);
        } catch (RuntimeException ex) {
            return;
        }

        if (stored <= 0 || storedItem == null || storedItem.getType() == Material.BARRIER || storedItem.getType().isAir()) {
            remove(block);
            return;
        }

        ItemStack shownItem = storedItem.clone();
        shownItem.setAmount(1);

        BlockFace face = getDisplayFace(block);
        int fingerprint = 31 * shownItem.hashCode() + face.ordinal();
        String barrelKey = getBarrelKey(block);
        DisplayState state = STATES.get(barrelKey);

        if (state != null && state.fingerprint == fingerprint) {
            Entity existing = Bukkit.getServer().getEntity(state.entityId);
            if (existing instanceof ItemDisplay && existing.isValid()) {
                return;
            }
        }

        ItemDisplay display = findExistingDisplay(block, barrelKey);
        if (display == null) {
            Location location = getDisplayLocation(block, face);
            display = block.getWorld().spawn(location, ItemDisplay.class);
        }

        configureDisplay(display, block, barrelKey, face, shownItem);
        STATES.put(barrelKey, new DisplayState(
            fingerprint,
            display.getUniqueId(),
            block.getWorld().getUID(),
            block.getX() >> 4,
            block.getZ() >> 4
        ));
    }

    /**
     * Removes all native item displays linked to this barrel.
     */
    public static void remove(@Nonnull Block block) {
        initialize();
        if (!initialized) {
            return;
        }

        String barrelKey = getBarrelKey(block);
        DisplayState state = STATES.remove(barrelKey);
        if (state != null) {
            Entity entity = Bukkit.getServer().getEntity(state.entityId);
            if (entity instanceof ItemDisplay) {
                entity.remove();
            }
        }

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : block.getWorld().getNearbyEntities(center, 1.25, 1.25, 1.25)) {
            if (entity instanceof ItemDisplay display && barrelKey.equals(getDisplayOwner(display))) {
                display.remove();
            }
        }
    }

    private static void configureDisplay(
        @Nonnull ItemDisplay display,
        @Nonnull Block block,
        @Nonnull String barrelKey,
        @Nonnull BlockFace face,
        @Nonnull ItemStack shownItem
    ) {
        Location target = getDisplayLocation(block, face);
        if (display.getWorld() != target.getWorld() || display.getLocation().distanceSquared(target) > 0.0001D) {
            display.teleport(target);
        }

        display.setItemStack(shownItem);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
        display.setBillboard(Display.Billboard.CENTER);

        Transformation transformation = display.getTransformation();
        transformation.getScale().set(DISPLAY_SCALE, DISPLAY_SCALE, DISPLAY_SCALE);
        display.setTransformation(transformation);

        display.setGravity(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setPersistent(true);
        display.setShadowRadius(0.0F);
        display.setShadowStrength(0.0F);
        display.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, barrelKey);
    }

    private static ItemDisplay findExistingDisplay(@Nonnull Block block, @Nonnull String barrelKey) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        ItemDisplay result = null;

        for (Entity entity : block.getWorld().getNearbyEntities(center, 1.25, 1.25, 1.25)) {
            if (!(entity instanceof ItemDisplay display) || !barrelKey.equals(getDisplayOwner(display))) {
                continue;
            }

            if (result == null) {
                result = display;
            } else {
                // Clean up duplicate displays left by an interrupted reload/update.
                display.remove();
            }
        }

        return result;
    }

    private static String getDisplayOwner(@Nonnull ItemDisplay display) {
        return display.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
    }

    private static BlockFace getDisplayFace(@Nonnull Block block) {
        if (block.getBlockData() instanceof Directional directional) {
            return directional.getFacing();
        }

        // Some higher-tier Fluffy Barrels use non-directional block materials.
        // SOUTH gives those blocks one predictable display face.
        return BlockFace.SOUTH;
    }

    private static Location getDisplayLocation(@Nonnull Block block, @Nonnull BlockFace face) {
        Vector offset = new Vector(face.getModX(), face.getModY(), face.getModZ()).multiply(0.57D);
        return block.getLocation().add(0.5D, 0.5D, 0.5D).add(offset);
    }

    private static String getBarrelKey(@Nonnull Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private static void showHoverText() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            RayTraceResult result = player.rayTraceBlocks(5.0D);
            if (result == null || result.getHitBlock() == null) {
                continue;
            }

            Block block = result.getHitBlock();
            if (!(StorageCacheUtils.getSfItem(block.getLocation()) instanceof Barrel barrel)
                || !isLookingAtDisplay(player, block)) {
                continue;
            }

            try {
                int stored = barrel.getStored(block);
                if (stored <= 0) {
                    player.sendActionBar(Component.text("Fluffy Barrel", NamedTextColor.GOLD)
                        .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Empty", NamedTextColor.RED)));
                    continue;
                }

                ItemStack item = barrel.getStoredItem(block);
                if (item == null || item.getType() == Material.BARRIER || item.getType().isAir()) {
                    player.sendActionBar(Component.text("Fluffy Barrel", NamedTextColor.GOLD)
                        .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                        .append(Component.text("Empty", NamedTextColor.RED)));
                    continue;
                }

                String amount = String.format(Locale.US, "%,d", stored);
                Component itemName = item.effectiveName();
                player.sendActionBar(itemName
                    .append(Component.text(" • ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(amount, NamedTextColor.YELLOW))
                    .append(Component.text(" stored", NamedTextColor.GRAY)));
            } catch (RuntimeException ignored) {
                // The Slimefun block data may still be loading during a chunk transition.
            }
        }
    }

    /**
     * ItemDisplay entities deliberately have no normal interaction hitbox. Approximate
     * item-frame-style hover targeting by checking whether the player's view ray passes
     * through the small displayed icon on the barrel face.
     */
    private static boolean isLookingAtDisplay(@Nonnull Player player, @Nonnull Block block) {
        Location eye = player.getEyeLocation();
        Location target = getDisplayLocation(block, getDisplayFace(block));

        Vector toTarget = target.toVector().subtract(eye.toVector());
        double targetDistance = toTarget.length();
        if (targetDistance <= 0.01D || targetDistance > 5.25D) {
            return false;
        }

        Vector direction = eye.getDirection().normalize();
        double projection = toTarget.dot(direction);
        if (projection <= 0.0D) {
            return false;
        }

        Vector closestPoint = eye.toVector().add(direction.multiply(projection));
        return closestPoint.distanceSquared(target.toVector()) <= HOVER_RADIUS * HOVER_RADIUS;
    }

    private static void pruneStateCache() {
        STATES.entrySet().removeIf(entry -> {
            DisplayState state = entry.getValue();
            World world = Bukkit.getWorld(state.worldId);
            return world == null || !world.isChunkLoaded(state.chunkX, state.chunkZ);
        });
    }

    private static final class DisplayState {
        private final int fingerprint;
        private final UUID entityId;
        private final UUID worldId;
        private final int chunkX;
        private final int chunkZ;

        private DisplayState(int fingerprint, UUID entityId, UUID worldId, int chunkX, int chunkZ) {
            this.fingerprint = fingerprint;
            this.entityId = entityId;
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}
