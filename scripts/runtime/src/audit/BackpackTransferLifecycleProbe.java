package audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.BlockDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.ProfileDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.LocationUtils;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.virtual.VirtualItemHandler;
import io.github.thebusybiscuit.slimefun4.api.items.virtual.VirtualItemHandler.ItemResult;
import io.github.thebusybiscuit.slimefun4.api.items.virtual.VirtualItemHandler.ConsumeContext;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNet;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
import io.ncbpfluffybear.fluffymachines.diagnostics.FluffyDoctor;
import io.ncbpfluffybear.fluffymachines.diagnostics.FluffyDoctorReport;
import io.ncbpfluffybear.fluffymachines.items.tools.WateringCan;
import io.ncbpfluffybear.fluffymachines.utils.BarrelDisplayManager;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import io.ncbpfluffybear.fluffymachines.machines.BackpackLoader;
import io.ncbpfluffybear.fluffymachines.machines.BackpackUnloader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import java.lang.reflect.Method;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Exercises the supplied production addon; it contains no replacement transfer implementation. */
public final class BackpackTransferLifecycleProbe extends JavaPlugin {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[] LOADER_INPUTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] UNLOADER_OUTPUTS = {28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final List<Map<String, Object>> restartFixtures = new ArrayList<>();
    private final List<Gate> gates = new ArrayList<>();
    private final Set<String> firstTickLocations = new HashSet<>();
    private BlockDataController blocks;
    private ProfileDataController profiles;
    private World world;
    private int fixtureNumber;
    private boolean running;

    @Override
    public void onEnable() {
        getLogger().info("Native helper ready; no checks run until its console command is issued.");
    }

    @Override
    public void onDisable() {
        for (Gate gate : gates) gate.release.complete(null);
        for (World loadedWorld : Bukkit.getWorlds()) loadedWorld.removePluginChunkTickets(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1 || running || !(args[0].equals("run") || args[0].equals("read"))) {
            sender.sendMessage("Usage: fluffybackpackprobe run|read (one active phase)");
            return true;
        }
        running = true;
        blocks = Slimefun.getDatabaseManager().getBlockDataController();
        profiles = Slimefun.getDatabaseManager().getProfileDataController();
        world = Bukkit.getWorlds().getFirst();
        // The helper schedules each real production tick explicitly; ambient ticks
        // must not consume additional items while the callback queue is controlled.
        Slimefun.getTickerTask().pauseItemTicker("BACKPACK_LOADER");
        Slimefun.getTickerTask().pauseItemTicker("BACKPACK_UNLOADER");
        // Native barrel visuals are ticked manually too; ambient ticks would invalidate
        // the exact 20-tick deadline observations below.
        Slimefun.getTickerTask().pauseItemTicker("SMALL_FLUFFY_BARREL");
        CompletableFuture<Void> phase = args[0].equals("run") ? runChecks() : readChecks();
        phase.whenComplete((ignored, failure) -> owner(() -> {
            for (Gate gate : gates) gate.release.complete(null);
            finish(args[0], failure);
            return null;
        }));
        return true;
    }

    private CompletableFuture<Void> runChecks() {
        List<Scenario> scenarios = List.of(
                new Scenario("dolly-native-locked-chest-state", this::dollyNativeLockedChestState),
                new Scenario("foundry-native-bucket-metadata", this::foundryNativeBucketMetadata),
                new Scenario("paxel-native-item-metadata", this::paxelNativeItemMetadata),
                new Scenario("barrel-display-and-quantities", this::barrelDisplayLifecycle),
                new Scenario("barrel-native-input-consumption", this::barrelNativeInputConsumption),
                new Scenario("barrel-native-virtual-replacement", this::barrelNativeVirtualReplacement),
                new Scenario("barrel-quick-withdraw-two-outputs", this::barrelQuickWithdrawTwoOutputs),
                new Scenario("barrel-native-unsafe-state-reason", this::barrelNativeUnsafeStateReason),
                new Scenario("barrel-doctor-invalid-count-preserved", this::barrelDoctorInvalidCountPreserved),
                new Scenario("watering-can-native-charge-parity", this::wateringCanNativeChargeParity),
                new Scenario("watering-can-real-paper-sapling", this::wateringCanRealPaperSapling),
                new Scenario("barrel-slimefun-cargo-metadata", this::barrelSlimefunCargoMetadata),
                new Scenario("barrel-canonical-presentation-self-heal", this::barrelCanonicalPresentationSelfHeal),
                new Scenario("barrel-historical-item-nonmerge", this::barrelHistoricalItemNonmerge),
                new Scenario("barrel-cargo-restart-write", this::barrelRestartWrite),
                new Scenario("barrel-actual-cargo-network", this::barrelActualCargoNetwork),
                new Scenario("normal-loader", () -> normal(true)),
                new Scenario("normal-unloader", () -> normal(false)),
                new Scenario("legacy-lore-loader", () -> normal(true, true)),
                new Scenario("legacy-lore-unloader", () -> normal(false, true)),
                new Scenario("loader-bound-backpack-routing", () -> routing(true, "bound")),
                new Scenario("loader-unbound-backpack-routing", () -> routing(true, "unbound")),
                new Scenario("loader-full-backpack-routing", () -> routing(true, "full")),
                new Scenario("unloader-unbound-backpack-routing", () -> routing(false, "unbound")),
                new Scenario("unloader-empty-backpack-routing", () -> routing(false, "empty")),
                new Scenario("loader-backpack-swap", () -> backpackSwap(true)),
                new Scenario("unloader-backpack-swap", () -> backpackSwap(false)),
                new Scenario("loader-input-shulker-swap", () -> forbiddenInput(false)),
                new Scenario("loader-input-backpack-swap", () -> forbiddenInput(true)),
                new Scenario("unloader-energy-budget", this::energyBudget),
                new Scenario("unloader-empty-output-filled", () -> outputFilled(true)),
                new Scenario("unloader-nonempty-output-filled", () -> outputFilled(false)),
                new Scenario("invalidated-backpack", this::invalidatedBackpack),
                new Scenario("removed-machine", this::removedMachine),
                new Scenario("loader-cache-eviction", () -> evictedMachine(true)),
                new Scenario("unloader-cache-eviction", () -> evictedMachine(false)),
                new Scenario("loader-menu-replacement", () -> evictedMachine(true, true)),
                new Scenario("unloader-menu-replacement", () -> evictedMachine(false, true)));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Scenario scenario : scenarios) {
            chain = chain.thenCompose(ignored -> ownerCompose(scenario.body))
                    .thenCompose(check -> owner(() -> {
                        Map<String, Object> row = check.result(scenario.name);
                        results.add(row);
                        getLogger().info("FLUFFY_NATIVE_CASE " + scenario.name + " " + row.get("passed"));
                        return (Void) null;
                    }));
        }
        return chain.thenCompose(ignored -> owner(() -> {
            writeJson(getDataFolder().toPath().resolve("restart-fixtures.json"), restartFixtures);
            return (Void) null;
        }));
    }

    /**
     * Native, non-player Dolly state boundary: the existing production PDC writer
     * must carry a real locked chest's lock and custom name into the Dolly item;
     * the existing production restorer must then apply the exact lock and name
     * to a native chest. This deliberately does NOT impersonate a player's
     * Dolly pickup/place handler or move a historical owner's inventory.
     * A second Paper server process checks vanilla block-state persistence.
     */
    private CompletableFuture<Check> dollyNativeLockedChestState() {
        return owner(() -> {
            Check check = new Check();
            SlimefunItem registered = SlimefunItem.getById("DOLLY");
            if (registered == null) {
                throw new IllegalStateException("Registered production Dolly is missing");
            }
            try {
                Class<?> dollyClass = Class.forName(
                        "io.ncbpfluffybear.fluffymachines.items.tools.Dolly");
                check.that(dollyClass.isInstance(registered),
                        "Native registered DOLLY uses the production Dolly implementation");
                var save = dollyClass.getDeclaredMethod("setStoredChestData",
                        ItemStack.class, Material.class, Material.class, String.class, String.class);
                var restore = dollyClass.getDeclaredMethod("applyStoredChestData",
                        ItemStack.class, Block.class, Block.class);
                save.setAccessible(true);
                restore.setAccessible(true);

                fixtureNumber++;
                Location sourceLocation = new Location(world, 2176 + fixtureNumber * 128, 64, 2048);
                Location restoredLocation = sourceLocation.clone().add(3, 0, 0);
                holdChunk(sourceLocation);
                Block source = sourceLocation.getBlock();
                source.setType(Material.TRAPPED_CHEST, false);
                org.bukkit.block.Chest sourceState = (org.bukkit.block.Chest) source.getState();
                String lock = "dolly-native-owner-lock";
                String name = "Native Dolly Locked Chest";
                writeNativeChestLock(sourceState, lock);
                sourceState.customName(Component.text(name, NamedTextColor.AQUA));
                check.that(sourceState.update(true, false), "Native source chest lock/name committed");
                ItemStack originalContent = rich(Material.DIAMOND, 19, "dolly-lock-content");
                ((org.bukkit.block.Chest) source.getState()).getBlockInventory()
                        .setItem(0, originalContent.clone());

                ItemStack dolly = registered.getItem().clone();
                var savedSource = (org.bukkit.block.Chest) source.getState();
                String originalLock = readNativeChestLock(savedSource);
                check.that(lock.equals(originalLock) && savedSource.isLocked(),
                        "Native Paper source chest retains the exact lock");
                save.invoke(registered, dolly, source.getType(), null, originalLock,
                        Utils.legacyString(savedSource.customName()));
                var pdc = dolly.getItemMeta().getPersistentDataContainer();
                var addon = Bukkit.getPluginManager().getPlugin("FluffyMachines");
                if (addon == null) throw new IllegalStateException("Production FluffyMachines unavailable");
                check.that(lock.equals(pdc.get(new NamespacedKey(addon, "dolly_chest_lock"),
                                PersistentDataType.STRING))
                        && Material.TRAPPED_CHEST.name().equals(pdc.get(
                                new NamespacedKey(addon, "dolly_chest_type"), PersistentDataType.STRING))
                        && pdc.get(new NamespacedKey(addon, "dolly_chest_name"),
                                PersistentDataType.STRING) != null,
                        "Actual Dolly state writer preserves typed chest type, lock and custom name PDC");
                check.item(dolly, nativeCopy(dolly),
                        "Dolly lock and owner-independent metadata survive native item serialization");
                check.item(originalContent, ((org.bukkit.block.Chest) source.getState())
                        .getBlockInventory().getItem(0),
                        "Reading chest and writing Dolly PDC does not consume the source payload");

                Block restored = restoredLocation.getBlock();
                restored.setType(Material.TRAPPED_CHEST, false);
                ItemStack dollySnapshot = nativeCopy(dolly);
                restore.invoke(registered, dolly, restored, null);
                var restoredState = (org.bukkit.block.Chest) restored.getState();
                check.that(lock.equals(readNativeChestLock(restoredState))
                        && restoredState.isLocked(),
                        "Actual Dolly state restorer applies exact lock to native Paper chest");
                check.that(restoredState.customName() != null
                        && name.equals(PlainTextComponentSerializer.plainText()
                                .serialize(restoredState.customName())),
                        "Actual Dolly state restorer applies original plain custom chest name");
                check.item(dollySnapshot, dolly,
                        "Dolly state restore leaves serialized PDC and item identity untouched");

                // The production storage transaction is not tested here. Seed a
                // separate native chest with an independent cloned fixture
                // solely to prove that its contents and lock survive restart.
                restoredState.getBlockInventory().setItem(0, originalContent.clone());
                check.item(originalContent, ((org.bukkit.block.Chest) restored.getState())
                        .getBlockInventory().getItem(0), "Native restored chest stores 19 items exactly");
                Map<String, Object> fixture = new LinkedHashMap<>();
                fixture.put("x", restored.getX());
                fixture.put("y", restored.getY());
                fixture.put("z", restored.getZ());
                fixture.put("type", restored.getType().name());
                fixture.put("lock", lock);
                fixture.put("name", name);
                fixture.put("dolly", Base64.getEncoder().encodeToString(dolly.serializeAsBytes()));
                fixture.put("contents", encodeItems(
                        ((org.bukkit.block.Chest) restored.getState()).getBlockInventory()
                                .getStorageContents()));
                writeJson(getDataFolder().toPath().resolve("dolly-lock-restart-fixture.json"), fixture);
                check.note("Production Dolly lock/name PDC writer + restorer on real chest; "
                        + "not a player-triggered pickup/place or crash-atomic inventory transfer.");
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Production Dolly chest state bridge unavailable", ex);
            }
            return check;
        });
    }

    /** Reflect legacy Paper lock methods without adding compiler deprecation debt. */
    private static void writeNativeChestLock(org.bukkit.block.Chest chest, String lock) {
        try {
            org.bukkit.block.Lockable.class.getMethod("setLock", String.class).invoke(chest, lock);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Paper chest lock write API unavailable", ex);
        }
    }

    private static String readNativeChestLock(org.bukkit.block.Chest chest) {
        try {
            return (String) org.bukkit.block.Lockable.class.getMethod("getLock").invoke(chest);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Paper chest lock read API unavailable", ex);
        }
    }

    /**
     * Exercises the real registered Fluffy Barrel ticker and Bukkit ItemDisplay entities.
     * Inputs and output slots remain physical inventories; only the presentation cache is delayed.
     */
    /**
     * Native Cargo-facing insertion of two different registered Slimefun items.
     * Cosmetic Adventure component layouts may vary; lore and Slimefun identity may not.
     */
    /**
     * Paper's documented replacement for deprecated ItemStack.setType is
     * withType + setting the returned stack in its destination inventory.
     * This standalone fixture exercises both the actual old Foundry helper
     * and the replacement's native ItemMeta/PDC invariants before any gameplay
     * migration. A separate future fixture must test PlayerInventory writes.
     */
    private CompletableFuture<Check> foundryNativeBucketMetadata() {
        return owner(() -> {
            Check check = new Check();
            ItemStack held = rich(Material.LAVA_BUCKET, 1, "foundry-lava");
            var meta = held.getItemMeta();
            meta.displayName(Component.text("Foundry lava", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Private owner metadata", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)));
            var ownerKey = new NamespacedKey(this, "foundry_owner");
            var markerKey = new NamespacedKey(this, "marker");
            var wideKey = new NamespacedKey(this, "wide");
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, "owner-A");
            held.setItemMeta(meta);
            Slimefun.getItemDataService().setItemData(held, "REINFORCED_PLATE");
            ItemStack before = nativeCopy(held);
            ItemStack candidate = held.withType(Material.BUCKET);
            check.that(candidate != held,
                    "Paper withType returns a new stack; no in-place aliasing");
            check.that(candidate.getType() == Material.BUCKET && candidate.getAmount() == 1,
                    "Modern bucket transition retains type and original amount");
            check.item(before, held, "Modern withType leaves the source lava bucket untouched");
            var originalMeta = held.getItemMeta();
            var candidateMeta = candidate.getItemMeta();
            check.that(java.util.Objects.equals(originalMeta.displayName(), candidateMeta.displayName())
                    && java.util.Objects.equals(originalMeta.lore(), candidateMeta.lore()),
                    "Modern bucket conversion retains exact rich Adventure name and ordered lore");
            check.that("owner-A".equals(candidateMeta.getPersistentDataContainer()
                    .get(ownerKey, PersistentDataType.STRING))
                    && "foundry-lava".equals(candidateMeta.getPersistentDataContainer()
                    .get(markerKey, PersistentDataType.STRING))
                    && Long.valueOf(9_007_199_254_741_111L).equals(candidateMeta
                    .getPersistentDataContainer().get(wideKey, PersistentDataType.LONG)),
                    "Modern bucket conversion retains typed owner, string and long PDC");
            check.that("REINFORCED_PLATE".equals(Slimefun.getItemDataService()
                    .getItemData(candidateMeta).orElse(null)),
                    "Modern bucket conversion retains the exact registered Slimefun PDC ID");
            check.item(candidate, nativeCopy(candidate),
                    "Converted item metadata survives real native byte serialization");
            try {
                Class<?> foundry = Class.forName(
                    "io.ncbpfluffybear.fluffymachines.multiblocks.Foundry");
                Method consume = foundry.getDeclaredMethod("consumeLavaBucketInPlace", ItemStack.class);
                consume.setAccessible(true);
                ItemStack actual = nativeCopy(before);
                check.that(Boolean.TRUE.equals(consume.invoke(null, actual))
                        && actual.getType() == Material.BUCKET && actual.getAmount() == 1,
                        "Registered Foundry legacy bridge changes the original lava-bucket stack");
                check.that(Boolean.FALSE.equals(consume.invoke(null, actual)),
                        "Legacy bridge does not consume a bucket twice");
                check.that(actual.getType() == Material.BUCKET && actual.getAmount() == 1,
                        "Legacy bridge retains its in-place material and quantity after repeat");
                // Baseline the REAL deprecated mutation as well as the candidate.
                // An identity-safe replacement must not claim parity without
                // comparing the current in-place behavior on native Paper.
                var actualMeta = actual.getItemMeta();
                check.that(java.util.Objects.equals(before.getItemMeta().displayName(), actualMeta.displayName())
                        && java.util.Objects.equals(before.getItemMeta().lore(), actualMeta.lore()),
                        "Existing Foundry mutation retains exact Adventure name and lore");
                check.that("owner-A".equals(actualMeta.getPersistentDataContainer()
                        .get(ownerKey, PersistentDataType.STRING))
                        && "foundry-lava".equals(actualMeta.getPersistentDataContainer()
                        .get(markerKey, PersistentDataType.STRING))
                        && Long.valueOf(9_007_199_254_741_111L).equals(actualMeta
                        .getPersistentDataContainer().get(wideKey, PersistentDataType.LONG)),
                        "Existing Foundry mutation retains typed owner, string and long PDC");
                check.that("REINFORCED_PLATE".equals(Slimefun.getItemDataService()
                        .getItemData(actualMeta).orElse(null)),
                        "Existing Foundry mutation retains the exact Slimefun PDC ID");
                check.item(actual, nativeCopy(actual),
                        "Existing Foundry bucket metadata survives native serialization");
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Production Foundry bucket helper unavailable", ex);
            }
            check.note("This tests detached Paper stacks, not actual PlayerInventory writes or "
                    + "atomicity if a later Foundry animation fails.");
            return check;
        });
    }

    /**
     * Baseline the exact production Paxel in-place tool mutation against
     * Paper's detached withType candidate on both supported tool tiers.
     * The fixture never changes a real player's inventory.
     */
    private CompletableFuture<Check> paxelNativeItemMetadata() {
        return owner(() -> {
            Check check = new Check();
            try {
                Class<?> paxel = Class.forName(
                        "io.ncbpfluffybear.fluffymachines.items.tools.Paxel");
                Method mine = paxel.getDeclaredMethod(
                        "materialForBlock", Material.class, boolean.class, boolean.class);
                Method hit = paxel.getDeclaredMethod("materialForHit", Material.class);
                Method change = paxel.getDeclaredMethod(
                        "changeToolTypeInPlace", ItemStack.class, Material.class);
                mine.setAccessible(true);
                hit.setAccessible(true);
                change.setAccessible(true);

                for (Material original : new Material[] {
                        Material.DIAMOND_PICKAXE, Material.NETHERITE_PICKAXE
                }) {
                    for (boolean combat : new boolean[] {false, true}) {
                        String label = original + (combat ? " hit" : " mine");
                        ItemStack held = rich(original, 1, "paxel-" + label);
                        var meta = held.getItemMeta();
                        meta.displayName(Component.text("Paxel " + label, NamedTextColor.AQUA)
                                .decoration(TextDecoration.ITALIC, false));
                        meta.lore(List.of(Component.text("Preserve owner data", NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false)));
                        var ownerKey = new NamespacedKey(this, "paxel_owner");
                        var markerKey = new NamespacedKey(this, "marker");
                        var wideKey = new NamespacedKey(this, "wide");
                        meta.getPersistentDataContainer().set(
                                ownerKey, PersistentDataType.STRING, "player-owned");
                        if (meta instanceof org.bukkit.inventory.meta.Damageable damageable) {
                            damageable.setDamage(17);
                        }
                        held.setItemMeta(meta);
                        held.addUnsafeEnchantment(
                                org.bukkit.enchantments.Enchantment.EFFICIENCY, 2);
                        Slimefun.getItemDataService().setItemData(held, "PAXEL");
                        ItemStack before = nativeCopy(held);
                        Material target = combat
                                ? (Material) hit.invoke(null, original)
                                : (Material) mine.invoke(null, original, false, true);

                        // A modern replacement returns a detached object; it
                        // cannot silently be substituted for the existing alias.
                        ItemStack candidate = held.withType(target);
                        check.that(candidate != held, label + ": withType is detached");
                        check.item(before, held, label + ": candidate leaves original untouched");

                        ItemStack actual = nativeCopy(before);
                        ItemStack originalAlias = actual;
                        change.invoke(null, actual, target);
                        check.that(actual == originalAlias,
                                label + ": production mutation keeps original reference");
                        check.that(actual.getType() == target && actual.getAmount() == 1,
                                label + ": production mutation preserves quantity and tool selection");
                        var actualMeta = actual.getItemMeta();
                        var beforeMeta = before.getItemMeta();
                        check.that(java.util.Objects.equals(
                                    beforeMeta.displayName(), actualMeta.displayName())
                                && java.util.Objects.equals(beforeMeta.lore(), actualMeta.lore()),
                                label + ": production mutation retains Adventure name/lore");
                        check.that(actualMeta.getPersistentDataContainer()
                                    .get(ownerKey, PersistentDataType.STRING).equals("player-owned")
                                && ("paxel-" + label).equals(actualMeta.getPersistentDataContainer()
                                    .get(markerKey, PersistentDataType.STRING))
                                && Long.valueOf(9_007_199_254_741_111L).equals(
                                    actualMeta.getPersistentDataContainer()
                                            .get(wideKey, PersistentDataType.LONG)),
                                label + ": production mutation retains typed PDC");
                        check.that("PAXEL".equals(Slimefun.getItemDataService()
                                .getItemData(actualMeta).orElse(null)),
                                label + ": production mutation retains Slimefun ID");
                        check.that(actual.getEnchantments().equals(before.getEnchantments())
                                && actualMeta instanceof org.bukkit.inventory.meta.Damageable
                                && ((org.bukkit.inventory.meta.Damageable) actualMeta).getDamage() == 17,
                                label + ": production mutation retains enchants and damage");
                        check.item(actual, nativeCopy(actual),
                                label + ": production metadata survives native item serialization");
                        check.that(candidate.getType() == actual.getType()
                                && candidate.getAmount() == actual.getAmount(),
                                label + ": detached candidate preserves tool type and amount");
                        check.that(java.util.Objects.equals(candidate.getItemMeta().displayName(),
                                    actualMeta.displayName())
                                && java.util.Objects.equals(candidate.getItemMeta().lore(),
                                    actualMeta.lore())
                                && candidate.getItemMeta().getPersistentDataContainer()
                                    .equals(actualMeta.getPersistentDataContainer()),
                                label + ": detached candidate retains same Adventure and PDC");
                    }
                }
                check.note("Detached item parity and current in-place behavior only; "
                        + "not a PlayerInventory replacement or live item upgrade.");
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Production Paxel conversion unavailable", ex);
            }
            return check;
        });
    }

    /** Run the actual production CargoNetworkTask with real wall-head nodes and a chest source. */
    private void runNativeCargoNetwork(CargoNet network, Location input, Location output, boolean withOutput) {
        try {
            Class<?> task = Class.forName("io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNetworkTask");
            var constructor = task.getDeclaredConstructor(CargoNet.class, Map.class, Map.class);
            constructor.setAccessible(true);
            Map<Integer, List<Location>> destinations = withOutput ? Map.of(0, List.of(output)) : Map.of();
            Runnable work = (Runnable) constructor.newInstance(network, Map.of(input, 0), destinations);
            work.run();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Native production Cargo network task could not execute", failure);
        }
    }

    private Block nativeCargoNode(Location location, String id, BlockFace facing) {
        SlimefunItem node = SlimefunItem.getById(id);
        if (node == null) throw new IllegalStateException("Missing registered node " + id);
        Block block = location.getBlock();
        block.setType(Material.PLAYER_WALL_HEAD, false);
        Directional data = (Directional) block.getBlockData();
        data.setFacing(facing);
        block.setBlockData(data, false);
        SlimefunBlockData sfData = blocks.createBlock(location, node.getId());
        if (!sfData.isDataLoaded() || sfData.getBlockMenu() == null)
            throw new IllegalStateException("Cargo node is not initialized " + id);
        StorageCacheUtils.setData(location, "frequency", "0");
        return block;
    }

    /** Prove one input node does not deliver into a barrel; a network output node does. */
    private CompletableFuture<Check> barrelActualCargoNetwork() {
        return owner(() -> {
            Check check = new Check();
            fixtureNumber++;
            Location base = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
            Chunk chunk = holdChunk(base);
            blocks.getChunkData(chunk);
            SlimefunItem barrelItem = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem materialItem = SlimefunItem.getById("REINFORCED_PLATE");
            SlimefunItem managerItem = SlimefunItem.getById("CARGO_MANAGER");
            if (!(barrelItem instanceof Barrel barrel) || materialItem == null || managerItem == null)
                throw new IllegalStateException("Native Cargo network registration is incomplete");
            Block destination = base.getBlock();
            destination.setType(barrelItem.getItem().getType(), false);
            SlimefunBlockData barrelData = blocks.createBlock(base, barrelItem.getId());
            BlockMenu menu = barrelData.getBlockMenu();
            if (menu == null || !barrelData.isDataLoaded())
                throw new IllegalStateException("Native Cargo destination barrel menu missing");
            barrel.setStored(destination, 0);

            Location chestLocation = base.clone().add(2, 0, 0);
            Block source = chestLocation.getBlock();
            source.setType(Material.CHEST, false);
            org.bukkit.block.Chest chest = (org.bukkit.block.Chest) source.getState(false);
            var inventory = chest.getBlockInventory();
            ItemStack cargo = new ItemStack(materialItem.getItem().getType(), 64);
            cargo.setItemMeta(materialItem.getItem().getItemMeta());
            inventory.setItem(0, cargo.clone());

            Location inputLocation = base.clone().add(1, 0, 0);
            nativeCargoNode(inputLocation, "CARGO_NODE_INPUT", BlockFace.WEST);
            Location outputLocation = base.clone().add(0, 0, 1);
            nativeCargoNode(outputLocation, "CARGO_NODE_OUTPUT", BlockFace.SOUTH);
            Location managerLocation = base.clone().add(1, 0, 1);
            Block regulator = managerLocation.getBlock();
            regulator.setType(managerItem.getItem().getType(), false);
            blocks.createBlock(managerLocation, managerItem.getId());
            CargoNet cargoNet = CargoNet.getNetworkFromLocationOrCreate(managerLocation);

            runNativeCargoNetwork(cargoNet, inputLocation, outputLocation, false);
            check.that(inventory.getItem(0) != null && inventory.getItem(0).getAmount() == 64,
                    "a Cargo INPUT node without any output destination returns items to its source");
            check.that(barrel.visibleContents(destination).safe()
                    && barrel.visibleContents(destination).amount() == 0L,
                    "source-only Cargo node cannot magically deposit into the barrel");

            runNativeCargoNetwork(cargoNet, inputLocation, outputLocation, true);
            check.that(inventory.getItem(0) == null,
                    "production CargoNetworkTask withdraws 64 items from source chest");
            check.that(menu.getItemInSlot(19) != null && menu.getItemInSlot(19).getAmount() == 64,
                    "registered Cargo OUTPUT node physically deposits into barrel input slot");
            barrelItem.getBlockTicker().tick(destination, barrelItem, barrelData);
            check.that(barrel.visibleContents(destination).safe()
                    && barrel.visibleContents(destination).amount() == 64L,
                    "barrel stores all 64 routed Slimefun items without loss or duplication");

            ItemStack another = cargo.clone();
            another.setAmount(31);
            inventory.setItem(0, another);
            runNativeCargoNetwork(cargoNet, inputLocation, outputLocation, true);
            barrelItem.getBlockTicker().tick(destination, barrelItem, barrelData);
            check.that(inventory.getItem(0) == null && barrel.visibleContents(destination).safe()
                    && barrel.visibleContents(destination).amount() == 95L,
                    "second real Cargo network route preserves all 95 cumulative items");
            check.that("REINFORCED_PLATE".equals(Slimefun.getItemDataService()
                    .getItemData(menu.getItemInSlot(24)).orElse(null)),
                    "routed Slimefun item identity survives actual network handling");
            ItemStack status = menu.getItemInSlot(22);
            check.that(status != null && status.getType() == Material.LIME_STAINED_GLASS_PANE
                    && status.hasItemMeta()
                    && PlainTextComponentSerializer.plainText()
                            .serialize(status.getItemMeta().displayName()).contains("95 /"),
                    "barrel GUI accurately reports 95 compatible Cargo items without a false mismatch");
            check.that(barrel.getStored(destination) == 0
                            && menu.getItemInSlot(24) != null && menu.getItemInSlot(24).getAmount() == 64
                            && menu.getItemInSlot(25) != null && menu.getItemInSlot(25).getAmount() == 31,
                    "real Cargo delivery leaves 95 buffered items and a legitimate zero saved reserve");
            check.that(menu.getItemInSlot(19) == null && menu.getItemInSlot(20) == null,
                    "production barrel ticker absorbs both incoming Cargo deliveries");
            byte[] firstCargoBytes = menu.getItemInSlot(24).serializeAsBytes();
            byte[] secondCargoBytes = menu.getItemInSlot(25).serializeAsBytes();
            FluffyDoctorReport readOnlyDoctor = FluffyDoctor.run(false);
            check.that(readOnlyDoctor.getDetails().stream().anyMatch(detail ->
                    detail.startsWith("Output-only Fluffy Barrels:")),
                    "Read-only Fluffy Doctor distinguishes zero saved reserve from buffered Cargo stock");
            check.that(barrel.getStored(destination) == 0
                    && Arrays.equals(firstCargoBytes, menu.getItemInSlot(24).serializeAsBytes())
                    && Arrays.equals(secondCargoBytes, menu.getItemInSlot(25).serializeAsBytes()),
                    "Doctor output-only receipt never changes actual saved quantity or output bytes");
            writeJson(getDataFolder().toPath().resolve("barrel-real-cargo-restart-fixture.json"),
                    Map.of("x", base.getBlockX(), "y", base.getBlockY(), "z", base.getBlockZ(),
                            "id", barrelItem.getId(), "item", materialItem.getId(), "total", 95));
            return check;
        });
    }

    /** Create a real Slimefun barrel persisted across a full Paper process restart. */
    private CompletableFuture<Check> barrelRestartWrite() {
        return owner(() -> {
            Check check = new Check();
            fixtureNumber++;
            Location loc = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
            Chunk chunk = holdChunk(loc);
            blocks.getChunkData(chunk);
            SlimefunItem sfBarrel = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem sfMaterial = SlimefunItem.getById("REINFORCED_PLATE");
            if (!(sfBarrel instanceof Barrel barrel) || sfMaterial == null)
                throw new IllegalStateException("Native restart barrel or Slimefun input is missing");
            Block block = loc.getBlock();
            block.setType(sfBarrel.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(loc, sfBarrel.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded())
                throw new IllegalStateException("Native restart barrel menu is unavailable");
            barrel.setStored(block, 0);
            ItemStack cargo = sfMaterial.getItem().clone();
            cargo.setAmount(17);
            int[] inputs = menu.getPreset().getSlotsAccessedByItemTransport(menu, ItemTransportFlow.INSERT, cargo);
            check.that(menu.pushItem(cargo.clone(), inputs) == null, "first 17 Slimefun cargo items inserted");
            sfBarrel.getBlockTicker().tick(block, sfBarrel, data);
            cargo.setAmount(120);
            check.that(menu.pushItem(cargo, inputs) == null, "second 120 Slimefun cargo items inserted");
            sfBarrel.getBlockTicker().tick(block, sfBarrel, data);
            check.that(barrel.getStored(block) == 9, "saved reserve is 9 before restart");
            check.that(barrel.visibleContents(block).safe() && barrel.visibleContents(block).amount() == 137L,
                    "137 total Slimefun items are intact before restart");
            check.that(menu.getItemInSlot(24) != null && menu.getItemInSlot(24).getAmount() == 64
                    && menu.getItemInSlot(25) != null && menu.getItemInSlot(25).getAmount() == 64,
                    "both native output slots are full before restart");
            writeJson(getDataFolder().toPath().resolve("barrel-restart-fixture.json"),
                    Map.of("x", loc.getBlockX(), "y", loc.getBlockY(), "z", loc.getBlockZ(),
                            "id", sfBarrel.getId(), "item", sfMaterial.getId(), "stored", 9, "total", 137));
            return check;
        });
    }

    /** Invoke the actual CargoNetworkTask -> CargoUtils.insert path on a real registered output node. */
    private ItemStack insertViaCoreCargo(CargoNet network, Block outputNode, Block target, ItemStack stack) {
        try {
            Class<?> util = Class.forName("io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoUtils");
            Class<?> networkBase = Class.forName("io.github.thebusybiscuit.slimefun4.core.networks.cargo.AbstractItemNetwork");
            Method insert = util.getDeclaredMethod("insert", networkBase, Map.class, Block.class, Block.class,
                    boolean.class, ItemStack.class, ItemStackWrapper.class);
            insert.setAccessible(true);
            return (ItemStack) insert.invoke(null, network, new java.util.HashMap<Location, org.bukkit.inventory.Inventory>(),
                    outputNode, target, false, stack, ItemStackWrapper.wrap(stack));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Production CargoUtils.insert could not run in native fixture", failure);
        }
    }

    private CompletableFuture<Check> barrelSlimefunCargoMetadata() {
        return owner(() -> {
            Check check = new Check();
            SlimefunItem barrelItem = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            if (!(barrelItem instanceof Barrel barrel)) {
                throw new IllegalStateException("Registered Fluffy Barrel missing");
            }
            for (String id : List.of("REINFORCED_PLATE", "STEEL_INGOT")) {
                SlimefunItem payloadItem = SlimefunItem.getById(id);
                if (payloadItem == null) throw new IllegalStateException("Native Slimefun item missing: " + id);
                fixtureNumber++;
                Location location = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
                Chunk chunk = holdChunk(location);
                blocks.getChunkData(chunk);
                Block block = location.getBlock();
                block.setType(barrelItem.getItem().getType(), false);
                SlimefunBlockData data = blocks.createBlock(location, barrelItem.getId());
                BlockMenu menu = data.getBlockMenu();
                if (menu == null || !data.isDataLoaded()) {
                    throw new IllegalStateException("Native Cargo barrel menu is unavailable: " + id);
                }
                barrel.setStored(block, 0);

                // This is the physical Cargo OUTPUT node: it inserts into the attached barrel.
                // Cargo INPUT nodes in Slimefun terminology withdraw from source inventories.
                SlimefunItem outputItem = SlimefunItem.getById("CARGO_NODE_OUTPUT");
                if (outputItem == null) throw new IllegalStateException("Cargo output node not registered");
                Location outputLocation = location.clone().add(0, 0, 1);
                Block outputNode = outputLocation.getBlock();
                outputNode.setType(Material.PLAYER_WALL_HEAD, false);
                Directional skullFacing = (Directional) outputNode.getBlockData();
                skullFacing.setFacing(BlockFace.SOUTH);
                outputNode.setBlockData(skullFacing, false);
                SlimefunBlockData outputData = blocks.createBlock(outputLocation, outputItem.getId());
                if (outputData.getBlockMenu() == null || !outputData.isDataLoaded())
                    throw new IllegalStateException("Native Cargo output node menu not loaded");
                CargoNet cargoNet = CargoNet.getNetworkFromLocationOrCreate(outputLocation.clone().add(0, 0, 2));

                ItemStack original = payloadItem.getItem().clone();
                original.setAmount(15);
                var meta = original.getItemMeta();
                meta.displayName(Component.text("Cargo regression " + id));
                meta.lore(List.of(Component.text("Original: " + id),
                        Component.text("Stored component version", NamedTextColor.GRAY)));
                original.setItemMeta(meta);
                Slimefun.getItemDataService().setItemData(original, id);
                ItemStack reencoded = original.clone();
                reencoded.setAmount(21);
                var reencodedMeta = reencoded.getItemMeta();
                reencodedMeta.displayName(Component.text("").append(Component.text("Cargo regression " + id)));
                reencodedMeta.lore(List.of(
                        Component.text("").append(Component.text("Original: " + id)),
                        Component.text("").append(Component.text("Stored component version", NamedTextColor.GRAY))));
                reencoded.setItemMeta(reencodedMeta);
                check.that(!original.getItemMeta().equals(reencoded.getItemMeta()),
                        id + " genuinely has differently structured Adventure names and lore");

                int[] slots = menu.getPreset().getSlotsAccessedByItemTransport(
                        menu, ItemTransportFlow.INSERT, original);
                check.that(Arrays.equals(slots, new int[] {19, 20}), id + " cargo slots are input-only");
                check.that(insertViaCoreCargo(cargoNet, outputNode, block, original.clone()) == null, id + " initial item admitted by Cargo API");
                barrelItem.getBlockTicker().tick(block, barrelItem, data);
                check.that(barrel.visibleContents(block).safe()
                        && barrel.visibleContents(block).amount() == 15L, id + " first cargo delivery counted");
                check.that(insertViaCoreCargo(cargoNet, outputNode, block, reencoded.clone()) == null,
                        id + " cosmetically reencoded item admitted by Cargo API");
                barrelItem.getBlockTicker().tick(block, barrelItem, data);
                check.that(barrel.visibleContents(block).safe()
                        && barrel.visibleContents(block).amount() == 36L,
                        id + " equivalent legacy lore formatting merges without losing stock");
                check.that(barrel.getStored(block) >= 0,
                        id + " persisted reserve remains nonnegative after formatting merge");

                ItemStack differentLore = reencoded.clone();
                differentLore.setAmount(3);
                var differentLoreMeta = differentLore.getItemMeta();
                differentLoreMeta.lore(List.of(Component.text("A meaningful metadata difference")));
                differentLore.setItemMeta(differentLoreMeta);
                insertViaCoreCargo(cargoNet, outputNode, block, differentLore);
                barrelItem.getBlockTicker().tick(block, barrelItem, data);
                check.that(barrel.visibleContents(block).safe()
                        && barrel.visibleContents(block).amount() == 36L,
                        id + " different lore is never folded into saved storage");
                check.that(menu.getItemInSlot(19) != null && menu.getItemInSlot(19).getAmount() == 3,
                        id + " rejected lore item remains physically in Cargo input slot");
                menu.replaceExistingItem(19, null);

                // Use a plain Bukkit ItemStack, not SlimefunItemStack.clone(): the latter
                // may reapply the registered subclass ID while the core stages a Cargo item.
                ItemStack differentIdentity = new ItemStack(reencoded.getType(), 5);
                differentIdentity.setItemMeta(reencoded.getItemMeta());
                Slimefun.getItemDataService().setItemData(differentIdentity, id + "_SPOOF");
                check.that((id + "_SPOOF").equals(
                        Slimefun.getItemDataService().getItemData(differentIdentity).orElse(null)),
                        id + " spoof fixture has an actually distinct PDC ID");
                ItemStack offeredRemainder = insertViaCoreCargo(cargoNet, outputNode, block, differentIdentity);
                ItemStack staged = menu.getItemInSlot(19);
                check.that((id + "_SPOOF").equals(
                        Slimefun.getItemDataService().getItemData(staged).orElse(null)),
                        id + " physically staged Cargo item retains its distinct PDC identity");
                check.note(id + " before tick: source-ID="
                        + Slimefun.getItemDataService().getItemData(differentIdentity).orElse("<missing>")
                        + " staged-ID=" + Slimefun.getItemDataService().getItemData(staged).orElse("<missing>")
                        + " staged=" + (staged == null ? 0 : staged.getAmount())
                        + " offer-remainder=" + (offeredRemainder == null ? 0 : offeredRemainder.getAmount())
                        + " stored=" + barrel.getStored(block) + " visible=" + barrel.visibleContents(block).amount());
                barrelItem.getBlockTicker().tick(block, barrelItem, data);
                ItemStack after = menu.getItemInSlot(19);
                check.note(id + " after tick: staged-ID="
                        + Slimefun.getItemDataService().getItemData(after).orElse("<missing>")
                        + " staged=" + (after == null ? 0 : after.getAmount())
                        + " stored=" + barrel.getStored(block)
                        + " visibleSafe=" + barrel.visibleContents(block).safe()
                        + " visible=" + barrel.visibleContents(block).amount());
                check.that(barrel.visibleContents(block).safe()
                        && barrel.visibleContents(block).amount() == 36L,
                        id + " different Slimefun ID is never merged into saved storage");
                check.that(menu.getItemInSlot(19) != null && menu.getItemInSlot(19).getAmount() == 5,
                        id + " rejected Slimefun ID remains physically in Cargo input slot");
            }
            return check;
        });
    }


    /**
     * Preserve two same-ID Slimefun stacks when the older item has genuinely
     * different Adventure bold formatting. Unlike a harmless empty wrapper,
     * the changed decoration is not permission to rewrite historical items.
     * This covers the real barrel ticker and physical Cargo-facing slots.
     */
    /**
     * Native Paper BlockMenu diagnostic regression. Two different registered
     * items occupy the physical outputs, while saved reserve remains zero and
     * display is empty. Refresh only the status; never infer a new item, move
     * physical output, or rewrite the saved reserve.
     */
    private CompletableFuture<Check> barrelNativeUnsafeStateReason() {
        return owner(() -> {
            Check check = new Check();
            SlimefunItem barrelItem = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem firstItem = SlimefunItem.getById("REINFORCED_PLATE");
            SlimefunItem secondItem = SlimefunItem.getById("CARBON");
            if (!(barrelItem instanceof Barrel barrel) || firstItem == null || secondItem == null) {
                throw new IllegalStateException("Missing registered native barrel diagnostic fixtures");
            }
            fixtureNumber++;
            Location location = new Location(world, 2048 + fixtureNumber * 128, 64, 1816);
            holdChunk(location);
            blocks.getChunkData(location.getChunk());
            Block block = location.getBlock();
            block.setType(barrelItem.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(location, barrelItem.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded()) {
                throw new IllegalStateException("Native barrel has no loaded BlockMenu");
            }
            barrel.setStored(block, 0);
            // In the disposable data controller, createBlock does not open the
            // menu or call the preset's newInstance. A real opened barrel always
            // has a display placeholder; without it updateMenu intentionally
            // returns before writing any status pane.
            menu.replaceExistingItem(31, new ItemStack(Material.BARRIER));
            ItemStack first = firstItem.getItem().clone();
            ItemStack second = secondItem.getItem().clone();
            first.setAmount(37);
            second.setAmount(12);
            check.that(!first.isSimilar(second), "Two registered real Paper items have distinct identities");
            menu.replaceExistingItem(24, first.clone());
            menu.replaceExistingItem(25, second.clone());
            check.that(!barrel.visibleContents(block).safe()
                    && barrel.visibleContents(block).rejection()
                        == io.ncbpfluffybear.fluffymachines.items.BarrelContents.Rejection.OUTPUT_IDENTITY_MISMATCH,
                    "Native physical output mismatch has the exact read-only rejection classification");
            ItemStack beforeFirst = nativeCopy(menu.getItemInSlot(24));
            ItemStack beforeSecond = nativeCopy(menu.getItemInSlot(25));
            barrel.updateMenu(block, menu, false, barrel.getCapacity(block));
            ItemStack status = menu.getItemInSlot(22);
            List<Component> lore = status == null || !status.hasItemMeta()
                    ? null : status.getItemMeta().lore();
            check.that(status != null && status.getType() == Material.RED_STAINED_GLASS_PANE
                    && lore != null && lore.stream().anyMatch(line ->
                        PlainTextComponentSerializer.plainText().serialize(line)
                                .contains("Output item identity/metadata mismatch")),
                    "Actual red status pane explains why two unsafe real output stacks cannot merge");
            barrelItem.getBlockTicker().tick(block, barrelItem, data);
            check.that(barrel.getStored(block) == 0
                    && beforeFirst.equals(menu.getItemInSlot(24))
                    && beforeSecond.equals(menu.getItemInSlot(25)),
                    "Reading/rejecting the unsafe state never creates a saved reserve or touches output items");
            check.note("Real Paper registered BlockMenu and error status; synthetic mismatched "
                    + "registered items only. Does not establish identity of historical AlbionMC stock.");
            return check;
        });
    }

    /**
     * Native fail-closed Doctor regression: a cosmetic empty display cannot
     * prove an unreadable historical reserve is empty. Invoke the actual
     * production Doctor repair on a real loaded Paper barrel and preserve its
     * typed PDC-bearing physical output before an independent JVM restart.
     */
    private CompletableFuture<Check> barrelDoctorInvalidCountPreserved() {
        return owner(() -> {
            Check check = new Check();
            SlimefunItem sfBarrel = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem sfMaterial = SlimefunItem.getById("REINFORCED_PLATE");
            if (!(sfBarrel instanceof Barrel barrel) || sfMaterial == null)
                throw new IllegalStateException("Native Doctor barrel fixtures are missing");
            fixtureNumber++;
            Location location = new Location(world, 2048 + fixtureNumber * 128, 64, 1424);
            holdChunk(location);
            blocks.getChunkData(location.getChunk());
            Block block = location.getBlock();
            block.setType(sfBarrel.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(location, sfBarrel.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded())
                throw new IllegalStateException("Native Doctor barrel has no loaded menu");

            ItemStack physical = sfMaterial.getItem().clone();
            physical.setAmount(19);
            var meta = physical.getItemMeta();
            var ownerKey = new NamespacedKey(this, "doctor_historical_owner");
            meta.getPersistentDataContainer().set(
                    ownerKey, PersistentDataType.STRING, "original-typed-owner");
            physical.setItemMeta(meta);
            menu.replaceExistingItem(31, new ItemStack(Material.BARRIER));
            menu.replaceExistingItem(24, physical.clone());
            final String invalidSavedCount = "invalid-unverified-history";
            data.setData("stored", invalidSavedCount);
            ItemStack physicalBefore = nativeCopy(menu.getItemInSlot(24));

            FluffyDoctorReport report = FluffyDoctor.run(true);
            check.that(report.getScannedEntries() > 0 && report.getIssuesFound() > 0,
                    "Actual Fluffy Doctor repair detects the unverified native saved counter");
            check.that(invalidSavedCount.equals(data.getData("stored"))
                            && barrel.getStored(block) < 0,
                    "Explicit Doctor repair does not convert unknown historical reserve to zero");
            check.item(physicalBefore, menu.getItemInSlot(24),
                    "Doctor leaves physical output quantity and all ItemMeta/PDC unchanged");
            check.that(menu.getItemInSlot(31) != null
                            && menu.getItemInSlot(31).getType() == Material.BARRIER,
                    "Doctor does not replace empty cosmetic display with fabricated identity");
            writeJson(getDataFolder().toPath().resolve("barrel-doctor-unverified-restart-fixture.json"),
                    Map.of("x", location.getBlockX(), "y", location.getBlockY(),
                            "z", location.getBlockZ(), "stored", invalidSavedCount,
                            "original", Base64.getEncoder().encodeToString(
                                    physicalBefore.serializeAsBytes())));
            check.note("Paper native explicit Doctor repair on synthetic malformed count; "
                    + "no historical AlbionMC reserve has been reconstructed or migrated.");
            return check;
        });
    }

    private CompletableFuture<Check> barrelHistoricalItemNonmerge() {
        return owner(() -> {
            Check check = new Check();
            SlimefunItem barrelItem = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem resource = SlimefunItem.getById("REINFORCED_PLATE");
            if (!(barrelItem instanceof Barrel barrel) || resource == null)
                throw new IllegalStateException("Registered historical identity fixtures missing");
            fixtureNumber++;
            Location location = new Location(world, 2048 + fixtureNumber * 128, 64, 1664);
            holdChunk(location);
            blocks.getChunkData(location.getChunk());
            Block block = location.getBlock();
            block.setType(barrelItem.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(location, barrelItem.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded())
                throw new IllegalStateException("Native historical barrel menu unavailable");
            barrel.setStored(block, 0);

            ItemStack current = resource.getItem().clone();
            current.setAmount(12);
            ItemStack historical = resource.getItem().clone();
            historical.setAmount(37);
            // An owner's typed metadata is part of the immutable source fixture,
            // not something a visually identical Adventure name may override.
            NamespacedKey ownerKey = new NamespacedKey(this, "historical_barrel_owner");
            NamespacedKey privateQuantityKey = new NamespacedKey(this, "historical_barrel_private_quantity");
            for (ItemStack source : List.of(current, historical)) {
                var sourceMeta = source.getItemMeta();
                sourceMeta.getPersistentDataContainer().set(
                        ownerKey, PersistentDataType.STRING, "unchanged-owner");
                sourceMeta.getPersistentDataContainer().set(
                        privateQuantityKey, PersistentDataType.LONG, 9876543210123L);
                source.setItemMeta(sourceMeta);
            }
            var historicalMeta = historical.getItemMeta();
            Component originalName = historicalMeta.displayName();
            if (originalName == null)
                throw new IllegalStateException("Historical Slimefun fixture has no Adventure name");
            TextDecoration.State oldBold = originalName.decoration(TextDecoration.BOLD);
            TextDecoration.State historicalBold = oldBold == TextDecoration.State.TRUE
                    ? TextDecoration.State.FALSE : TextDecoration.State.TRUE;
            historicalMeta.displayName(Component.text("").append(
                    originalName.decoration(TextDecoration.BOLD, historicalBold)));
            historical.setItemMeta(historicalMeta);
            check.that(!historical.isSimilar(current),
                    "Real Paper detects distinct historical bold metadata at matching Slimefun ID");
            check.that(PlainTextComponentSerializer.plainText().serialize(
                        historical.getItemMeta().displayName()).equals(
                        PlainTextComponentSerializer.plainText().serialize(
                                current.getItemMeta().displayName())),
                    "Different historical Adventure structure keeps identical visible item name");
            check.that(historical.getItemMeta().getPersistentDataContainer()
                        .equals(current.getItemMeta().getPersistentDataContainer())
                    && resource.getId().equals(Slimefun.getItemDataService()
                            .getItemData(historical.getItemMeta()).orElse(null)),
                    "Historical and new templates retain the same registered typed Slimefun PDC");
            ItemStack historicalBefore = nativeCopy(historical);
            ItemStack currentBefore = nativeCopy(current);

            int[] inputs = menu.getPreset().getSlotsAccessedByItemTransport(
                    menu, ItemTransportFlow.INSERT, historical);
            check.that(menu.pushItem(historical.clone(), inputs) == null,
                    "Physical barrel input accepts the historical stack of 37");
            barrelItem.getBlockTicker().tick(block, barrelItem, data);
            check.that(barrel.visibleContents(block).safe()
                        && barrel.visibleContents(block).amount() == 37L,
                    "Registered barrel preserves all 37 old items in its real output/reserve");
            check.that(menu.pushItem(current.clone(), inputs) == null,
                    "New registered 12-item input reaches the Cargo-facing barrel slots");
            barrelItem.getBlockTicker().tick(block, barrelItem, data);
            ItemStack buffered = menu.getItemInSlot(24);
            ItemStack pending = menu.getItemInSlot(19);
            check.that(buffered != null && buffered.getAmount() == 37
                        && buffered.isSimilar(historical),
                    "Physical barrel output keeps 37 original historical items and exact metadata");
            check.that(pending != null && pending.getAmount() == 12
                        && pending.isSimilar(current),
                    "Real barrel ticker rejects identity-changing fresh input without consuming it");
            check.that(barrel.visibleContents(block).safe()
                        && barrel.visibleContents(block).amount() == 37L
                        && barrel.getStored(block) == 0,
                    "Historical barrel ledger remains 37, while 12 incompatible items stay pending");
            check.that(barrel.getStoredItem(block).isSimilar(historical)
                        && !barrel.getStoredItem(block).isSimilar(current),
                    "Barrel does not silently rewrite a noncosmetic historical display template");
            // Show a truthful warning at the exact GUI source, not a misleading
            // "0 stored" success pane while incompatible Cargo input is pending.
            byte[] pendingBeforeWarning = menu.getItemInSlot(19).serializeAsBytes();
            byte[] outputBeforeWarning = menu.getItemInSlot(24).serializeAsBytes();
            barrel.updateMenu(block, menu, true, barrel.getCapacity(block));
            ItemStack warning = menu.getItemInSlot(22);
            List<Component> warningLore = warning == null || !warning.hasItemMeta()
                    ? null : warning.getItemMeta().lore();
            check.that(warning != null && warning.getType() == Material.ORANGE_STAINED_GLASS_PANE
                            && warningLore != null && warningLore.stream().anyMatch(line ->
                                PlainTextComponentSerializer.plainText().serialize(line)
                                        .contains("12 item(s) remain in Cargo input slots")),
                    "Native GUI warns about 12 pending incompatible items without claiming they were stored");
            check.that(menu.getItemInSlot(19) != null
                            && menu.getItemInSlot(19).getAmount() == 12
                            && menu.getItemInSlot(19).isSimilar(current)
                            && barrel.visibleContents(block).amount() == 37L
                            && barrel.getStored(block) == 0,
                    "Rendering mismatch warning never consumes Cargo input or changes real stored units");
            check.that(Arrays.equals(pendingBeforeWarning, menu.getItemInSlot(19).serializeAsBytes())
                            && Arrays.equals(outputBeforeWarning, menu.getItemInSlot(24).serializeAsBytes())
                            && "unchanged-owner".equals(menu.getItemInSlot(19).getItemMeta()
                                .getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))
                            && Long.valueOf(9876543210123L).equals(menu.getItemInSlot(24).getItemMeta()
                                .getPersistentDataContainer().get(privateQuantityKey, PersistentDataType.LONG)),
                    "Native serialized old/new bytes, typed owner, and quantity PDC survive warning unchanged");
            FluffyDoctorReport rejectedDoctor = FluffyDoctor.run(false);
            check.that(rejectedDoctor.getDetails().stream().anyMatch(detail ->
                    detail.contains("12 Cargo input item(s) have a different strict item identity")),
                    "Read-only Fluffy Doctor reports actual unmerged historical/new Cargo units");
            check.that(Arrays.equals(pendingBeforeWarning, menu.getItemInSlot(19).serializeAsBytes())
                    && Arrays.equals(outputBeforeWarning, menu.getItemInSlot(24).serializeAsBytes())
                    && barrel.getStored(block) == 0,
                    "Doctor mismatch receipt leaves both old/new item bytes and reserve untouched");
            check.item(historicalBefore, historical,
                    "Original historical source stack survives all native barrel operations");
            check.item(currentBefore, current,
                    "Registered source stack remains unchanged after failed historical merge");

            Map<String, Object> fixture = new LinkedHashMap<>();
            fixture.put("x", location.getBlockX());
            fixture.put("y", location.getBlockY());
            fixture.put("z", location.getBlockZ());
            fixture.put("historical", Base64.getEncoder().encodeToString(historical.serializeAsBytes()));
            fixture.put("current", Base64.getEncoder().encodeToString(current.serializeAsBytes()));
            writeJson(getDataFolder().toPath().resolve("barrel-historical-nonmerge-fixture.json"), fixture);
            check.note("Noncosmetic historical identity and exact counts retained; "
                    + "no legacy item rewrite, no claim that owner worlds match this fixture.");
            return check;
        });
    }

    /**
     * Start with a filled barrel and a historically encoded template, without a
     * fresh input to trigger a change. A production tick must refresh only its
     * display template to the current registered Slimefun item without changing
     * the 37 stored items or their owner/data PDC.
     */
    private CompletableFuture<Check> barrelCanonicalPresentationSelfHeal() {
        return owner(() -> {
            Check check = new Check();
            SlimefunItem barrelItem = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem payload = SlimefunItem.getById("REINFORCED_PLATE");
            if (!(barrelItem instanceof Barrel barrel) || payload == null)
                throw new IllegalStateException("Canonical barrel or metadata payload not registered");
            fixtureNumber++;
            Location location = new Location(world, 1792 + fixtureNumber * 128, 64, 1664);
            Chunk chunk = holdChunk(location);
            blocks.getChunkData(chunk);
            Block block = location.getBlock();
            block.setType(barrelItem.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(location, barrelItem.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded())
                throw new IllegalStateException("Native formatting repair barrel did not load");
            barrel.setStored(block, 37);

            ItemStack canonical = payload.getItem().clone();
            canonical.setAmount(1);
            ItemStack legacy = canonical.clone();
            var legacyMeta = legacy.getItemMeta();
            Component originalName = legacyMeta.displayName();
            List<Component> originalLore = legacyMeta.lore();
            if (originalName != null)
                legacyMeta.displayName(Component.text("").append(originalName));
            if (originalLore != null)
                legacyMeta.lore(originalLore.stream().map(line -> Component.text("").append(line)).toList());
            legacy.setItemMeta(legacyMeta);
            check.that(!legacy.isSimilar(canonical),
                    "Historical display template has structurally different but visibly identical components");
            ItemStack keyed = Utils.keyItem(legacy);
            menu.replaceExistingItem(31, keyed);
            check.that(barrel.visibleContents(block).safe() && barrel.visibleContents(block).amount() == 37L,
                    "Before repair the exact 37-item stored ledger remains readable");

            barrelItem.getBlockTicker().tick(block, barrelItem, data);
            ItemStack repaired = menu.getItemInSlot(31);
            check.that(repaired != null && repaired.getType() == canonical.getType(),
                    "Restored presentation keeps the historical material");
            ItemStack restored = Utils.unKeyItem(repaired);
            check.that(restored.isSimilar(canonical),
                    "Saved template automatically adopts the registered, exact canonical presentation");
            check.that(barrel.visibleContents(block).safe() && barrel.visibleContents(block).amount() == 37L,
                    "Canonical formatting repair preserves 37 items through reserve/output refill");
            check.that(payload.getId().equals(
                    Slimefun.getItemDataService().getItemData(restored.getItemMeta()).orElse(null)),
                    "Self-healed template keeps the same registered Slimefun identity");

            return check;
        });
    }


    /**
     * Execute the production Quick Withdraw path on a real Paper barrel menu.
     * The player and player inventory are minimal API test doubles because
     * the disposable server has no connected human player.
     * Two buffered outputs plus further empty slots previously read OUTPUT_SLOTS[2].
     */
    /**
     * Exercise production watering-can charge serialization on real Paper ItemMeta.
     * A controlled Player API double observes sounds/messages; this does not
     * impersonate a connected player's protection or sapling-growth events.
     */
    private CompletableFuture<Check> wateringCanNativeChargeParity() {
        return owner(() -> {
            Check check = new Check();
            SlimefunItem registered = SlimefunItem.getById("WATERING_CAN");
            if (!(registered instanceof WateringCan can)) {
                throw new IllegalStateException("Registered production WATERING_CAN is unavailable");
            }
            ItemStack held = can.getItem().clone();
            held.setAmount(1);
            org.bukkit.inventory.meta.ItemMeta original = held.getItemMeta();
            if (original == null) {
                throw new IllegalStateException("Watering Can has no native item metadata");
            }
            NamespacedKey marker = new NamespacedKey(this, "native_water_test_owner");
            original.getPersistentDataContainer().set(
                    marker, PersistentDataType.STRING, "owner-private-marker");
            held.setItemMeta(original);
            ItemStack untouched = held.clone();

            org.bukkit.entity.Player controlledPlayer = (org.bukkit.entity.Player)
                    java.lang.reflect.Proxy.newProxyInstance(
                            org.bukkit.entity.Player.class.getClassLoader(),
                            new Class<?>[] {org.bukkit.entity.Player.class},
                            (proxy, method, args) -> {
                                return switch (method.getName()) {
                                    case "getLocation" -> world.getSpawnLocation();
                                    case "playSound", "sendMessage" -> null;
                                    default -> throw new UnsupportedOperationException(
                                            "Unexpected controlled player API: " + method.getName());
                                };
                            });

            check.that(!WateringCan.updateUses(can, controlledPlayer, held, 1),
                    "unfilled watering can refuses a consumption");
            check.that(held.isSimilar(untouched) && held.getAmount() == 1,
                    "refused consumption leaves all native item metadata unchanged");

            int maximum = can.getUses().getValue();
            check.that(maximum > 0, "registered watering can has positive usage capacity");
            check.that(WateringCan.updateUses(can, controlledPlayer, held, 2),
                    "production refill accepts source water");
            NamespacedKey uses = new NamespacedKey(
                    io.ncbpfluffybear.fluffymachines.FluffyMachines.getInstance(),
                    "watering_can_usage");
            Integer refilled = held.getItemMeta().getPersistentDataContainer()
                    .get(uses, PersistentDataType.INTEGER);
            check.that(refilled != null && refilled == maximum,
                    "refill writes maximum charge to typed native PDC");
            check.that(WateringCan.updateUses(can, controlledPlayer, held, 1),
                    "production use consumes one charge");
            Integer remaining = held.getItemMeta().getPersistentDataContainer()
                    .get(uses, PersistentDataType.INTEGER);
            check.that(remaining != null && remaining == maximum - 1,
                    "exactly one charge is consumed");
            check.that(held.getAmount() == 1
                    && "owner-private-marker".equals(held.getItemMeta()
                            .getPersistentDataContainer().get(marker, PersistentDataType.STRING)),
                    "refill and use preserve item quantity and unrelated owner PDC");
            ItemStack corruptedCharge = held.clone();
            corruptedCharge.editMeta(meta -> meta.getPersistentDataContainer()
                    .set(uses, PersistentDataType.INTEGER, -1));
            ItemStack unchangedCorruption = corruptedCharge.clone();
            check.that(!WateringCan.updateUses(can, controlledPlayer, corruptedCharge, 1)
                    && corruptedCharge.equals(unchangedCorruption),
                    "negative typed PDC charge cannot be consumed or silently rewritten");
            check.that(!WateringCan.updateUses(can, controlledPlayer, corruptedCharge, 3)
                    && corruptedCharge.equals(unchangedCorruption),
                    "negative typed PDC charge cannot trigger discharge or silent repair");
            check.that(WateringCan.updateUses(can, controlledPlayer, corruptedCharge, 2)
                    && maximum == corruptedCharge.getItemMeta().getPersistentDataContainer()
                            .get(uses, PersistentDataType.INTEGER)
                    && corruptedCharge.getAmount() == 1
                    && "owner-private-marker".equals(corruptedCharge.getItemMeta()
                            .getPersistentDataContainer().get(marker, PersistentDataType.STRING)),
                    "explicit refill restores invalid charge without losing owner metadata or quantity");
            writeJson(getDataFolder().toPath().resolve("watering-can-charge-restart-fixture.json"),
                    Map.of("serialized_item", Base64.getEncoder().encodeToString(held.serializeAsBytes()),
                            "expected_uses", maximum - 1));
            check.note("Real registered production WateringCan and Paper ItemMeta/PDC; "
                    + "player API double only, no claim of protected live sapling behavior.");
            return check;
        });
    }

    /**
     * Use the real production bonemeal helper on a real Paper OAK_SAPLING,
     * not a proxy block; the tree outcome is random and deliberately not
     * assumed. Permission checks in the player handler still need owner-world
     * protected-region acceptance and are not simulated here.
     */
    private CompletableFuture<Check> wateringCanRealPaperSapling() {
        return owner(() -> {
            Check check = new Check();
            if (!(SlimefunItem.getById("WATERING_CAN") instanceof WateringCan)) {
                throw new IllegalStateException("Production WATERING_CAN is not registered");
            }
            fixtureNumber++;
            Location loc = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
            holdChunk(loc);
            Block soil = loc.clone().subtract(0, 1, 0).getBlock();
            soil.setType(Material.GRASS_BLOCK, false);
            Block sapling = loc.getBlock();
            sapling.setType(Material.OAK_SAPLING, false);
            check.that(sapling.getType() == Material.OAK_SAPLING,
                    "native disposable Paper world contains a real planted oak sapling");

            try {
                Class<?> helper = Class.forName(
                        "io.ncbpfluffybear.fluffymachines.items.tools.SaplingGrowth");
                Method apply = helper.getDeclaredMethod(
                        "applyModernBoneMeal", Block.class, BlockFace.class);
                apply.setAccessible(true);
                apply.invoke(null, sapling, BlockFace.UP);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(
                        "Production Watering Can bonemeal helper failed on real Paper block", failure);
            }
            check.that(sapling.getType() == Material.OAK_SAPLING
                            || sapling.getType() == Material.OAK_LOG,
                    "single native bonemeal attempt never leaves a missing sapling/tree base");
            check.note("Actual production SaplingGrowth.applyModernBoneMeal on Paper OAK_SAPLING. "
                    + "This checks non-destructive native execution, not guaranteed random growth, "
                    + "connected-player protection, or ExoticGarden hooks.");
            return check;
        });
    }

    private CompletableFuture<Check> barrelQuickWithdrawTwoOutputs() {
        return owner(() -> {
            Check check = new Check();
            fixtureNumber++;
            Location loc = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
            Chunk chunk = holdChunk(loc);
            blocks.getChunkData(chunk);
            SlimefunItem sfBarrel = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem sfMaterial = SlimefunItem.getById("REINFORCED_PLATE");
            if (!(sfBarrel instanceof Barrel barrel) || sfMaterial == null)
                throw new IllegalStateException("Production barrel or item unavailable for Quick Withdraw");

            Block block = loc.getBlock();
            block.setType(sfBarrel.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(loc, sfBarrel.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded())
                throw new IllegalStateException("Native Quick Withdraw barrel menu unavailable");
            barrel.setStored(block, 0);

            ItemStack template = sfMaterial.getItem().clone();
            template.setAmount(1);
            ItemStack first = template.clone();
            first.setAmount(64);
            ItemStack second = template.clone();
            second.setAmount(13);
            menu.replaceExistingItem(31, Utils.keyItem(template));
            menu.replaceExistingItem(24, first.clone());
            menu.replaceExistingItem(25, second.clone());
            check.that(barrel.visibleContents(block).safe()
                    && barrel.visibleContents(block).amount() == 77L,
                    "initial 64 + 13 buffered items are registered without saved reserve");

            ItemStack[] held = new ItemStack[36];
            org.bukkit.inventory.PlayerInventory playerInventory =
                (org.bukkit.inventory.PlayerInventory) java.lang.reflect.Proxy.newProxyInstance(
                    org.bukkit.inventory.PlayerInventory.class.getClassLoader(),
                    new Class<?>[] {org.bukkit.inventory.PlayerInventory.class},
                    (proxy, method, arguments) -> {
                        return switch (method.getName()) {
                            case "getStorageContents", "getContents" -> held.clone();
                            case "getItem" -> held[(Integer) arguments[0]];
                            case "setItem" -> {
                                held[(Integer) arguments[0]] =
                                    arguments[1] == null ? null : ((ItemStack) arguments[1]).clone();
                                yield null;
                            }
                            default -> throw new UnsupportedOperationException(
                                    "Unexpected test inventory method: " + method.getName());
                        };
                    });
            org.bukkit.entity.Player testPlayer = (org.bukkit.entity.Player)
                java.lang.reflect.Proxy.newProxyInstance(
                    org.bukkit.entity.Player.class.getClassLoader(),
                    new Class<?>[] {org.bukkit.entity.Player.class},
                    (proxy, method, arguments) -> {
                        if ("getInventory".equals(method.getName())) return playerInventory;
                        throw new UnsupportedOperationException(
                                "Unexpected test player method: " + method.getName());
                    });

            barrel.extract(testPlayer, menu, block,
                    new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction(false, false));

            check.that(held[0] != null && held[0].getAmount() == 64
                    && first.isSimilar(held[0]), "first output transfers intact");
            check.that(held[1] != null && held[1].getAmount() == 13
                    && second.isSimilar(held[1]), "second output transfers intact");
            check.that(held[2] == null && held[3] == null && held[4] == null,
                    "additional empty player slots do not access a nonexistent third barrel output");
            check.that(menu.getItemInSlot(24) == null && menu.getItemInSlot(25) == null
                    && barrel.getStored(block) == 0,
                    "both physical outputs are emptied and saved reserve stays at zero");
            check.that(barrel.visibleContents(block).safe()
                    && barrel.visibleContents(block).amount() == 0L,
                    "barrel content classifier reports a safe empty state");
            long playerTotal = Arrays.stream(held).filter(java.util.Objects::nonNull)
                    .mapToLong(ItemStack::getAmount).sum();
            check.that(playerTotal == 77L, "all 77 items conserved across Quick Withdraw");

            // A Cargo withdrawal may leave output 24 empty while output 25
            // remains occupied. The previous extraction loop repeatedly checked
            // 24 and skipped every free player slot, stranding output 25.
            Arrays.fill(held, null);
            menu.replaceExistingItem(24, null);
            menu.replaceExistingItem(25, second.clone());
            check.that(barrel.visibleContents(block).safe()
                    && barrel.visibleContents(block).amount() == 13L,
                    "only the second output remains, with exactly 13 physical items");
            barrel.extract(testPlayer, menu, block,
                    new me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction(false, false));
            check.that(held[0] != null && held[0].getAmount() == 13
                    && second.isSimilar(held[0]),
                    "Quick Withdraw reaches the occupied second output when the first is empty");
            check.that(held[1] == null && held[2] == null && held[3] == null,
                    "an empty first barrel output causes no phantom player transfers");
            check.that(menu.getItemInSlot(24) == null && menu.getItemInSlot(25) == null
                    && barrel.getStored(block) == 0 && barrel.visibleContents(block).safe()
                    && barrel.visibleContents(block).amount() == 0L,
                    "second-only Quick Withdraw empties the physical buffer without altering reserve");
            check.that(Arrays.stream(held).filter(java.util.Objects::nonNull)
                    .mapToLong(ItemStack::getAmount).sum() == 13L,
                    "all 13 second-only buffered items are conserved");
            check.note("Real production Barrel.extract and BlockMenu on native Paper; "
                    + "player inventory is a minimal API test double, not a connected-player interaction.");
            return check;
        });
    }

    /**
     * On a real Paper server, exercise the exact void-returning BlockMenu.consumeItem
     * used by the production Barrel. Do not infer credits from requested amounts:
     * compare the persisted slot, typed PDC and total remaining physical stock.
     * This is a contract probe, not a migration or barrel accounting rewrite.
     */
    private CompletableFuture<Check> barrelNativeInputConsumption() {
        return owner(() -> {
            Check check = new Check();
            fixtureNumber++;
            Location location = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
            Chunk chunk = holdChunk(location);
            blocks.getChunkData(chunk);
            SlimefunItem sfBarrel = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            SlimefunItem sfMaterial = SlimefunItem.getById("REINFORCED_PLATE");
            if (!(sfBarrel instanceof Barrel barrel) || sfMaterial == null) {
                throw new IllegalStateException("Native barrel input consumption fixture missing");
            }
            Block block = location.getBlock();
            block.setType(sfBarrel.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(location, sfBarrel.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded()) {
                throw new IllegalStateException("Native barrel input menu is unavailable");
            }
            barrel.setStored(block, 0);
            ItemStack incoming = sfMaterial.getItem().clone();
            incoming.setAmount(13);
            NamespacedKey ownerKey = new NamespacedKey(this, "barrel_input_owner_fixture");
            var meta = incoming.getItemMeta();
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, "original-owner");
            incoming.setItemMeta(meta);
            Slimefun.getItemDataService().setItemData(incoming, sfMaterial.getId());
            byte[] historicalSnapshot = incoming.serializeAsBytes();

            // The physical slot, not a detached Java reference, is authoritative.
            menu.replaceExistingItem(19, incoming.clone());
            ItemStack before = menu.getItemInSlot(19).clone();
            long beforeChange = menu.captureChangeSequence();
            menu.consumeItem(19, 5);
            ItemStack after = menu.getItemInSlot(19);
            ItemStack physical = menu.toInventory().getItem(19);
            check.that(after != null && physical != null
                            && after.getAmount() == 8 && physical.getAmount() == 8,
                    "native void consumeItem removes five items from the physical input slot");
            check.that(after != null && before.isSimilar(after),
                    "partial consumption preserves exact component and typed PDC identity");
            check.that(after != null && "original-owner".equals(after.getItemMeta()
                            .getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING)),
                    "native consumption preserves typed owner PDC without rewriting it");
            check.that(after != null && sfMaterial.getId().equals(
                            Slimefun.getItemDataService().getItemData(after).orElse(null)),
                    "native consumption preserves original Slimefun ID");
            check.that(Arrays.equals(historicalSnapshot, incoming.serializeAsBytes()),
                    "observations never mutate the original serialized snapshot");
            check.that(menu.captureChangeSequence() > beforeChange,
                    "partial consumption is marked for persistence");
            check.that(before.getAmount() == 13 && after != null
                            && before.getAmount() == 5 + after.getAmount(),
                    "observed five consumed and eight remaining conserve original thirteen");
            check.that(barrel.getStored(block) == 0,
                    "raw BlockMenu consumption cannot manufacture barrel reserve credits");

            // An invalid empty-slot request must not create credits or mark dirty.
            long beforeEmpty = menu.captureChangeSequence();
            boolean emptySlotThrew = false;
            try {
                menu.consumeItem(20, 7);
            } catch (NullPointerException legacyEmptySlotFailure) {
                // Pinned Slimefun Legacy 4.1.71 delegates an empty slot to
                // Dough ItemUtils.consumeItem, which dereferences a null item.
                // Record the API hazard without weakening the conservation gate.
                emptySlotThrew = true;
            }
            check.that(menu.getItemInSlot(20) == null
                            && menu.captureChangeSequence() == beforeEmpty
                            && barrel.getStored(block) == 0,
                    "empty-slot consume cannot create items or barrel reserve credits");
            check.note("Pinned core empty-slot consumeItem threw NullPointerException: " + emptySlotThrew
                    + "; callers must guard empty slots before invoking the void API.");

            // Actual production ticker absorbs the eight remaining items.
            sfBarrel.getBlockTicker().tick(block, sfBarrel, data);
            check.that(menu.getItemInSlot(19) == null
                            && barrel.visibleContents(block).safe()
                            && barrel.visibleContents(block).amount() == 8L,
                    "production barrel absorbs exactly the eight remaining physical items");
            ItemStack output = menu.getItemInSlot(24);
            check.that(output != null && output.getAmount() == 8 && before.isSimilar(output),
                    "production barrel output preserves original rich item identity");
            check.that(output != null && "original-owner".equals(output.getItemMeta()
                            .getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING)),
                    "barrel output retains typed owner PDC after real input consumption");
            return check;
        });
    }

    /**
     * A virtual-item handler can replace a slot with a different rich identity
     * while keeping the same Slimefun ID and quantity. This is not proof of any
     * physical consumption, so the production barrel must NOT credit storage.
     */
    private CompletableFuture<Check> barrelNativeVirtualReplacement() {
        return owner(() -> {
            Check check = new Check();
            fixtureNumber++;
            Location location = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
            Chunk chunk = holdChunk(location);
            blocks.getChunkData(chunk);
            SlimefunItem sfBarrel = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            if (!(sfBarrel instanceof Barrel barrel)) {
                throw new IllegalStateException("Native barrel virtual replacement fixture missing");
            }
            Block block = location.getBlock();
            block.setType(sfBarrel.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(location, sfBarrel.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded()) {
                throw new IllegalStateException("Native virtual replacement menu unavailable");
            }
            barrel.setStored(block, 0);

            final String id = "TEST_FLUFFY_BARREL_VIRTUAL_OWNER_V82";
            NamespacedKey ownerKey = new NamespacedKey(this, "barrel_virtual_owner_fixture");
            SlimefunItem virtualItem = new SlimefunItem(
                    new ItemGroup(new NamespacedKey(this, "barrel_virtual_owner_group"),
                            new ItemStack(Material.BOOK)),
                    new SlimefunItemStack(id, Material.DIAMOND, "Virtual owner fixture"),
                    RecipeType.ENHANCED_CRAFTING_TABLE,
                    new ItemStack[9]);
            java.util.concurrent.atomic.AtomicInteger handlerCalls = new java.util.concurrent.atomic.AtomicInteger();
            virtualItem.addItemHandler(new VirtualItemHandler() {
                @Override
                public boolean isVirtualItem(ItemStack stack) {
                    return stack != null && stack.getType() == Material.DIAMOND
                            && id.equals(Slimefun.getItemDataService()
                                    .getItemData(stack).orElse(null));
                }

                @Override
                public ItemResult consume(ItemStack stack, int amount, boolean replaceConsumables,
                                          ConsumeContext context) {
                    handlerCalls.incrementAndGet();
                    ItemStack changed = stack.clone();
                    var changedMeta = changed.getItemMeta();
                    changedMeta.getPersistentDataContainer().set(
                            ownerKey, PersistentDataType.STRING, "different-owner");
                    changed.setItemMeta(changedMeta);
                    return ItemResult.handled(changed);
                }
            });
            Slimefun.getRegistry().getSlimefunItemIds().put(id, virtualItem);

            ItemStack incoming = new ItemStack(Material.DIAMOND, 9);
            Slimefun.getItemDataService().setItemData(incoming, id);
            var meta = incoming.getItemMeta();
            meta.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, "original-owner");
            incoming.setItemMeta(meta);
            check.that(Slimefun.getItemStackService().isVirtualItem(incoming),
                    "native core resolves the real virtual item handler");
            byte[] originalBytes = incoming.serializeAsBytes();
            menu.replaceExistingItem(19, incoming.clone());
            sfBarrel.getBlockTicker().tick(block, sfBarrel, data);
            ItemStack remains = menu.getItemInSlot(19);
            check.that(handlerCalls.get() == 1,
                    "real BlockMenu.consumeItem invokes the virtual handler exactly once");
            check.that(remains != null && remains.getAmount() == 9,
                    "virtual replacement preserves all nine physical input items");
            check.that(remains != null && "different-owner".equals(remains.getItemMeta()
                            .getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING)),
                    "actual replacement has different typed PDC despite the same Slimefun ID");
            check.that(remains != null && id.equals(
                            Slimefun.getItemDataService().getItemData(remains).orElse(null)),
                    "virtual replacement still has the same Slimefun ID");
            check.that(barrel.getStored(block) == 0
                            && barrel.visibleContents(block).safe()
                            && barrel.visibleContents(block).amount() == 0,
                    "production barrel refuses phantom credits for virtual owner replacement");
            check.that(Arrays.equals(originalBytes, incoming.serializeAsBytes()),
                    "virtual replacement never mutates the original source snapshot");
            return check;
        });
    }

    private CompletableFuture<Check> barrelDisplayLifecycle() {
        return ownerCompose(() -> {
            Check check = new Check();
            fixtureNumber++;
            Location location = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
            Chunk chunk = holdChunk(location);
            blocks.getChunkData(chunk);
            SlimefunItem item = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
            if (!(item instanceof Barrel barrel) || !item.getBlockTicker().isSynchronized()) {
                throw new IllegalStateException("Real synchronized Fluffy Barrel ticker is unavailable");
            }

            Block block = location.getBlock();
            block.setType(item.getItem().getType(), false);
            SlimefunBlockData data = blocks.createBlock(location, item.getId());
            BlockMenu menu = data.getBlockMenu();
            if (menu == null || !data.isDataLoaded()) {
                throw new IllegalStateException("Registered barrel inventory was not loaded");
            }
            // Direct core createBlock() bypasses the normal player placement/menu initialization
            // callback. Reproduce the persisted "stored=0" that an ordinary empty barrel starts with.
            // Without it Barrel#getStored intentionally rejects the malformed fixture record.
            barrel.setStored(block, 0);

            item.getBlockTicker().tick(block, item, data);
            check.that(barrelDisplays(location).isEmpty(), "empty barrel has no native item display");
            check.that(barrel.getStored(block) == 0, "empty barrel reserve starts at zero");

            ItemStack diamonds = rich(Material.DIAMOND, 17, "barrel-first-item");
            int[] cargoInputs = menu.getPreset().getSlotsAccessedByItemTransport(
                    menu, ItemTransportFlow.INSERT, diamonds);
            check.that(Arrays.equals(cargoInputs, new int[] {19, 20}),
                    "native Cargo insertion exposes only the two input slots");
            check.that(menu.pushItem(diamonds.clone(), cargoInputs) == null,
                    "Cargo-facing insertion accepts the initial diamond payload");
            item.getBlockTicker().tick(block, item, data);
            check.that(barrelQuantity(barrel, block, menu) == 17L,
                "storage still conserves 17 diamonds while visual refresh is delayed");
            check.that(barrelDisplays(location).isEmpty(),
                "cosmetic display inspection stays deferred before the deadline");

            return delay(25).thenCompose(ignored -> ownerCompose(() -> {
                item.getBlockTicker().tick(block, item, data);
                List<ItemDisplay> displays = barrelDisplays(location);
                check.that(displays.size() == 1, "one native ItemDisplay is created after deadline");
                check.that(displays.size() == 1
                    && displays.getFirst().getItemStack().getType() == Material.DIAMOND,
                    "display represents the registered diamonds");
                check.that(barrelQuantity(barrel, block, menu) == 17L,
                    "display refresh does not create or consume storage items");
                ItemStack output = menu.getItemInSlot(24);
                check.that(output != null && output.getAmount() == 17
                    && diamonds.isSimilar(output), "physical output preserves rich item metadata");

                // Cargo input only: both output slots fill even with no output Cargo node.
                // The last 9 items MUST remain in the saved reserve (137 - 128).
                ItemStack extra = diamonds.clone();
                extra.setAmount(120);
                check.that(menu.pushItem(extra, cargoInputs) == null,
                        "Cargo-facing input accepts 120 additional items through both input slots");
                item.getBlockTicker().tick(block, item, data);
                check.that(barrelQuantity(barrel, block, menu) == 137L,
                        "input-only Cargo transfer preserves all 137 diamonds");
                check.that(barrel.getStored(block) == 9,
                        "full output buffers leave exactly 9 diamonds in the saved reserve");
                check.that(menu.getItemInSlot(24) != null && menu.getItemInSlot(24).getAmount() == 64
                        && menu.getItemInSlot(25) != null && menu.getItemInSlot(25).getAmount() == 64,
                        "both physical output buffers contain their full 64-item stacks");
                ItemStack status = menu.getItemInSlot(22);
                check.that(status != null && status.hasItemMeta()
                        && PlainTextComponentSerializer.plainText()
                                .serialize(status.getItemMeta().displayName()).contains("137 /"),
                        "GUI status shows saved reserve PLUS both buffered output stacks");

                // Removing physical output stacks must not delete the saved remainder.
                for (int slot : new int[] {24, 25}) menu.replaceExistingItem(slot, null);
                menu.markDirty();
                item.getBlockTicker().tick(block, item, data);
                check.that(barrelQuantity(barrel, block, menu) == 9L,
                        "draining both physical outputs preserves the 9 saved diamonds");
                check.that(menu.getItemInSlot(24) != null && menu.getItemInSlot(24).getAmount() == 9,
                        "remaining 9 diamonds can flow into the first output buffer");
                for (int slot : new int[] {24, 25}) menu.replaceExistingItem(slot, null);
                menu.markDirty();
                item.getBlockTicker().tick(block, item, data);
                check.that(barrelQuantity(barrel, block, menu) == 0L,
                        "draining output and remaining reserve empties the barrel without loss");

                return delay(25).thenCompose(done -> ownerCompose(() -> {
                    item.getBlockTicker().tick(block, item, data);
                    check.that(barrelDisplays(location).isEmpty(),
                        "empty barrel removes stale cosmetic ItemDisplay after deadline");

                    ItemStack emeralds = rich(Material.EMERALD, 9, "barrel-new-item");
                    menu.replaceExistingItem(19, emeralds.clone());
                    item.getBlockTicker().tick(block, item, data);
                    check.that(barrelQuantity(barrel, block, menu) == 9L,
                        "new item registration conserves nine physical emeralds");

                    return delay(25).thenCompose(finish -> ownerCompose(() -> {
                        item.getBlockTicker().tick(block, item, data);
                        List<ItemDisplay> updatedDisplays = barrelDisplays(location);
                        check.that(updatedDisplays.size() == 1
                            && updatedDisplays.getFirst().getItemStack().getType() == Material.EMERALD,
                            "reassigned barrel display updates to the new item");
                        check.that(barrelQuantity(barrel, block, menu) == 9L,
                            "second visual refresh never changes real storage quantities");

                        // Simulate an external plugin removing the cosmetic entity, not the
                        // barrel's real inventory or persistent registered item identity.
                        if (updatedDisplays.size() == 1) {
                            updatedDisplays.getFirst().remove();
                        }
                        check.that(barrelDisplays(location).isEmpty(),
                            "external display deletion removes only the cosmetic entity");
                        item.getBlockTicker().tick(block, item, data);
                        check.that(barrelDisplays(location).isEmpty(),
                            "missing display is not recreated before its next cosmetic deadline");

                        return delay(25).thenCompose(recheck -> owner(() -> {
                            item.getBlockTicker().tick(block, item, data);
                            List<ItemDisplay> repaired = barrelDisplays(location);
                            check.that(repaired.size() == 1
                                && repaired.getFirst().getItemStack().getType() == Material.EMERALD,
                                "externally removed display self-heals at the next refresh deadline");
                            check.that(barrelQuantity(barrel, block, menu) == 9L,
                                "icon self-healing does not alter stored item quantities");

                            // A corrupted saved counter must never turn into a
                            // fresh empty barrel or consume incoming Cargo items.
                            ItemStack blocked = emeralds.clone();
                            blocked.setAmount(4);
                            barrel.setStored(block, -1);
                            check.that(menu.pushItem(blocked.clone(),
                                    menu.getPreset().getSlotsAccessedByItemTransport(
                                            menu, ItemTransportFlow.INSERT, blocked)) == null,
                                    "Cargo can physically stage an item despite corrupt saved data");
                            item.getBlockTicker().tick(block, item, data);
                            check.that(barrel.getStored(block) == -1,
                                    "invalid saved counter is never reset by the ticker");
                            check.that(menu.getItemInSlot(19) != null
                                    && menu.getItemInSlot(19).getAmount() == 4,
                                    "ticker does not consume Cargo input when saved counter is corrupt");
                            check.that(!barrel.visibleContents(block).safe(),
                                    "corrupt saved counter is visibly classified unsafe");
                            menu.replaceExistingItem(19, null);
                            barrel.setStored(block, 0);
                            BarrelDisplayManager.remove(block);
                            check.that(barrelDisplays(location).isEmpty(),
                                "explicit display removal leaves no orphaned icon");
                            return check;
                        }));
                    }));
                }));
            }));
        });
    }

    private List<ItemDisplay> barrelDisplays(Location location) {
        return world.getNearbyEntities(location.clone().add(0.5, 0.5, 0.5), 1.25, 1.25, 1.25)
            .stream().filter(ItemDisplay.class::isInstance).map(ItemDisplay.class::cast).toList();
    }

    private static long barrelQuantity(Barrel barrel, Block block, BlockMenu menu) {
        long stored = barrel.getStored(block);
        for (int slot : new int[] {19, 20, 24, 25}) {
            ItemStack stack = menu.getItemInSlot(slot);
            if (!empty(stack)) stored += stack.getAmount();
        }
        return stored;
    }

    private CompletableFuture<Check> normal(boolean loader) {
        return normal(loader, false);
    }

    private CompletableFuture<Check> normal(boolean loader, boolean legacy) {
        ItemStack payload = rich(Material.DIAMOND, 17, loader ? "loader-control" : "unloader-control");
        ItemStack occupied = rich(Material.GOLD_INGOT, 3, "occupied-prefix");
        ItemStack spare = rich(Material.IRON_INGOT, 5, "unchosen-stack");
        ItemStack excluded = rich(Material.SHULKER_BOX, 1, "ordinary-excluded-input");
        return bag(loader ? new ItemStack[] {occupied} : new ItemStack[] {null, payload, spare}, legacy).thenCompose(a -> ownerCompose(() -> {
            Machine m = machine(loader);
            m.menu.replaceExistingItem(loader ? 45 : 0, a.item.clone());
            if (loader) {
                m.menu.replaceExistingItem(10, excluded.clone());
                m.menu.replaceExistingItem(11, payload.clone());
                m.menu.replaceExistingItem(12, spare.clone());
            }
            m.energy.setCharge(m.location, 32L);
            return gated(m, 1, () -> CompletableFuture.completedFuture(null)).thenCompose(ignored -> ownerCompose(() -> {
                Check check = new Check();
                if (loader) {
                    check.item(excluded, m.menu.getItemInSlot(10), "earlier prohibited input is retained");
                    check.that(empty(m.menu.getItemInSlot(11)), "first eligible input cleared after valid Loader transfer");
                    check.item(spare, m.menu.getItemInSlot(12), "second eligible stack waits for another tick");
                    check.item(occupied, a.backpack.getInventory().getItem(0), "already occupied backpack slot retained");
                    check.item(payload, a.backpack.getInventory().getItem(1), "exact payload enters first empty backpack slot");
                } else {
                    check.that(empty(a.backpack.getInventory().getItem(0)), "earlier empty backpack slot retained");
                    check.that(empty(a.backpack.getInventory().getItem(1)), "first occupied backpack source cleared after valid Unloader transfer");
                    check.item(spare, a.backpack.getInventory().getItem(2), "second occupied stack waits for another tick");
                    check.item(payload, m.menu.getItemInSlot(UNLOADER_OUTPUTS[0]), "exact payload in first output");
                    check.that(outputCount(m) == payload.getAmount(), "one stack is transferred per tick");
                }
                check.that(m.energy.getChargeLong(m.location) == 16, "one valid transfer consumes exactly16 energy");
                check.item(a.item, m.menu.getItemInSlot(loader ? 45 : 0), "physical backpack identity retained");
                return waitUntil(() -> profiles.getPendingBackpackSaveChainCount() == 0, 120)
                        .thenCompose(v -> blocks.saveBlockInventoryAsync(m.data))
                        .thenCompose(v -> owner(() -> {
                            check.that(profiles.getUncertainBackpackBaselineCount() == 0, "production backpack save completed without uncertainty");
                            remember(m, a, legacy);
                            return check;
                        }));
            }));
        }));
    }

    private CompletableFuture<Check> routing(boolean loader, String kind) {
        ItemStack[] contents = new ItemStack[kind.equals("full") ? 9 : 0];
        for (int i = 0; i < contents.length; i++) contents[i] = new ItemStack(Material.STONE, 64);
        return bag(contents).thenCompose(a -> ownerCompose(() -> {
            Machine m = machine(loader);
            ItemStack physical = kind.equals("unbound")
                    ? nativeCopy(SlimefunItem.getById("SMALL_BACKPACK").getItem()) : a.item.clone();
            boolean full = kind.equals("full");
            ItemStack payload = rich(Material.COPPER_INGOT, 7, "routing-input");
            int source = loader ? (full ? 45 : 10) : 0;
            int target = loader ? (kind.equals("bound") ? 45 : 53) : UNLOADER_OUTPUTS[0];
            m.menu.replaceExistingItem(source, physical.clone());
            if (full) m.menu.replaceExistingItem(10, payload.clone());
            return gated(m, 1, () -> CompletableFuture.completedFuture(null), kind.equals("full") || kind.equals("empty")).thenCompose(v -> owner(() -> {
                Check check = new Check();
                check.that(empty(m.menu.getItemInSlot(source)), "routed backpack leaves source slot");
                check.item(physical, m.menu.getItemInSlot(target), "physical backpack identity and metadata survive routing");
                check.items(copy(contents.length == 0 ? new ItemStack[9] : contents), a.backpack.getInventory().getContents(), "routing does not mutate backing inventory");
                if (full) check.item(payload, m.menu.getItemInSlot(10), "full backpack leaves Loader payload in source");
                check.that(m.energy.getChargeLong(m.location) == 16, "routing consumes no transfer energy");
                return check;
            }));
        }));
    }

    private CompletableFuture<Check> backpackSwap(boolean loader) {
        ItemStack payload = rich(Material.EMERALD, 13, "swap-" + loader);
        ItemStack recovery = rich(Material.QUARTZ, 7, "swap-recovery");
        return bag(loader ? new ItemStack[0] : new ItemStack[] {payload}).thenCompose(a ->
                bag(loader ? new ItemStack[0] : new ItemStack[] {recovery}).thenCompose(b -> ownerCompose(() -> {
                    Machine m = machine(loader);
                    m.menu.replaceExistingItem(loader ? 45 : 0, a.item.clone());
                    if (loader) m.menu.replaceExistingItem(10, payload.clone());
                    ItemStack[] aBefore = copy(a.backpack.getInventory().getContents());
                    ItemStack[] bBefore = copy(b.backpack.getInventory().getContents());
                    Object aSavedBefore = a.backpack.getSnapshot();
                    Object bSavedBefore = b.backpack.getSnapshot();
                    return gated(m, 1, () -> {
                        m.menu.replaceExistingItem(loader ? 45 : 0, b.item.clone());
                        return CompletableFuture.completedFuture(null);
                    }).thenCompose(v -> ownerCompose(() -> {
                        Check check = new Check();
                        check.items(aBefore, a.backpack.getInventory().getContents(), "removed backpack A is unchanged");
                        check.items(bBefore, b.backpack.getInventory().getContents(), "replacement backpack B is unchanged");
                        check.that(aSavedBefore == a.backpack.getSnapshot() && bSavedBefore == b.backpack.getSnapshot(), "cancelled callback acknowledges no backpack save");
                        check.item(b.item, m.menu.getItemInSlot(loader ? 45 : 0), "replacement backpack remains installed");
                        if (loader) check.item(payload, m.menu.getItemInSlot(10), "Loader input retained");
                        else check.that(outputCount(m) == 0, "Unloader output retained");
                        check.that(m.energy.getChargeLong(m.location) == 16, "stale callback consumes no energy");
                        return gated(m, 1, () -> CompletableFuture.completedFuture(null)).thenCompose(done -> owner(() -> {
                            if (loader) {
                                check.item(payload, b.backpack.getInventory().getItem(0), "next valid tick transfers into replacement backpack B");
                                check.that(empty(m.menu.getItemInSlot(10)), "next valid Loader tick clears input");
                            } else {
                                check.item(recovery, m.menu.getItemInSlot(UNLOADER_OUTPUTS[0]), "next valid tick transfers from replacement backpack B");
                                check.that(empty(b.backpack.getInventory().getItem(0)), "next valid Unloader tick clears B source");
                            }
                            check.items(aBefore, a.backpack.getInventory().getContents(), "removed backpack A remains unchanged after recovery");
                            check.that(m.energy.getChargeLong(m.location) == 0, "recovery uses the retained energy allowance");
                            return check;
                        }));
                    }));
                })));
    }

    private CompletableFuture<Check> forbiddenInput(boolean backpackInput) {
        return bag(new ItemStack[0]).thenCompose(a -> bag(new ItemStack[0]).thenCompose(b -> ownerCompose(() -> {
            Machine m = machine(true);
            m.menu.replaceExistingItem(45, a.item.clone());
            m.menu.replaceExistingItem(10, new ItemStack(Material.COBBLESTONE, 5));
            ItemStack forbidden = backpackInput ? b.item.clone() : rich(Material.SHULKER_BOX, 1, "forbidden-shulker");
            Object savedBefore = a.backpack.getSnapshot();
            return gated(m, 1, () -> {
                m.menu.replaceExistingItem(10, forbidden.clone());
                return CompletableFuture.completedFuture(null);
            }).thenCompose(v -> ownerCompose(() -> {
                Check check = new Check();
                check.item(forbidden, m.menu.getItemInSlot(10), "changed prohibited input remains in machine");
                check.that(inventoryCount(a.backpack.getInventory().getContents()) == 0, "prohibited input was not nested");
                check.that(savedBefore == a.backpack.getSnapshot(), "prohibited-input callback acknowledges no backpack save");
                check.that(m.energy.getChargeLong(m.location) == 16, "rejected input consumes no energy");
                ItemStack recovery = rich(Material.COPPER_INGOT, 5, "input-recovery");
                m.menu.replaceExistingItem(10, recovery.clone());
                return gated(m, 1, () -> CompletableFuture.completedFuture(null)).thenCompose(done -> owner(() -> {
                    check.item(recovery, a.backpack.getInventory().getItem(0), "next valid tick accepts ordinary replacement input");
                    check.that(empty(m.menu.getItemInSlot(10)), "recovery input leaves machine");
                    check.that(m.energy.getChargeLong(m.location) == 0, "valid recovery consumes the retained16 energy");
                    return check;
                }));
            }));
        })));
    }

    private CompletableFuture<Check> energyBudget() {
        ItemStack first = rich(Material.DIAMOND, 11, "budget-first");
        ItemStack second = rich(Material.GOLD_INGOT, 19, "budget-second");
        return bag(new ItemStack[] {first, second}).thenCompose(a -> ownerCompose(() -> {
            Machine m = machine(false);
            m.menu.replaceExistingItem(0, a.item.clone());
            return gated(m, 2, () -> CompletableFuture.completedFuture(null)).thenCompose(v -> owner(() -> {
                Check check = new Check();
                check.item(first, m.menu.getItemInSlot(UNLOADER_OUTPUTS[0]), "first stack transferred");
                check.item(second, a.backpack.getInventory().getItem(1), "second stack remains without another energy allowance");
                check.that(outputCount(m) == first.getAmount(), "only one stack transferred");
                check.that(m.energy.getChargeLong(m.location) == 0, "exact16-energy allowance consumed");
                return check;
            }));
        }));
    }

    private CompletableFuture<Check> outputFilled(boolean emptyBackpack) {
        ItemStack payload = rich(Material.IRON_INGOT, 9, "blocked-output");
        return bag(emptyBackpack ? new ItemStack[0] : new ItemStack[] {payload}).thenCompose(a -> ownerCompose(() -> {
            Machine m = machine(false);
            m.menu.replaceExistingItem(0, a.item.clone());
            ItemStack[] before = copy(a.backpack.getInventory().getContents());
            Object savedBefore = a.backpack.getSnapshot();
            return gated(m, 1, () -> {
                for (int slot : UNLOADER_OUTPUTS) m.menu.replaceExistingItem(slot, new ItemStack(Material.STONE, 64));
                return CompletableFuture.completedFuture(null);
            }).thenCompose(v -> owner(() -> {
                Check check = new Check();
                check.item(a.item, m.menu.getItemInSlot(0), "input backpack retained when all output slots become full");
                check.items(before, a.backpack.getInventory().getContents(), "backpack contents retained");
                check.that(savedBefore == a.backpack.getSnapshot(), "blocked output acknowledges no backpack save");
                check.that(outputCount(m) == UNLOADER_OUTPUTS.length * 64, "full output inventory retained");
                check.that(m.energy.getChargeLong(m.location) == 16, "blocked output consumes no energy");
                return check;
            }));
        }));
    }

    private CompletableFuture<Check> invalidatedBackpack() {
        ItemStack payload = rich(Material.LAPIS_LAZULI, 23, "invalidated-instance");
        return bag(new ItemStack[] {payload}).thenCompose(a -> ownerCompose(() -> {
            Machine m = machine(false);
            m.menu.replaceExistingItem(0, a.item.clone());
            Object savedBefore = a.backpack.getSnapshot();
            return gated(m, 1, () -> {
                // Fixture writes have finished. This is the real core disconnect/cache
                // path, not a hand-set invalid flag or a fabricated backpack class.
                profiles.invalidateCacheAfterBackpackPersistence(a.backpack.getOwner().getUniqueId().toString());
                if (!a.backpack.isInvalid()) throw new IllegalStateException("Fixture cache invalidation did not complete");
                return CompletableFuture.completedFuture(null);
            }).thenCompose(v -> ownerCompose(() -> {
                Check check = new Check();
                check.item(payload, a.backpack.getInventory().getItem(0), "invalidated instance is not mutated");
                check.that(savedBefore == a.backpack.getSnapshot(), "invalidated callback acknowledges no backpack save");
                check.that(outputCount(m) == 0, "invalidated callback creates no output");
                check.that(m.energy.getChargeLong(m.location) == 16, "invalidated callback consumes no energy");
                return waitUntil(() -> profiles.getPendingBackpackSaveChainCount() == 0, 120)
                        .thenCompose(done -> profiles.getBackpackAsync(a.backpack.getUniqueId().toString()))
                        .thenCompose(authoritative -> ownerCompose(() -> {
                            if (authoritative == null || authoritative.isInvalid() || authoritative == a.backpack)
                                throw new IllegalStateException("Invalidated backpack did not reload authoritatively");
                            check.item(payload, authoritative.getInventory().getItem(0), "authoritative reload preserves saved payload");
                            return gated(m, 1, () -> CompletableFuture.completedFuture(null)).thenCompose(done -> owner(() -> {
                                check.that(empty(authoritative.getInventory().getItem(0)), "next valid tick clears authoritative source");
                                check.item(payload, m.menu.getItemInSlot(UNLOADER_OUTPUTS[0]), "next valid tick uses reloaded backpack");
                                check.that(m.energy.getChargeLong(m.location) == 0, "authoritative transfer consumes retained energy");
                                check.item(payload, a.backpack.getInventory().getItem(0), "detached invalid instance remains untouched");
                                return check;
                            }));
                        }));
            }));
        }));
    }

    private CompletableFuture<Check> removedMachine() {
        ItemStack payload = rich(Material.REDSTONE, 29, "removed-machine");
        return bag(new ItemStack[] {payload}).thenCompose(a -> ownerCompose(() -> {
            Machine m = machine(false);
            m.menu.replaceExistingItem(0, a.item.clone());
            return gated(m, 1, () -> {
                blocks.removeBlock(m.location);
                m.block.setType(Material.AIR, false);
                if (!m.menu.locked()) throw new IllegalStateException("Core removal did not lock old menu");
                return CompletableFuture.completedFuture(null);
            }).thenCompose(v -> owner(() -> {
                Check check = new Check();
                check.item(payload, a.backpack.getInventory().getItem(0), "removal retains source backpack contents");
                check.that(outputCount(m) == 0, "removed menu has no new output");
                check.that(StorageCacheUtils.getBlock(m.location) == null, "removed block data stays absent");
                check.note("Uses actual core removal/locking, not a connected-player BlockBreakEvent; runner checks task exceptions.");
                return check;
            }));
        }));
    }

    private CompletableFuture<Check> evictedMachine(boolean loader) {
        return evictedMachine(loader, false);
    }

    private CompletableFuture<Check> evictedMachine(boolean loader, boolean reloadBeforeRelease) {
        ItemStack payload = rich(Material.AMETHYST_SHARD, 31, "eviction-" + loader);
        return bag(loader ? new ItemStack[0] : new ItemStack[] {payload}).thenCompose(a -> ownerCompose(() -> {
            Machine m = machine(loader);
            m.menu.replaceExistingItem(loader ? 45 : 0, a.item.clone());
            if (loader) m.menu.replaceExistingItem(10, payload.clone());
            ItemStack[] before = copy(a.backpack.getInventory().getContents());
            Object savedBefore = a.backpack.getSnapshot();
            return blocks.saveBlockInventoryAsync(m.data).thenCompose(v -> ownerCompose(() -> gated(m, 1, () -> {
                int cx = m.location.getBlockX() >> 4;
                int cz = m.location.getBlockZ() >> 4;
                String chunkKey = LocationUtils.getChunkKey(m.location);
                if (blocks.getAllLoadedChunkData().stream().noneMatch(c -> c.getKey().equals(chunkKey)))
                    throw new IllegalStateException("Fixture chunk cache was absent before unload: " + chunkKey);
                world.removePluginChunkTicket(cx, cz, this);
                if (world.getPluginChunkTickets(cx, cz).contains(this))
                    throw new IllegalStateException("Fixture chunk ticket was not removed");
                world.unloadChunkRequest(cx, cz);
                return waitUntil(() -> !world.isChunkLoaded(cx, cz)
                        && blocks.getAllLoadedChunkData().stream().noneMatch(c -> c.getKey().equals(chunkKey)), 160)
                        .thenCompose(done -> reloadBeforeRelease ? ownerCompose(() -> reloadMenu(m)
                                .thenCompose(loaded -> owner(() -> {
                                    if (blocks.getBlockData(m.location).getBlockMenu() == m.menu)
                                        throw new IllegalStateException("Fixture reload did not replace the authoritative menu");
                                    return (Void) null;
                                }))) : CompletableFuture.completedFuture(null));
            }))).thenCompose(v -> ownerCompose(() -> {
                Check check = new Check();
                check.items(before, a.backpack.getInventory().getContents(), "callback after confirmed eviction leaves backpack unchanged");
                check.that(savedBefore == a.backpack.getSnapshot(), "evicted callback acknowledges no backpack save");
                return reloadMenu(m).thenCompose(ignored -> owner(() -> {
                    BlockMenu reloaded = blocks.getBlockData(m.location).getBlockMenu();
                    check.that(reloaded != m.menu, "reload exposes a distinct authoritative menu");
                    if (loader) check.item(payload, reloaded.getItemInSlot(10), "authoritative Loader source retained");
                    else check.that(countSlots(reloaded, UNLOADER_OUTPUTS) == 0, "authoritative Unloader output retained");
                    check.item(a.item, reloaded.getItemInSlot(loader ? 45 : 0), "backpack item identity survived eviction/reload");
                    check.that(m.energy.getChargeLong(m.location) == 16, "authoritative machine energy survives the cancelled callback");
                    check.note("Both actual world unload and controller-cache eviction were observed before callback release.");
                    if (reloadBeforeRelease) check.note("A distinct live menu at the same location was loaded before the stale callback was released.");
                    return check;
                }));
            }));
        }));
    }

    private CompletableFuture<Void> reloadMenu(Machine m) {
        holdChunk(m.location);
        return waitUntil(() -> {
            SlimefunBlockData live = blocks.getBlockData(m.location);
            if (live == null) return false;
            if (!live.isDataLoaded()) blocks.loadBlockData(live);
            return live.getBlockMenu() != null;
        }, 120);
    }

    private CompletableFuture<Void> gated(Machine machine, int ticks, Supplier<CompletableFuture<Void>> mutation) {
        return gated(machine, ticks, mutation, true);
    }

    private CompletableFuture<Void> gated(Machine machine, int ticks, Supplier<CompletableFuture<Void>> mutation,
            boolean expectsCallback) {
        boolean firstTick = firstTickLocations.add(machine.data.getKey());
        Gate gate = new Gate();
        gates.add(gate);
        profiles.getCallbackExecutor().execute(() -> {
            gate.entered.complete(null);
            gate.release.orTimeout(30, TimeUnit.SECONDS).join();
        });
        return gate.entered.thenCompose(v -> ownerCompose(() -> {
            if (firstTick && (!world.isChunkLoaded(machine.location.getBlockX() >> 4, machine.location.getBlockZ() >> 4)
                    || !machine.data.isDataLoaded() || machine.data.isPendingRemove()
                    || StorageCacheUtils.getMenu(machine.location) != machine.menu
                    || machine.energy.getChargeLong(machine.location) < 16))
                throw new IllegalStateException("Initial machine fixture is not a live, loaded, charged authoritative menu");
            if (!(profiles.getCallbackExecutor() instanceof ThreadPoolExecutor callbackExecutor))
                throw new IllegalStateException("Cannot observe the actual core callback executor queue");
            int queuedBefore = callbackExecutor.getQueue().size();
            for (int i = 0; i < ticks; i++) machine.item.getBlockTicker().tick(machine.block, machine.item, machine.data);
            // A first operation that depends on a backpack must demonstrably reach
            // getAsync before we alter its input or lifecycle. Recovery ticks in
            // the negative control may legitimately be out of energy already.
            return (firstTick && expectsCallback
                    ? waitUntil(() -> callbackExecutor.getQueue().size() >= queuedBefore + ticks, 100)
                    : CompletableFuture.<Void>completedFuture(null)).thenCompose(done -> ownerCompose(mutation));
        })).whenComplete((v, failure) -> gate.release.complete(null))
                .thenCompose(v -> gate.release)
                .thenCompose(v -> {
                    CompletableFuture<Void> drained = new CompletableFuture<>();
                    profiles.getCallbackExecutor().execute(() -> drained.complete(null));
                    return drained;
                }).thenCompose(v -> delay(4))
                .thenCompose(v -> waitUntil(() -> profiles.getPendingBackpackSaveChainCount() == 0, 120));
    }

    private CompletableFuture<Bag> bag(ItemStack[] contents) {
        return bag(contents, false);
    }

    private CompletableFuture<Bag> bag(ItemStack[] contents, boolean legacy) {
        // This offline disposable server needs a named native OfflinePlayer: an
        // arbitrary UUID has no name and cannot satisfy the real profile schema.
        OfflinePlayer owner = Bukkit.getOfflinePlayer("Fluffy_" + UUID.randomUUID().toString().substring(0, 8));
        return profiles.getOrCreateProfileAsync(owner).thenCompose(profile -> ownerCompose(() -> {
            PlayerBackpack created = profiles.createBackpack(owner, "native-fixture", 1, 9);
            for (int i = 0; i < contents.length; i++) created.getInventory().setItem(i, contents[i] == null ? null : contents[i].clone());
            ItemStack item = nativeCopy(SlimefunItem.getById("SMALL_BACKPACK").getItem());
            PlayerBackpack.bindItem(item, created);
            ItemStack bound = nativeCopy(item);
            return profiles.saveBackpackInventoryAsync(created)
                    .thenCompose(v -> waitUntil(() -> profiles.getPendingWriteTaskCount() == 0, 120))
                    .thenCompose(v -> profiles.getBackpackAsync(created.getUniqueId().toString()))
                    .thenCompose(canonical -> owner(() -> {
                        if (canonical == null || canonical.isInvalid()) throw new IllegalStateException("Backpack fixture did not load");
                        if (!Arrays.equals(created.getInventory().getContents(), canonical.getInventory().getContents()))
                            throw new IllegalStateException("Fixture seed did not persist exactly");
                        if (!legacy) return new Bag(canonical, bound);
                        ItemStack legacyItem = nativeCopy(SlimefunItem.getById("SMALL_BACKPACK").getItem());
                        var meta = legacyItem.getItemMeta();
                        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                        lore.add(LegacyComponentSerializer.legacySection().deserialize("\u00a77ID: " + owner.getUniqueId() + "#1"));
                        meta.lore(lore);
                        legacyItem.setItemMeta(meta);
                        if (PlayerBackpack.getBackpackUUID(meta).isPresent()
                                || !PlayerBackpack.getLegacyBackpackIdentity(meta).isPresent())
                            throw new IllegalStateException("Legacy lore fixture is not lore-only");
                        return new Bag(canonical, nativeCopy(legacyItem));
                    }));
        }));
    }

    private Machine machine(boolean loader) {
        fixtureNumber++;
        // Leave eight chunks between fixtures so another fixture's real plugin
        // region ticket cannot keep an eviction target loaded through its neighbors.
        Location location = new Location(world, 1536 + fixtureNumber * 128, 64, 1536);
        Chunk chunk = holdChunk(location);
        blocks.getChunkData(chunk);
        SlimefunItem item = SlimefunItem.getById(loader ? "BACKPACK_LOADER" : "BACKPACK_UNLOADER");
        if (loader && !(item instanceof BackpackLoader) || !loader && !(item instanceof BackpackUnloader))
            throw new IllegalStateException("Actual production machine registration unavailable");
        Block block = location.getBlock();
        block.setType(item.getItem().getType(), false);
        SlimefunBlockData data = blocks.createBlock(location, item.getId());
        BlockMenu menu = data.getBlockMenu();
        if (menu == null || !data.isDataLoaded()) throw new IllegalStateException("Machine fixture menu unavailable");
        EnergyNetComponent energy = (EnergyNetComponent) item;
        energy.setCharge(location, 16L);
        if (energy.getChargeLong(location) != 16)
            throw new IllegalStateException("Fixture energy did not initialize to16");
        return new Machine(item, energy, block, location, data, menu, loader);
    }

    private Chunk holdChunk(Location location) {
        Chunk chunk = world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        chunk.addPluginChunkTicket(this);
        if (!chunk.getPluginChunkTickets().contains(this))
            throw new IllegalStateException("Could not hold the native fixture chunk loaded");
        return chunk;
    }

    private CompletableFuture<Void> readChecks() {
        Type type = new TypeToken<List<Map<String, Object>>>() {}.getType();
        final List<Map<String, Object>> fixtures;
        try {
            fixtures = JSON.fromJson(Files.readString(getDataFolder().toPath().resolve("restart-fixtures.json")), type);
        } catch (IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        if (fixtures == null || fixtures.size() != 4)
            return CompletableFuture.failedFuture(new IllegalStateException("Missing ordinary-transfer restart controls"));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (Map<String, Object> fixture : fixtures) {
            chain = chain.thenCompose(v -> profiles.getBackpackAsync((String) fixture.get("backpack_uuid")))
                    .thenCompose(backpack -> ownerCompose(() -> {
                        if (backpack == null) throw new IllegalStateException("Restart backpack is missing");
                        Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                                ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
                        holdChunk(location);
                        SlimefunBlockData data = blocks.getBlockData(location);
                        if (data == null) throw new IllegalStateException("Restart machine is missing");
                        if (!data.isDataLoaded()) blocks.loadBlockData(data);
                        // On the published 4.1.71 core the async loader marks data loaded
                        // before attaching the menu. A read in that brief window is not
                        // evidence of a missing saved inventory. Wait at most five seconds;
                        // a missing menu still fails the phase and all four fixtures remain required.
                        return waitUntil(() -> data.isDataLoaded() && data.getBlockMenu() != null, 100)
                                .thenCompose(v -> owner(() -> {
                                    BlockMenu menu = data.getBlockMenu();
                                    if (!data.isDataLoaded() || menu == null) {
                                        throw new IllegalStateException("Restart machine menu invalidated before inventory comparison");
                                    }
                                    Check check = new Check();
                                    check.items(decodeItems(fixture.get("backpack_contents")), backpack.getInventory().getContents(), "backpack contents survive a separate process");
                                    check.items(decodeItems(fixture.get("machine_contents")), menu.getContents(), "machine contents survive a separate process");
                                    check.that(fixture.get("machine_id").equals(data.getSfId()), "machine identity retained");
                                    EnergyNetComponent energy = (EnergyNetComponent) SlimefunItem.getById(data.getSfId());
                                    check.that(energy.getChargeLong(location) == ((Number) fixture.get("charge")).longValue(), "remaining machine energy survives restart");
                                    results.add(check.result("restart-" + fixture.get("identity") + "-" + fixture.get("machine_id")));
                                    return (Void) null;
                                }));
                    }));
        }
        // Require the synthetic invalid-reserve Doctor fixture to remain intact
        // in a newly started Paper JVM, then repeat the real repair operation.
        // Neither an empty display nor a successful restart proves a zero reserve.
        chain = chain.thenCompose(v -> {
            final Map<String, Object> fixture;
            try {
                Type fixtureType = new TypeToken<Map<String, Object>>() {}.getType();
                fixture = JSON.fromJson(Files.readString(getDataFolder().toPath()
                        .resolve("barrel-doctor-unverified-restart-fixture.json")), fixtureType);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Missing invalid-reserve Doctor restart fixture", failure));
            }
            if (fixture == null || !(fixture.get("stored") instanceof String)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Invalid historical-count Doctor restart fixture"));
            }
            return ownerCompose(() -> {
                Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                        ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
                holdChunk(location);
                SlimefunBlockData data = blocks.getBlockData(location);
                if (data == null) throw new IllegalStateException("Doctor barrel absent after Paper restart");
                if (!data.isDataLoaded()) blocks.loadBlockData(data);
                return waitUntil(() -> data.isDataLoaded() && data.getBlockMenu() != null, 100)
                        .thenCompose(ready -> owner(() -> {
                            BlockMenu menu = data.getBlockMenu();
                            ItemStack original = ItemStack.deserializeBytes(
                                    Base64.getDecoder().decode((String) fixture.get("original")));
                            Check check = new Check();
                            check.that(fixture.get("stored").equals(data.getData("stored")),
                                    "Unverified historical saved count survives separate Paper JVM unchanged");
                            check.item(original, menu.getItemInSlot(24),
                                    "All 19 physical output items and typed owner PDC survive restart");
                            FluffyDoctorReport report = FluffyDoctor.run(true);
                            check.that(report.getScannedEntries() > 0 && report.getIssuesFound() > 0,
                                    "Native Doctor again reports unverified reserve after restart");
                            check.that(fixture.get("stored").equals(data.getData("stored")),
                                    "Second explicit Doctor repair still never zeros historical reserve");
                            check.item(original, menu.getItemInSlot(24),
                                    "Second Doctor repair never consumes or rewrites buffered item PDC");
                            results.add(check.result("restart-barrel-doctor-invalid-count-preserved"));
                            return (Void) null;
                        }));
            });
        });

        // Serialize the native watering-can item across the process boundary;
        // Paper must reconstruct its own charge PDC and unrelated owner field.
        chain = chain.thenCompose(v -> owner(() -> {
            final Map<String, Object> fixture;
            try {
                Type fixtureType = new TypeToken<Map<String, Object>>() {}.getType();
                fixture = JSON.fromJson(Files.readString(getDataFolder().toPath()
                        .resolve("watering-can-charge-restart-fixture.json")), fixtureType);
            } catch (IOException failure) {
                throw new IllegalStateException("Missing native watering-can restart fixture", failure);
            }
            if (fixture == null || !(fixture.get("serialized_item") instanceof String encoded)) {
                throw new IllegalStateException("Invalid watering-can restart fixture");
            }
            ItemStack restored = ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
            NamespacedKey ownerMarker = new NamespacedKey(this, "native_water_test_owner");
            NamespacedKey usage = new NamespacedKey(
                    io.ncbpfluffybear.fluffymachines.FluffyMachines.getInstance(),
                    "watering_can_usage");
            Integer count = restored.getItemMeta().getPersistentDataContainer()
                    .get(usage, PersistentDataType.INTEGER);
            String marker = restored.getItemMeta().getPersistentDataContainer()
                    .get(ownerMarker, PersistentDataType.STRING);
            Check check = new Check();
            check.that(restored.getAmount() == 1
                    && SlimefunItem.getById("WATERING_CAN") != null
                    && restored.getType() == SlimefunItem.getById("WATERING_CAN").getItem().getType(),
                    "same registered Watering Can item and quantity survive a clean Paper restart");
            check.that(count != null && count == ((Number) fixture.get("expected_uses")).intValue(),
                    "typed watering charge PDC survives a separate native Paper process");
            check.that("owner-private-marker".equals(marker),
                    "unrelated owner PDC survives native item serialization and restart");
            results.add(check.result("restart-watering-can-charge-parity"));
            return (Void) null;
        }));
        // A separate process must reload both output buffers and the durable reserve.
        return chain.thenCompose(v -> {
            final Map<String, Object> fixture;
            try {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                fixture = JSON.fromJson(Files.readString(
                        getDataFolder().toPath().resolve("barrel-restart-fixture.json")), mapType);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (fixture == null) return CompletableFuture.failedFuture(
                    new IllegalStateException("Missing barrel restart fixture"));
            return ownerCompose(() -> {
                Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                        ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
                holdChunk(location);
                SlimefunBlockData data = blocks.getBlockData(location);
                if (data == null) throw new IllegalStateException("Barrel block data missing after Paper restart");
                if (!data.isDataLoaded()) blocks.loadBlockData(data);
                return waitUntil(() -> data.isDataLoaded() && data.getBlockMenu() != null, 100)
                        .thenCompose(v2 -> owner(() -> {
                            BlockMenu menu = data.getBlockMenu();
                            SlimefunItem sf = SlimefunItem.getById((String) fixture.get("id"));
                            if (!(sf instanceof Barrel barrel) || menu == null)
                                throw new IllegalStateException("Missing barrel menu after restart");
                            Block block = location.getBlock();
                            Check check = new Check();
                            check.that(fixture.get("id").equals(data.getSfId()),
                                    "barrel registration survives a separate Paper process");
                            check.that(barrel.getStored(block) == ((Number) fixture.get("stored")).intValue(),
                                    "saved Slimefun barrel reserve survives restart unchanged");
                            check.that(barrel.visibleContents(block).safe()
                                    && barrel.visibleContents(block).amount() == ((Number) fixture.get("total")).longValue(),
                                    "both buffered outputs and reserve total survive restart");
                            ItemStack outputA = menu.getItemInSlot(24), outputB = menu.getItemInSlot(25);
                            check.that(outputA != null && outputA.getAmount() == 64
                                    && outputB != null && outputB.getAmount() == 64,
                                    "two physical output buffers survive Paper restart");
                            // Missing slots must produce an assertion failure, not an NPE
                            // that hides the preceding restart/conservation evidence.
                            String outputAId = outputA == null ? null : Slimefun.getItemDataService()
                                    .getItemData(outputA).orElse(null);
                            String outputBId = outputB == null ? null : Slimefun.getItemDataService()
                                    .getItemData(outputB).orElse(null);
                            check.that(fixture.get("item").equals(outputAId)
                                    && fixture.get("item").equals(outputBId),
                                    "Slimefun item identities persist in both output slots");
                            sf.getBlockTicker().tick(block, sf, data);
                            check.that(barrel.visibleContents(block).safe()
                                    && barrel.visibleContents(block).amount() == 137L,
                                    "first post-restart machine tick conserves all items");
                            results.add(check.result("restart-barrel-cargo-contents"));
                            return (Void) null;
                        }));
            });
        }).thenCompose(v -> {
            // This is stock routed by the real CargoNetworkTask, not the separate
            // direct BlockMenu insertion fixture. The persisted reserve is zero
            // because all 95 items were placed into the two physical outputs.
            final Map<String, Object> fixture;
            try {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                fixture = JSON.fromJson(Files.readString(getDataFolder().toPath()
                        .resolve("barrel-real-cargo-restart-fixture.json")), mapType);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(failure);
            }
            if (fixture == null) return CompletableFuture.failedFuture(
                    new IllegalStateException("Missing actual Cargo network restart fixture"));
            return ownerCompose(() -> {
                Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                        ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
                holdChunk(location);
                SlimefunBlockData data = blocks.getBlockData(location);
                if (data == null) throw new IllegalStateException(
                        "Actual Cargo network barrel data missing after Paper restart");
                if (!data.isDataLoaded()) blocks.loadBlockData(data);
                return waitUntil(() -> data.isDataLoaded() && data.getBlockMenu() != null, 100)
                        .thenCompose(v2 -> owner(() -> {
                            BlockMenu menu = data.getBlockMenu();
                            SlimefunItem sf = SlimefunItem.getById((String) fixture.get("id"));
                            if (!(sf instanceof Barrel barrel) || menu == null)
                                throw new IllegalStateException("Missing network-routed barrel after restart");
                            Block block = location.getBlock();
                            Check check = new Check();
                            check.that(fixture.get("id").equals(data.getSfId()),
                                    "network-routed barrel registration survives separate Paper process");
                            // A restart cannot resurrect the source chest's exported
                            // 95 items while retaining the barrel outputs: check both
                            // sides of the real Cargo transfer ledger.
                            Block source = location.clone().add(2, 0, 0).getBlock();
                            if (!(source.getState() instanceof org.bukkit.block.Chest chest))
                                throw new IllegalStateException("Cargo source chest missing after restart");
                            int sourceStock = Arrays.stream(chest.getBlockInventory().getStorageContents())
                                    .filter(java.util.Objects::nonNull)
                                    .mapToInt(ItemStack::getAmount).sum();
                            check.that(sourceStock == 0,
                                    "original Cargo source chest stays empty after restart (no source-side duplication)");
                            check.that(barrel.getStored(block) == 0,
                                    "zero saved reserve remains zero without losing buffered Cargo stock");
                            check.that(menu.getItemInSlot(19) == null && menu.getItemInSlot(20) == null,
                                    "Cargo-facing input buffers remain empty after network delivery/restart");
                            ItemStack outputA = menu.getItemInSlot(24), outputB = menu.getItemInSlot(25);
                            check.that(outputA != null && outputA.getAmount() == 64
                                            && outputB != null && outputB.getAmount() == 31,
                                    "real Cargo network outputs 64 + 31 survive Paper restart exactly");
                            String firstId = outputA == null ? null
                                    : Slimefun.getItemDataService().getItemData(outputA).orElse(null);
                            String secondId = outputB == null ? null
                                    : Slimefun.getItemDataService().getItemData(outputB).orElse(null);
                            check.that(fixture.get("item").equals(firstId)
                                            && fixture.get("item").equals(secondId),
                                    "real Cargo network Slimefun item identities survive restart");
                            check.that(barrel.visibleContents(block).safe()
                                            && barrel.visibleContents(block).amount()
                                            == ((Number) fixture.get("total")).longValue(),
                                    "zero saved reserve plus two physical outputs conserves all 95 items");
                            sf.getBlockTicker().tick(block, sf, data);
                            check.that(barrel.getStored(block) == 0
                                            && barrel.visibleContents(block).safe()
                                            && barrel.visibleContents(block).amount() == 95L,
                                    "first post-restart barrel ticker neither loses nor duplicates Cargo items");
                            ItemStack status = menu.getItemInSlot(22);
                            check.that(status != null && status.hasItemMeta()
                                            && PlainTextComponentSerializer.plainText()
                                            .serialize(status.getItemMeta().displayName()).contains("95 /"),
                                    "post-restart GUI counts both physical outputs despite zero saved reserve");
                            results.add(check.result("restart-barrel-real-cargo-network"));
                            return (Void) null;
                        }));
            });
        }).thenCompose(v -> owner(() -> {
            // Strictly require the Dolly chest fixture made by the seed phase.
            // Never interpret a missing chest/lock/contents as a clean empty state.
            final Map<String, Object> fixture;
            try {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                fixture = JSON.fromJson(Files.readString(getDataFolder().toPath()
                        .resolve("dolly-lock-restart-fixture.json")), mapType);
            } catch (IOException failure) {
                throw new IllegalStateException("Missing native Dolly lock restart evidence", failure);
            }
            if (fixture == null) throw new IllegalStateException("Missing Dolly lock fixture");
            Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                    ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
            holdChunk(location);
            Block block = location.getBlock();
            Check check = new Check();
            check.that(block.getType().name().equals(fixture.get("type")),
                    "Original restored Dolly chest material survives Paper restart");
            if (!(block.getState() instanceof org.bukkit.block.Chest chest))
                throw new IllegalStateException("Native restored Dolly chest missing after restart");
            String lock = (String) fixture.get("lock");
            check.that(chest.isLocked() && lock.equals(readNativeChestLock(chest)),
                    "Production Dolly-restored chest lock survives separate Paper process");
            check.that(chest.customName() != null && fixture.get("name").equals(
                    PlainTextComponentSerializer.plainText().serialize(chest.customName())),
                    "Production Dolly-restored chest custom name survives Paper restart");
            check.items(decodeItems(fixture.get("contents")),
                    chest.getBlockInventory().getStorageContents(),
                    "Exact native Dolly chest contents survive Paper restart with no loss");
            ItemStack saved = ItemStack.deserializeBytes(
                    Base64.getDecoder().decode((String) fixture.get("dolly")));
            var addon = Bukkit.getPluginManager().getPlugin("FluffyMachines");
            if (addon == null) throw new IllegalStateException("FluffyMachines plugin missing");
            check.that(lock.equals(saved.getItemMeta().getPersistentDataContainer().get(
                    new NamespacedKey(addon, "dolly_chest_lock"), PersistentDataType.STRING)),
                    "Dolly PDC lock persisted through native ItemStack bytes roundtrip");
            results.add(check.result("restart-dolly-locked-chest-state"));
            return (Void) null;
        })).thenCompose(v -> {
            final Map<String, Object> fixture;
            try {
                Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                fixture = JSON.fromJson(Files.readString(getDataFolder().toPath()
                        .resolve("barrel-historical-nonmerge-fixture.json")), mapType);
            } catch (IOException failure) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Missing historical barrel nonmerge fixture", failure));
            }
            if (fixture == null)
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Native historical barrel fixture missing after restart"));
            return ownerCompose(() -> {
                Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                        ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
                holdChunk(location);
                SlimefunBlockData data = blocks.getBlockData(location);
                if (data == null)
                    throw new IllegalStateException("Historical barrel not persisted across Paper restart");
                if (!data.isDataLoaded()) blocks.loadBlockData(data);
                return waitUntil(() -> data.isDataLoaded() && data.getBlockMenu() != null, 100)
                        .thenCompose(ready -> owner(() -> {
                            BlockMenu menu = data.getBlockMenu();
                            SlimefunItem sf = SlimefunItem.getById("SMALL_FLUFFY_BARREL");
                            if (!(sf instanceof Barrel barrel) || menu == null)
                                throw new IllegalStateException("Native historical barrel not registered");
                            Block block = location.getBlock();
                            ItemStack historical = ItemStack.deserializeBytes(Base64.getDecoder().decode(
                                    (String) fixture.get("historical")));
                            ItemStack current = ItemStack.deserializeBytes(Base64.getDecoder().decode(
                                    (String) fixture.get("current")));
                            Check check = new Check();
                            ItemStack buffered = menu.getItemInSlot(24);
                            ItemStack pending = menu.getItemInSlot(19);
                            check.that(buffered != null && buffered.getAmount() == 37
                                        && buffered.isSimilar(historical),
                                    "37 historical items remain physically buffered after restart");
                            check.that(pending != null && pending.getAmount() == 12
                                        && pending.isSimilar(current),
                                    "12 different-format new Slimefun items remain in input after restart");
                            check.that(barrel.visibleContents(block).safe()
                                        && barrel.visibleContents(block).amount() == 37L
                                        && barrel.getStored(block) == 0,
                                    "Saved historical reserve+output total is 37 with no invented merging");
                            sf.getBlockTicker().tick(block, sf, data);
                            check.that(menu.getItemInSlot(19) != null
                                        && menu.getItemInSlot(19).isSimilar(current)
                                        && menu.getItemInSlot(19).getAmount() == 12
                                        && menu.getItemInSlot(24) != null
                                        && menu.getItemInSlot(24).isSimilar(historical)
                                        && menu.getItemInSlot(24).getAmount() == 37,
                                    "Actual first restarted barrel tick conserves both distinct item populations");
                            results.add(check.result("restart-barrel-historical-item-nonmerge"));
                            return (Void) null;
                        }));
            });
        });
    }

    private void remember(Machine m, Bag a, boolean legacy) {
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("identity", legacy ? "legacy" : "pdc");
        fixture.put("machine_id", m.item.getId());
        fixture.put("x", m.location.getBlockX()); fixture.put("y", m.location.getBlockY()); fixture.put("z", m.location.getBlockZ());
        fixture.put("backpack_uuid", a.backpack.getUniqueId().toString());
        fixture.put("backpack_contents", encodeItems(a.backpack.getInventory().getContents()));
        fixture.put("machine_contents", encodeItems(m.menu.getContents()));
        fixture.put("charge", m.energy.getChargeLong(m.location));
        restartFixtures.add(fixture);
    }

    private void finish(String phase, Throwable failure) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("phase", phase);
        report.put("at", Instant.now().toString());
        report.put("server", Bukkit.getVersion());
        report.put("core", Bukkit.getPluginManager().getPlugin("Slimefun").getPluginMeta().getVersion());
        report.put("addon", Bukkit.getPluginManager().getPlugin("FluffyMachines").getPluginMeta().getVersion());
        report.put("registered_synchronized_tickers", Map.of(
                "BACKPACK_LOADER", SlimefunItem.getById("BACKPACK_LOADER").getBlockTicker().isSynchronized(),
                "BACKPACK_UNLOADER", SlimefunItem.getById("BACKPACK_UNLOADER").getBlockTicker().isSynchronized()));
        report.put("cases", results);
        report.put("fatal", failure == null ? null : unwrap(failure).toString());
        report.put("passed", failure == null && !results.isEmpty() && results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("passed"))));
        writeJson(getDataFolder().toPath().resolve(phase + "-result.json"), report);
        getLogger().info("FLUFFY_NATIVE_PHASE " + phase + " " + report.get("passed"));
        if (failure != null) unwrap(failure).printStackTrace();
        running = false;
    }

    private CompletableFuture<Void> delay(long ticks) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(this, () -> future.complete(null), ticks);
        return future;
    }

    private CompletableFuture<Void> waitUntil(BooleanSupplier condition, int remainingTicks) {
        return owner(condition::getAsBoolean).thenCompose(done -> {
            if (done) return CompletableFuture.completedFuture(null);
            if (remainingTicks <= 0) return CompletableFuture.failedFuture(new IllegalStateException("Native fixture condition timed out"));
            return delay(1).thenCompose(v -> waitUntil(condition, remainingTicks - 1));
        });
    }

    private <T> CompletableFuture<T> owner(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Runnable run = () -> {
            try { result.complete(action.get()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        if (Bukkit.isPrimaryThread()) run.run(); else Bukkit.getScheduler().runTask(this, run);
        return result;
    }

    private <T> CompletableFuture<T> ownerCompose(Supplier<CompletableFuture<T>> action) {
        return owner(action).thenCompose(value -> value);
    }

    private ItemStack rich(Material material, int amount, String marker) {
        ItemStack item = new ItemStack(material, amount);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("Native fixture " + marker));
        meta.lore(List.of(Component.text("Preserve this exact test payload")));
        meta.getPersistentDataContainer().set(new NamespacedKey(this, "marker"), PersistentDataType.STRING, marker);
        meta.getPersistentDataContainer().set(new NamespacedKey(this, "wide"), PersistentDataType.LONG, 9_007_199_254_741_111L);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack nativeCopy(ItemStack item) { return ItemStack.deserializeBytes(item.serializeAsBytes()); }
    private static ItemStack[] copy(ItemStack[] items) { return Arrays.stream(items).map(i -> i == null ? null : i.clone()).toArray(ItemStack[]::new); }
    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() == 0; }
    private static int inventoryCount(ItemStack[] items) { return Arrays.stream(items).filter(i -> !empty(i)).mapToInt(ItemStack::getAmount).sum(); }
    private static int countSlots(BlockMenu menu, int[] slots) { return Arrays.stream(slots).map(s -> { ItemStack i = menu.getItemInSlot(s); return empty(i) ? 0 : i.getAmount(); }).sum(); }
    private static int outputCount(Machine m) { return countSlots(m.menu, UNLOADER_OUTPUTS); }
    private static List<String> encodeItems(ItemStack[] items) { return Arrays.stream(items).map(i -> empty(i) ? "" : Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList(); }
    private static ItemStack[] decodeItems(Object value) { return ((List<?>) value).stream().map(v -> ((String) v).isEmpty() ? null : ItemStack.deserializeBytes(Base64.getDecoder().decode((String) v))).toArray(ItemStack[]::new); }
    private static Throwable unwrap(Throwable failure) { while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause(); return failure; }
    private static void writeJson(Path target, Object value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJson(value) + "\n");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) { throw new IllegalStateException("Cannot write native evidence", failure); }
    }

    private record Bag(PlayerBackpack backpack, ItemStack item) {}
    private record Machine(SlimefunItem item, EnergyNetComponent energy, Block block, Location location, SlimefunBlockData data, BlockMenu menu, boolean loader) {}
    private record Scenario(String name, Supplier<CompletableFuture<Check>> body) {}
    private static final class Gate { final CompletableFuture<Void> entered = new CompletableFuture<>(); final CompletableFuture<Void> release = new CompletableFuture<>(); }
    private static final class Check {
        final List<Map<String, Object>> assertions = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        void that(boolean pass, String detail) { assertions.add(Map.of("passed", pass, "detail", detail)); }
        void item(ItemStack expected, ItemStack actual, String detail) { that(empty(expected) && empty(actual) || expected != null && expected.equals(actual), detail); }
        void items(ItemStack[] expected, ItemStack[] actual, String detail) { that(Arrays.equals(expected, actual), detail); }
        void note(String value) { notes.add(value); }
        Map<String, Object> result(String name) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name); result.put("passed", !assertions.isEmpty() && assertions.stream().allMatch(a -> Boolean.TRUE.equals(a.get("passed"))));
            result.put("assertions", assertions); result.put("notes", notes); return result;
        }
    }
}
