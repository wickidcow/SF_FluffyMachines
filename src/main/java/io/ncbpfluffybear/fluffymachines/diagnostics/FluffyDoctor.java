package io.ncbpfluffybear.fluffymachines.diagnostics;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.BlockDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunChunkData;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
import io.ncbpfluffybear.fluffymachines.items.BarrelContents;
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
                // An empty/missing presentation is NOT evidence that the historical
                // reserve is zero. Rewriting the count could erase a valid but
                // currently unregistered item reserve. Remain read-only even for
                // an explicitly requested Doctor repair.
                report.issue(sfId, blockData,
                        "invalid or missing 'stored' count: " + printable(storedRaw)
                                + "; historical reserve unverified; no count rewrite");
                if (repair) {
                    report.detail(locationPrefix(sfId, blockData)
                            + "saved count left untouched; restore from a verified backup only");
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
                        Integer safeSize = BarrelDoctorSafety.safeCapacityRepair(stored, configuredMaximum);
                        if (safeSize == null) {
                            // Unknown stored quantity or invalid maximum: no
                            // backing-data proof exists to justify any change.
                            report.detail(locationPrefix(sfId, blockData)
                                    + "Mini Barrel capacity left untouched: reserve or maximum unverified");
                        } else {
                            blockData.setData("max-size", Integer.toString(safeSize));
                            capacity = safeSize;
                            report.repaired++;
                            report.detail(locationPrefix(sfId, blockData)
                                    + "repaired Mini Barrel capacity to " + safeSize);
                        }
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

            // Diagnose the exact saved-reserve + physical-output + pending-Cargo
            // boundary for loaded barrels. A persisted reserve of zero does NOT
            // mean that the two output slots are empty. Reuse the production
            // identity predicate; no blind Slimefun-ID matching or item writes.
            if (stored != null && menu != null) {
                SlimefunItem item = SlimefunItem.getById(sfId);
                if (item instanceof Barrel barrel) {
                    BarrelContents<ItemStack> contents = barrel.inspectForDoctor(menu, stored);
                    if (!contents.safe()) {
                        // The missing template with a positive reserve was
                        // already reported above. Never double-count it as a
                        // separate fault or reconstruct any stored identity.
                        if (!(stored > 0 && emptyDisplay)) {
                            report.issue(sfId, blockData,
                                    "saved reserve/output identity unsafe: "
                                            + contents.rejection().explanation()
                                            + "; no quantity or item rewrite");
                        }
                    } else {
                        Long verifiedOutput = BarrelDoctorSafety.confirmedOutputWithZeroReserve(
                                stored, contents);
                        if (verifiedOutput != null) {
                            report.notePhysicalOutputOnly(sfId, blockData, verifiedOutput);
                        }
                    }

                    long pendingMismatch = barrel.incompatiblePendingInputCountForDoctor(menu);
                    if (pendingMismatch > 0) {
                        report.issue(sfId, blockData,
                                pendingMismatch + " Cargo input item(s) have a different strict item"
                                        + " identity from the stored template; inputs left untouched");
                    }
                }
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
        private long bufferedOnlyBarrels;
        private long bufferedOnlyItems;
        private final List<String> bufferedExamples = new ArrayList<>();
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

        private void notePhysicalOutputOnly(
                String sfId, SlimefunBlockData blockData, long count) {
            bufferedOnlyBarrels++;
            bufferedOnlyItems += count;
            if (bufferedExamples.size() < 4) {
                bufferedExamples.add(locationPrefix(sfId, blockData)
                        + "saved reserve=0, verified physical output slots=" + count
                        + "; this is buffered stock, not lost items");
            }
        }

        private FluffyDoctorReport freeze() {
            if (bufferedOnlyBarrels > 0) {
                detail("Output-only Fluffy Barrels: " + bufferedOnlyBarrels
                        + " loaded; " + bufferedOnlyItems
                        + " total item(s) in identity-verified physical output slots"
                        + " while saved reserve=0. No data was changed.");
                for (String example : bufferedExamples) {
                    detail(example);
                }
            }
            return new FluffyDoctorReport(scanned, issues, repaired, failures, details);
        }
    }
}
