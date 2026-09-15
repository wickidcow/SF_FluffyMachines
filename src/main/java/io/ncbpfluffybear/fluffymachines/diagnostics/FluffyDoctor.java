package io.ncbpfluffybear.fluffymachines.diagnostics;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.BlockDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunChunkData;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
import io.ncbpfluffybear.fluffymachines.items.MiniBarrel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * Loaded-world reconciliation for persistence-sensitive Fluffy Machines content.
 *
 * <p>This doctor intentionally does not rewrite item IDs or truncate stored barrel contents.
 * It validates the existing schema and only repairs values when an empty state is provable.
 */
public final class FluffyDoctor {

    private static final int DISPLAY_SLOT = 31;
    private static final int DETAIL_LIMIT = 40;
    private static final Set<String> BARREL_IDS = createBarrelIds();

    private FluffyDoctor() {
    }

    public static @Nonnull FluffyDoctorReport run(boolean repair) {
        MutableReport report = new MutableReport();

        try {
            BlockDataController controller = Slimefun.getDatabaseManager().getBlockDataController();
            for (SlimefunChunkData chunkData : controller.getAllLoadedChunkData()) {
                for (SlimefunBlockData blockData : chunkData.getAllBlockData()) {
                    String sfId = blockData.getSfId();
                    if (!BARREL_IDS.contains(sfId)) {
                        continue;
                    }
                    inspectBarrel(blockData, sfId, repair, report);
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            report.failures++;
            report.detail("Doctor could not enumerate loaded Slimefun block data: " + ex.getClass().getSimpleName());
        }

        return report.freeze();
    }

    private static void inspectBarrel(
            SlimefunBlockData blockData, String sfId, boolean repair, MutableReport report) {
        report.scanned++;

        try {
            String storedRaw = blockData.getData("stored");
            Integer stored = parseNonNegative(storedRaw);
            BlockMenu menu = blockData.getBlockMenu();
            ItemStack display = menu == null ? null : menu.getItemInSlot(DISPLAY_SLOT);
            boolean emptyDisplay = display == null || display.getType() == Material.BARRIER;

            if (stored == null) {
                report.issue(sfId, blockData, "invalid or missing 'stored' count: " + printable(storedRaw));
                if (repair && emptyDisplay) {
                    blockData.setData("stored", "0");
                    stored = 0;
                    report.repaired++;
                    report.detail(locationPrefix(sfId, blockData) + "repaired empty barrel 'stored' count to 0");
                }
            }

            int capacity = configuredCapacity(sfId);
            if ("MINI_FLUFFY_BARREL".equals(sfId)) {
                String maxRaw = blockData.getData("max-size");
                Integer maxSize = parsePositive(maxRaw);
                int configuredMaximum = MiniBarrel.getDisplayCapacity();
                if (maxSize == null || maxSize > configuredMaximum) {
                    report.issue(sfId, blockData, "invalid Mini Barrel 'max-size': " + printable(maxRaw));
                    if (repair) {
                        int safeSize = stored != null && stored > configuredMaximum ? stored : configuredMaximum;
                        blockData.setData("max-size", Integer.toString(safeSize));
                        capacity = safeSize;
                        report.repaired++;
                        report.detail(locationPrefix(sfId, blockData) + "repaired Mini Barrel capacity to " + safeSize);
                    }
                } else {
                    capacity = maxSize;
                }
            }

            if (stored != null && capacity > 0 && stored > capacity) {
                report.issue(sfId, blockData, "stored count " + stored + " exceeds configured capacity " + capacity
                        + "; contents were NOT truncated");
            }

            if (stored != null && stored > 0 && emptyDisplay) {
                report.issue(sfId, blockData,
                        "has " + stored + " stored items but no keyed display item; manual recovery is required");
            }
        } catch (RuntimeException | LinkageError ex) {
            report.failures++;
            report.detail(locationPrefix(sfId, blockData) + "inspection failed: " + ex.getClass().getSimpleName());
        }
    }

    private static int configuredCapacity(String sfId) {
        if ("MINI_FLUFFY_BARREL".equals(sfId)) {
            return MiniBarrel.getDisplayCapacity();
        }
        int configured = Slimefun.getItemCfg().getInt(sfId + ".capacity");
        if (configured > 0) {
            return configured;
        }
        for (Barrel.BarrelType type : Barrel.BarrelType.values()) {
            if (type.getKey().equals(sfId)) {
                return type.getDefaultSize();
            }
        }
        return 0;
    }

    private static Integer parseNonNegative(String value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parsePositive(String value) {
        Integer parsed = parseNonNegative(value);
        return parsed != null && parsed > 0 ? parsed : null;
    }

    private static String printable(String value) {
        return value == null ? "<missing>" : "'" + value + "'";
    }

    private static Set<String> createBarrelIds() {
        Set<String> ids = new HashSet<>();
        ids.add("MINI_FLUFFY_BARREL");
        for (Barrel.BarrelType type : Barrel.BarrelType.values()) {
            ids.add(type.getKey());
        }
        return Set.copyOf(ids);
    }

    private static String locationPrefix(String sfId, SlimefunBlockData blockData) {
        var location = blockData.getLocation();
        return sfId + " @ " + location.getWorld().getName() + " " + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ() + ": ";
    }

    private static final class MutableReport {
        private long scanned;
        private long issues;
        private long repaired;
        private long failures;
        private final List<String> details = new ArrayList<>();

        private void issue(String sfId, SlimefunBlockData blockData, String message) {
            issues++;
            detail(locationPrefix(sfId, blockData) + message);
        }

        private void detail(String message) {
            if (details.size() < DETAIL_LIMIT) {
                details.add(message);
            }
        }

        private FluffyDoctorReport freeze() {
            return new FluffyDoctorReport(scanned, issues, repaired, failures, details);
        }
    }
}
