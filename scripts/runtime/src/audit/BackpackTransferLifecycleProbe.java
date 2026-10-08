package audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.BlockDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.ProfileDataController;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;

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
                world.getChunkAt(cx, cz).removePluginChunkTicket(this);
                if (!world.unloadChunk(cx, cz, true)) throw new IllegalStateException("Fixture chunk refused unload");
                return waitUntil(() -> !world.isChunkLoaded(cx, cz)
                        && blocks.getAllLoadedChunkData().stream().noneMatch(c -> c.getKey().equals(chunkKey(m))), 160)
                        .thenCompose(done -> reloadBeforeRelease ? ownerCompose(() -> reloadMenu(m)) : CompletableFuture.completedFuture(null));
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
        Location location = new Location(world, 1536 + fixtureNumber * 32, 64, 1536);
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
                    .thenCompose(backpack -> owner(() -> {
                        if (backpack == null) throw new IllegalStateException("Restart backpack is missing");
                        Location location = new Location(world, ((Number) fixture.get("x")).intValue(),
                                ((Number) fixture.get("y")).intValue(), ((Number) fixture.get("z")).intValue());
                        holdChunk(location);
                        SlimefunBlockData data = blocks.getBlockData(location);
                        if (data == null) throw new IllegalStateException("Restart machine is missing");
                        if (!data.isDataLoaded()) blocks.loadBlockData(data);
                        Check check = new Check();
                        check.items(decodeItems(fixture.get("backpack_contents")), backpack.getInventory().getContents(), "backpack contents survive a separate process");
                        check.items(decodeItems(fixture.get("machine_contents")), data.getBlockMenu().getContents(), "machine contents survive a separate process");
                        check.that(fixture.get("machine_id").equals(data.getSfId()), "machine identity retained");
                        EnergyNetComponent energy = (EnergyNetComponent) SlimefunItem.getById(data.getSfId());
                        check.that(energy.getChargeLong(location) == ((Number) fixture.get("charge")).longValue(), "remaining machine energy survives restart");
                        results.add(check.result("restart-" + fixture.get("identity") + "-" + fixture.get("machine_id")));
                        return (Void) null;
                    }));
        }
        return chain;
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

    private static String chunkKey(Machine m) { return m.location.getWorld().getName() + ";" + (m.location.getBlockX() >> 4) + ";" + (m.location.getBlockZ() >> 4); }
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
