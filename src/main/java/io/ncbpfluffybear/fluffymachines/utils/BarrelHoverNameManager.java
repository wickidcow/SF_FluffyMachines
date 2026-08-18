package io.ncbpfluffybear.fluffymachines.utils;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.ncbpfluffybear.fluffymachines.FluffyMachines;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shows an item-frame-style name label when a player points at the item displayed
 * on the face of a Fluffy Barrel.
 *
 * <p>ItemDisplay entities deliberately have no hitbox, so vanilla cannot produce
 * the normal item-frame hover label for them. This manager reuses the barrel-face
 * ray trace and displays a private TextDisplay for the viewing player only. The
 * stored ItemStack is never renamed or modified.</p>
 */
public final class BarrelHoverNameManager {

    private static final Map<UUID, TextDisplay> LABELS = new HashMap<>();
    private static final Set<UUID> VISIBLE_LABELS = new HashSet<>();
    private static final float LABEL_SCALE = 0.65F;
    private static boolean initialized;

    private BarrelHoverNameManager() {
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
        Bukkit.getScheduler().runTaskTimer(plugin, BarrelHoverNameManager::updateHoverNames, 10L, 5L);
    }

    public static void shutdown() {
        for (TextDisplay label : LABELS.values()) {
            if (label != null && label.isValid()) {
                label.remove();
            }
        }

        LABELS.clear();
        VISIBLE_LABELS.clear();
        initialized = false;
    }

    private static void updateHoverNames() {
        FluffyMachines plugin = FluffyMachines.getInstance();
        if (plugin == null) {
            return;
        }

        Set<UUID> onlinePlayers = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getUniqueId());

            HoverTarget target = findHoverTarget(player);
            if (target == null) {
                hideLabel(plugin, player);
                continue;
            }

            showLabel(plugin, player, target.block, target.item);
        }

        Iterator<Map.Entry<UUID, TextDisplay>> iterator = LABELS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, TextDisplay> entry = iterator.next();
            if (onlinePlayers.contains(entry.getKey())) {
                continue;
            }

            TextDisplay label = entry.getValue();
            if (label != null && label.isValid()) {
                label.remove();
            }

            VISIBLE_LABELS.remove(entry.getKey());
            iterator.remove();
        }
    }

    private static HoverTarget findHoverTarget(@Nonnull Player player) {
        RayTraceResult result = player.rayTraceBlocks(5.0D);
        if (result == null || result.getHitBlock() == null) {
            return null;
        }

        Block block = result.getHitBlock();
        if (!(StorageCacheUtils.getSfItem(block.getLocation()) instanceof Barrel barrel)
            || !isInsideFrontHoverZone(result, block)) {
            return null;
        }

        try {
            if (barrel.getStored(block) <= 0) {
                return null;
            }

            ItemStack item = barrel.getStoredItem(block);
            if (item == null || item.getType() == Material.BARRIER || item.getType().isAir()) {
                return null;
            }

            return new HoverTarget(block, item);
        } catch (RuntimeException ignored) {
            // Slimefun block data may still be loading during a chunk transition.
            return null;
        }
    }

    private static void showLabel(
        @Nonnull FluffyMachines plugin,
        @Nonnull Player player,
        @Nonnull Block block,
        @Nonnull ItemStack item
    ) {
        UUID playerId = player.getUniqueId();
        Location target = getLabelLocation(block, getDisplayFace(block));
        TextDisplay label = LABELS.get(playerId);

        if (label == null || !label.isValid() || label.getWorld() != target.getWorld()) {
            if (label != null && label.isValid()) {
                label.remove();
            }

            label = spawnLabel(target);
            LABELS.put(playerId, label);
            VISIBLE_LABELS.remove(playerId);
        } else if (label.getLocation().distanceSquared(target) > 0.0001D) {
            label.teleport(target);
        }

        label.text(getActualItemName(item));

        if (VISIBLE_LABELS.add(playerId)) {
            player.showEntity(plugin, label);
        }
    }

    private static TextDisplay spawnLabel(@Nonnull Location location) {
        return location.getWorld().spawn(location, TextDisplay.class, label -> {
            label.setVisibleByDefault(false);
            label.setPersistent(false);
            label.setGravity(false);
            label.setInvulnerable(true);
            label.setSilent(true);
            label.setBillboard(Display.Billboard.CENTER);
            label.setDefaultBackground(true);
            label.setShadowed(true);
            label.setSeeThrough(false);
            label.setAlignment(TextDisplay.TextAlignment.CENTER);

            Transformation transformation = label.getTransformation();
            transformation.getScale().set(LABEL_SCALE, LABEL_SCALE, LABEL_SCALE);
            label.setTransformation(transformation);
        });
    }

    private static void hideLabel(@Nonnull FluffyMachines plugin, @Nonnull Player player) {
        UUID playerId = player.getUniqueId();
        if (!VISIBLE_LABELS.remove(playerId)) {
            return;
        }

        TextDisplay label = LABELS.get(playerId);
        if (label != null && label.isValid()) {
            player.hideEntity(plugin, label);
        }
    }

    private static BlockFace getDisplayFace(@Nonnull Block block) {
        if (block.getBlockData() instanceof Directional directional) {
            return directional.getFacing();
        }

        return BlockFace.SOUTH;
    }

    private static boolean isInsideFrontHoverZone(@Nonnull RayTraceResult result, @Nonnull Block block) {
        BlockFace face = getDisplayFace(block);
        if (result.getHitBlockFace() != face) {
            return false;
        }

        Vector hit = result.getHitPosition();
        double localX = hit.getX() - block.getX();
        double localY = hit.getY() - block.getY();
        double localZ = hit.getZ() - block.getZ();

        return switch (face) {
            case NORTH, SOUTH -> inCenterHalf(localX) && inCenterHalf(localY);
            case EAST, WEST -> inCenterHalf(localZ) && inCenterHalf(localY);
            case UP, DOWN -> inCenterHalf(localX) && inCenterHalf(localZ);
            default -> false;
        };
    }

    private static boolean inCenterHalf(double coordinate) {
        return coordinate >= 0.25D && coordinate <= 0.75D;
    }

    private static Location getLabelLocation(@Nonnull Block block, @Nonnull BlockFace face) {
        Vector faceOffset = new Vector(face.getModX(), face.getModY(), face.getModZ()).multiply(0.62D);
        return block.getLocation()
            .add(0.5D, 0.90D, 0.5D)
            .add(faceOffset);
    }

    /**
     * Uses the item's real display component without changing its metadata. This
     * means an ordinary unrenamed vanilla item still shows its translated vanilla
     * name, while custom/Slimefun item names remain intact.
     */
    @Nonnull
    private static Component getActualItemName(@Nonnull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            Component customName = meta.customName();
            if (customName != null) {
                return customName;
            }

            if (meta.hasItemName()) {
                return meta.itemName();
            }
        }

        return item.effectiveName();
    }

    private static final class HoverTarget {
        private final Block block;
        private final ItemStack item;

        private HoverTarget(@Nonnull Block block, @Nonnull ItemStack item) {
            this.block = block;
            this.item = item;
        }
    }
}
