package audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.BlockDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.ProfileDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.LocationUtils;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.core.networks.cargo.CargoNet;
import io.github.thebusybiscuit.slimefun4.utils.itemstack.ItemStackWrapper;
import io.ncbpfluffybear.fluffymachines.items.Barrel;
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
                new Scenario("barrel-display-and-quantities", this::barrelDisplayLifecycle),
                new Scenario("barrel-slimefun-cargo-metadata", this::barrelSlimefunCargoMetadata),
                new Scenario("barrel-canonical-presentation-self-heal", this::barrelCanonicalPresentationSelfHeal),
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
     * Exercises the real registered Fluffy Barrel ticker and Bukkit ItemDisplay entities.
     * Inputs and output slots remain physical inventories; only the presentation cache is delayed.
     */
    /**
     * Native Cargo-facing insertion of two different registered Slimefun items.
     * Cosmetic Adventure component layouts may vary; lore and Slimefun identity may not.
     */
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
            check.that(status != null && status.hasItemMeta()
                    && PlainTextComponentSerializer.plainText()
                            .serialize(status.getItemMeta().displayName()).contains("95 /"),
                    "barrel GUI accurately reports actual Cargo network output and reserve");
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
