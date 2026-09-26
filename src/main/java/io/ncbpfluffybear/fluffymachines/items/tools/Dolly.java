package io.ncbpfluffybear.fluffymachines.items.tools;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.ItemSetting;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.ncbpfluffybear.fluffymachines.FluffyMachines;
import io.ncbpfluffybear.fluffymachines.utils.FluffyItems;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import org.bukkit.Bukkit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Chest;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A portable chest mover backed by a Slimefun player backpack.
 *
 * <p>All Bukkit block and inventory operations are forced onto the primary
 * server thread. This is important because the Gugu/Legacy backpack callbacks
 * may complete asynchronously on modern servers.</p>
 */
public class Dolly extends SimpleSlimefunItem<ItemUseHandler> {

    private static final LegacyComponentSerializer LEGACY_COMPONENTS = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private static final ItemStack LOCK_ITEM = Utils.buildNonInteractable(
        Material.DIRT, "&4&lError", "&cThis Dolly is empty."
    );
    private static final int DOUBLE_CHEST_SIZE = 54;
    private static final int SINGLE_CHEST_SIZE = 27;
    private static final int SINGLE_CHEST_MARKER_SLOT = 27;
    private static final NamespacedKey CHEST_TYPE_KEY =
        new NamespacedKey(FluffyMachines.getInstance(), "dolly_chest_type");
    private static final NamespacedKey SECOND_CHEST_TYPE_KEY =
        new NamespacedKey(FluffyMachines.getInstance(), "dolly_second_chest_type");
    private static final NamespacedKey CHEST_LOCK_KEY =
        new NamespacedKey(FluffyMachines.getInstance(), "dolly_chest_lock");
    private static final NamespacedKey CHEST_NAME_KEY =
        new NamespacedKey(FluffyMachines.getInstance(), "dolly_chest_name");

    private final ItemSetting<Boolean> canPickupLockedChest =
        new ItemSetting<>(this, "can-pick-locked-chest", true);
    private final Set<UUID> activeOperations = ConcurrentHashMap.newKeySet();

    public Dolly(ItemGroup category, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(category, item, recipeType, recipe);
        addItemSetting(canPickupLockedChest);
    }

    @Nonnull
    @Override
    public ItemUseHandler getItemHandler() {
        return e -> {
            e.cancel();

            if (!e.getClickedBlock().isPresent()) {
                return;
            }

            Player player = e.getPlayer();
            ItemStack dolly = e.getItem();
            Block clicked = e.getClickedBlock().get();

            // Never move or overwrite regular or universal Slimefun blocks.
            if (StorageCacheUtils.hasSlimefunBlock(clicked.getLocation())) {
                return;
            }

            if (isSupportedChest(clicked)) {
                if (!canPickupChest(clicked, player)) {
                    Utils.send(player, "&cYou cannot pick up this chest.");
                    return;
                }

                startPickup(dolly, clicked, player);
                return;
            }

            Block target = clicked.getRelative(e.getClickedFace());
            placeChest(dolly, target, player);
        };
    }

    private void startPickup(ItemStack dolly, Block chest, Player player) {
        ItemMeta meta = dolly.getItemMeta();
        boolean isBound = meta != null && PlayerBackpack.hasBackpackIdentity(meta);

        try {
            if (!isBound) {
                // Keep profile loading outside the Dolly operation lock. PlayerProfile#get may
                // finish asynchronously, and older Gugu builds do not queue a second callback
                // while the same profile is already loading.
                PlayerProfile.get(player, profile -> {
                    if (!beginOperation(player)) {
                        return;
                    }

                    runDollyOperation(player, () -> {
                        ItemMeta currentMeta = dolly.getItemMeta();
                        boolean becameBound =
                            currentMeta != null && PlayerBackpack.hasBackpackIdentity(currentMeta);

                        if (becameBound) {
                            Utils.send(player, "&eThe Dolly storage was prepared. Use it again.");
                            return;
                        }

                        createBackpackAndPickup(dolly, chest, player, profile);
                    });
                });
                return;
            }

            if (!beginOperation(player)) {
                return;
            }

            loadBackpackAsync(
                dolly,
                player,
                backpack -> pickupChest(dolly, backpack, chest, player),
                () -> reportStorageFailure(player, null)
            );
        } catch (RuntimeException ex) {
            activeOperations.remove(player.getUniqueId());
            reportStorageFailure(player, ex);
        }
    }

    private void createBackpackAndPickup(
        ItemStack dolly,
        Block chest,
        Player player,
        PlayerProfile profile
    ) {
        if (profile == null) {
            reportStorageFailure(player, null);
            return;
        }

        PlayerBackpack backpack = Slimefun.getDatabaseManager()
            .getProfileDataController()
            .createBackpack(player, "&bDolly", profile.nextBackpackNum(), DOUBLE_CHEST_SIZE);
        bindDollyItem(dolly, backpack);
        backpack.getInventory().clear();
        backpack.getInventory().setItem(0, LOCK_ITEM);
        saveBackpack(backpack);
        pickupChest(dolly, backpack, chest, player);
    }

    /**
     * Binds a Dolly to its backing backpack without relying on the
     * dependency-owned owner placeholder. The Gugu dependency may translate
     * that placeholder, so FluffyMachines owns and writes its English line.
     */
    private void bindDollyItem(ItemStack dolly, PlayerBackpack backpack) {
        PlayerBackpack.setItemPdc(
            dolly,
            backpack.getUniqueId().toString(),
            backpack.getOwner().getUniqueId().toString()
        );

        ItemMeta meta = dolly.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Dolly item metadata is missing");
        }

        List<Component> existingLore = meta.hasLore() ? meta.lore() : null;
        List<Component> lore = existingLore != null
            ? new ArrayList<>(existingLore)
            : new ArrayList<>();
        String ownerName = backpack.getOwner().getName();
        Component ownerLine = LEGACY_COMPONENTS.deserialize(
            FluffyItems.DOLLY_OWNER_LORE + (ownerName == null ? "Unknown" : ownerName)
        );

        int ownerLineIndex = findOwnerLoreIndex(lore);
        if (ownerLineIndex >= 0) {
            lore.set(ownerLineIndex, ownerLine);
        } else {
            lore.add(ownerLine);
        }

        meta.lore(lore);
        String backpackName = backpack.getName();
        if (backpackName != null && !backpackName.isBlank()) {
            meta.displayName(LEGACY_COMPONENTS.deserialize(backpackName));
        }
        dolly.setItemMeta(meta);
    }

    /**
     * Finds either the English owner line or the legacy owner slot. Legacy
     * bindings may contain a translated dependency string, so the final
     * non-ID lore line is migrated when no English prefix is present.
     */
    private int findOwnerLoreIndex(List<Component> lore) {
        String ownerPrefix = PLAIN.serialize(LEGACY_COMPONENTS.deserialize(FluffyItems.DOLLY_OWNER_LORE));
        for (int i = 0; i < lore.size(); i++) {
            String plain = PLAIN.serialize(lore.get(i));
            if (plain.startsWith(ownerPrefix)) {
                return i;
            }
        }

        for (int i = lore.size() - 1; i >= 3; i--) {
            String plain = PLAIN.serialize(lore.get(i));
            if (!plain.startsWith("ID: ")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Loads a bound Dolly through Slimefun Legacy's future-based backpack resolver.
     *
     * <p>The resolver supports both modern PDC bindings and legacy lore bindings.
     * Completion is always funneled through {@link #runDollyOperation(Player, Runnable)}
     * before Bukkit inventory or item metadata is touched.</p>
     */
    private void loadBackpackAsync(
        ItemStack dolly,
        Player player,
        Consumer<PlayerBackpack> onFound,
        Runnable onNotFound
    ) {
        try {
            PlayerBackpack.getAsync(dolly).whenComplete((backpack, failure) -> {
                if (failure != null) {
                    runDollyOperation(player, () -> reportStorageFailure(player, failure));
                    return;
                }

                runDollyOperation(player, () -> {
                    if (backpack == null) {
                        onNotFound.run();
                        return;
                    }

                    // Upgrade legacy lore-only bindings to persistent item data.
                    bindDollyItem(dolly, backpack);
                    onFound.accept(backpack);
                });
            });
        } catch (RuntimeException exception) {
            runDollyOperation(player, () -> reportStorageFailure(player, exception));
        }
    }

    private boolean beginOperation(Player player) {
        if (activeOperations.add(player.getUniqueId())) {
            return true;
        }

        Utils.send(player, "&cA Dolly operation is already in progress.");
        return false;
    }

    private void runDollyOperation(Player player, Runnable operation) {
        Runnable wrapped = () -> {
            try {
                operation.run();
            } catch (RuntimeException ex) {
                FluffyMachines.getInstance().getLogger().warning(
                    "Dolly operation failed for " + player.getName() + ": " + ex.getMessage());
                Utils.send(player, "&cThe Dolly operation failed safely; no contents were removed.");
            } finally {
                activeOperations.remove(player.getUniqueId());
            }
        };

        if (Bukkit.isPrimaryThread()) {
            wrapped.run();
        } else {
            Utils.runSync(wrapped);
        }
    }

    private void reportStorageFailure(Player player, @Nullable Throwable failure) {
        if (failure != null) {
            FluffyMachines.getInstance().getLogger().warning(
                "Could not load Dolly storage for " + player.getName() + ": " + failure.getMessage());
        }
        Utils.send(player, "&cThe Dolly's storage could not be loaded.");
    }

    private boolean isSupportedChest(Block block) {
        return isSupportedChestMaterial(block.getType())
            && block.getState() instanceof org.bukkit.block.Chest;
    }

    /**
     * Returns whether the material is a chest that the Dolly can safely move.
     *
     * <p>The name check intentionally covers every vanilla copper chest oxidation
     * and waxing variant without maintaining a fragile hard-coded list. Paper
     * exposes these as {@code COPPER_CHEST}, {@code EXPOSED_COPPER_CHEST},
     * {@code WEATHERED_COPPER_CHEST}, {@code OXIDIZED_COPPER_CHEST}, and their
     * waxed equivalents.</p>
     */
    private boolean isSupportedChestMaterial(@Nullable Material material) {
        return material == Material.CHEST
            || material == Material.TRAPPED_CHEST
            || isCopperChestMaterial(material);
    }

    private boolean isCopperChestMaterial(@Nullable Material material) {
        if (material == null) {
            return false;
        }

        String name = material.name();
        return name.equals("COPPER_CHEST") || name.endsWith("_COPPER_CHEST");
    }

    private boolean canPickupChest(Block block, Player player) {
        if (!isSupportedChest(block) || StorageCacheUtils.hasSlimefunBlock(block.getLocation())) {
            return false;
        }

        for (Block chestBlock : getChestBlocks(block)) {
            if (StorageCacheUtils.hasSlimefunBlock(chestBlock.getLocation())) {
                return false;
            }

            if (!Slimefun.getProtectionManager().hasPermission(
                player, chestBlock.getLocation(), Interaction.BREAK_BLOCK)) {
                return false;
            }

            org.bukkit.block.Chest state = (org.bukkit.block.Chest) chestBlock.getState();
            if (!canPickupLockedChest.getValue() && state.isLocked()) {
                return false;
            }
        }
        return true;
    }

    private void pickupChest(ItemStack dolly, PlayerBackpack backpack, Block chest, Player player) {
        if (!Bukkit.isPrimaryThread()) {
            Utils.runSync(() -> pickupChest(dolly, backpack, chest, player));
            return;
        }

        // The block may have changed while the asynchronous backpack lookup ran.
        if (!canPickupChest(chest, player)) {
            Utils.send(player, "&cThe chest changed or can no longer be picked up.");
            return;
        }

        if (!isLockItem(backpack.getInventory().getItem(0))) {
            Utils.send(player, "&cThis Dolly is already carrying a chest!");
            return;
        }

        if (backpack.getSize() < DOUBLE_CHEST_SIZE) {
            backpack.setSize(DOUBLE_CHEST_SIZE);
        }

        org.bukkit.block.Chest chestState = (org.bukkit.block.Chest) chest.getState();
        Inventory chestInventory = chestState.getInventory();
        Set<Block> chestBlocks = getChestBlocks(chest);
        boolean isDouble = chestInventory.getSize() == DOUBLE_CHEST_SIZE;
        ItemStack[] contents = cloneContents(chestInventory.getStorageContents());
        Material chestMaterial = chest.getType();
        Material secondChestMaterial = getSecondChestMaterial(chest, chestBlocks);

        ItemStack[] previousBackpackContents = cloneContents(backpack.getInventory().getContents());
        try {
            // Replace every backpack slot so stale single/double chest data cannot survive.
            backpack.getInventory().clear();
            backpack.getInventory().setStorageContents(contents);
            if (!isDouble) {
                backpack.getInventory().setItem(SINGLE_CHEST_MARKER_SLOT, LOCK_ITEM);
            }
            saveBackpack(backpack);

            // Persist chest type, lock, and custom name independently of the minecart material.
            setStoredChestData(
                dolly,
                chestMaterial,
                secondChestMaterial,
                chestState.getLock(),
                chestState.getCustomName()
            );
        } catch (RuntimeException ex) {
            restoreBackpack(backpack, previousBackpackContents);
            FluffyMachines.getInstance().getLogger().warning(
                "Could not store a Dolly chest for " + player.getName() + ": " + ex.getMessage());
            Utils.send(player, "&cThe chest could not be picked up; it was left untouched.");
            return;
        }

        // Clear contents before removing both halves, preventing vanilla item drops.
        chestInventory.clear();
        for (Block chestBlock : chestBlocks) {
            chestBlock.setType(Material.AIR, false);
        }

        dolly.setType(Material.CHEST_MINECART);
        Utils.send(player, "&aChest picked up.");
    }

    private void placeChest(ItemStack dolly, Block target, Player player) {
        if (!beginOperation(player)) {
            return;
        }

        try {
            loadBackpackAsync(
                dolly,
                player,
                backpack -> placeChestSync(dolly, backpack, target, player),
                () -> Utils.send(player, "&cThis Dolly is not carrying a chest.")
            );
        } catch (RuntimeException ex) {
            activeOperations.remove(player.getUniqueId());
            reportStorageFailure(player, ex);
        }
    }

    private void placeChestSync(ItemStack dolly, PlayerBackpack backpack, Block target, Player player) {
        if (backpack.getSize() < DOUBLE_CHEST_SIZE) {
            backpack.setSize(DOUBLE_CHEST_SIZE);
            backpack.getInventory().setItem(SINGLE_CHEST_MARKER_SLOT, LOCK_ITEM);
        }

        ItemStack[] backpackContents = cloneContents(backpack.getInventory().getContents());
        if (backpackContents.length == 0 || isLockItem(backpackContents[0])) {
            Utils.send(player, "&cThis Dolly is not carrying a chest!");
            return;
        }

        boolean singleChest = backpackContents.length <= SINGLE_CHEST_MARKER_SLOT
            || isLockItem(backpackContents[SINGLE_CHEST_MARKER_SLOT]);
        Material chestMaterial = getStoredChestMaterial(dolly);
        Material secondChestMaterial = getStoredSecondChestMaterial(dolly, chestMaterial);

        if (!canChestFit(target, player, singleChest)) {
            Utils.send(player, "&cThe chest cannot be placed here!");
            return;
        }

        Block second = singleChest ? null : getRightBlock(target, player.getFacing().getOppositeFace());
        try {
            createChest(target, player, singleChest, chestMaterial, secondChestMaterial);
            applyStoredChestData(dolly, target, second);

            ItemStack[] chestContents = singleChest
                ? Arrays.copyOf(backpackContents, SINGLE_CHEST_SIZE)
                : Arrays.copyOf(backpackContents, DOUBLE_CHEST_SIZE);
            InventoryHolder holder = (InventoryHolder) target.getState();
            holder.getInventory().setStorageContents(chestContents);

            // The transfer is complete: wipe the backing backpack and persist every slot.
            backpack.getInventory().clear();
            backpack.getInventory().setItem(0, LOCK_ITEM);
            saveBackpack(backpack);

            clearStoredChestData(dolly);
            dolly.setType(Material.MINECART);
            Utils.send(player, "&aChest placed.");
        } catch (RuntimeException ex) {
            // Clear any partially placed inventory, restore the backing backpack, then remove blocks.
            if (target.getState() instanceof InventoryHolder) {
                ((InventoryHolder) target.getState()).getInventory().clear();
            }
            restoreBackpack(backpack, backpackContents);
            target.setType(Material.AIR, false);
            if (second != null) {
                second.setType(Material.AIR, false);
            }
            FluffyMachines.getInstance().getLogger().warning(
                "Could not place a Dolly chest for " + player.getName() + ": " + ex.getMessage());
            Utils.send(player, "&cThe chest could not be placed; the Dolly kept its contents.");
        }
    }

    private boolean canChestFit(Block first, Player player, boolean singleChest) {
        if (!first.getType().isAir()
            || StorageCacheUtils.hasSlimefunBlock(first.getLocation())
            || !Slimefun.getProtectionManager().hasPermission(
                player, first.getLocation(), Interaction.PLACE_BLOCK)) {
            return false;
        }

        if (!singleChest) {
            Block second = getRightBlock(first, player.getFacing().getOppositeFace());
            return second.getType().isAir()
                && !StorageCacheUtils.hasSlimefunBlock(second.getLocation())
                && Slimefun.getProtectionManager().hasPermission(
                    player, second.getLocation(), Interaction.PLACE_BLOCK);
        }
        return true;
    }

    private void createChest(
        Block first,
        Player player,
        boolean singleChest,
        Material chestMaterial,
        Material secondChestMaterial
    ) {
        BlockFace chestFace = player.getFacing().getOppositeFace();
        Material firstMaterial = isSupportedChestMaterial(chestMaterial)
            ? chestMaterial
            : Material.CHEST;
        Material secondMaterial = isCompatibleChestPair(firstMaterial, secondChestMaterial)
            ? secondChestMaterial
            : firstMaterial;

        first.setType(firstMaterial, false);
        Directional firstDirectional = (Directional) first.getBlockData();
        firstDirectional.setFacing(chestFace);
        first.setBlockData(firstDirectional, false);

        if (!singleChest) {
            Block second = getRightBlock(first, chestFace);
            second.setType(secondMaterial, false);
            Directional secondDirectional = (Directional) second.getBlockData();
            secondDirectional.setFacing(chestFace);
            second.setBlockData(secondDirectional, false);

            Chest firstData = (Chest) first.getBlockData();
            Chest secondData = (Chest) second.getBlockData();
            firstData.setType(Chest.Type.RIGHT);
            secondData.setType(Chest.Type.LEFT);
            first.setBlockData(firstData, false);
            second.setBlockData(secondData, false);
        }
    }

    @Nonnull
    private Set<Block> getChestBlocks(Block block) {
        Set<Block> blocks = new LinkedHashSet<>();
        blocks.add(block);

        if (!(block.getState() instanceof org.bukkit.block.Chest)) {
            return blocks;
        }

        InventoryHolder holder = ((org.bukkit.block.Chest) block.getState()).getInventory().getHolder();
        if (holder instanceof DoubleChest) {
            DoubleChest doubleChest = (DoubleChest) holder;
            addChestHolderBlock(blocks, doubleChest.getLeftSide());
            addChestHolderBlock(blocks, doubleChest.getRightSide());
        }
        return blocks;
    }

    @Nullable
    private Material getSecondChestMaterial(Block primary, Set<Block> chestBlocks) {
        if (chestBlocks.size() < 2) {
            return null;
        }

        for (Block chestBlock : chestBlocks) {
            if (!chestBlock.equals(primary)) {
                return chestBlock.getType();
            }
        }
        return null;
    }

    private void addChestHolderBlock(Set<Block> blocks, @Nullable InventoryHolder holder) {
        if (holder instanceof org.bukkit.block.Chest) {
            blocks.add(((org.bukkit.block.Chest) holder).getBlock());
        }
    }

    @Nonnull
    private Block getRightBlock(Block block, BlockFace face) {
        BlockFace rightFace;
        switch (face) {
            case NORTH:
                rightFace = BlockFace.WEST;
                break;
            case EAST:
                rightFace = BlockFace.NORTH;
                break;
            case SOUTH:
                rightFace = BlockFace.EAST;
                break;
            case WEST:
                rightFace = BlockFace.SOUTH;
                break;
            default:
                throw new IllegalStateException("Unexpected chest direction: " + face);
        }
        return block.getRelative(rightFace);
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            clone[i] = contents[i] == null ? null : contents[i].clone();
        }
        return clone;
    }

    private void saveBackpack(PlayerBackpack backpack) {
        Slimefun.getDatabaseManager().getProfileDataController().saveBackpackInventory(backpack);
    }

    private void setStoredChestData(
        ItemStack dolly,
        Material material,
        @Nullable Material secondMaterial,
        String lock,
        @Nullable String customName
    ) {
        ItemMeta meta = dolly.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("Dolly item metadata is missing");
        }

        meta.getPersistentDataContainer().set(CHEST_TYPE_KEY, PersistentDataType.STRING, material.name());
        if (secondMaterial == null) {
            meta.getPersistentDataContainer().remove(SECOND_CHEST_TYPE_KEY);
        } else {
            meta.getPersistentDataContainer().set(
                SECOND_CHEST_TYPE_KEY,
                PersistentDataType.STRING,
                secondMaterial.name()
            );
        }
        setOrRemove(meta, CHEST_LOCK_KEY, lock);
        setOrRemove(meta, CHEST_NAME_KEY, customName);
        dolly.setItemMeta(meta);
    }

    private void setOrRemove(ItemMeta meta, NamespacedKey key, @Nullable String value) {
        if (value == null || value.isEmpty()) {
            meta.getPersistentDataContainer().remove(key);
        } else {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        }
    }

    private Material getStoredChestMaterial(ItemStack dolly) {
        ItemMeta meta = dolly.getItemMeta();
        if (meta == null) {
            return Material.CHEST;
        }

        String value = meta.getPersistentDataContainer().get(CHEST_TYPE_KEY, PersistentDataType.STRING);
        Material material = value == null ? null : Material.matchMaterial(value);
        return isSupportedChestMaterial(material) ? material : Material.CHEST;
    }

    private Material getStoredSecondChestMaterial(ItemStack dolly, Material firstMaterial) {
        ItemMeta meta = dolly.getItemMeta();
        if (meta == null) {
            return firstMaterial;
        }

        String value = meta.getPersistentDataContainer().get(
            SECOND_CHEST_TYPE_KEY,
            PersistentDataType.STRING
        );
        Material material = value == null ? null : Material.matchMaterial(value);
        return isCompatibleChestPair(firstMaterial, material) ? material : firstMaterial;
    }

    private boolean isCompatibleChestPair(Material first, @Nullable Material second) {
        if (!isSupportedChestMaterial(first) || !isSupportedChestMaterial(second)) {
            return false;
        }

        if (isCopperChestMaterial(first)) {
            return isCopperChestMaterial(second);
        }

        // Normal and trapped chests may only form doubles with the same type.
        return first == second;
    }

    private void applyStoredChestData(ItemStack dolly, Block first, @Nullable Block second) {
        ItemMeta meta = dolly.getItemMeta();
        if (meta == null) {
            return;
        }

        String lock = meta.getPersistentDataContainer().get(CHEST_LOCK_KEY, PersistentDataType.STRING);
        String customName = meta.getPersistentDataContainer().get(CHEST_NAME_KEY, PersistentDataType.STRING);
        applyChestState(first, lock, customName);
        if (second != null) {
            applyChestState(second, lock, customName);
        }
    }

    private void applyChestState(Block block, @Nullable String lock, @Nullable String customName) {
        if (!(block.getState() instanceof org.bukkit.block.Chest)) {
            return;
        }

        org.bukkit.block.Chest state = (org.bukkit.block.Chest) block.getState();
        if (lock != null) {
            state.setLock(lock);
        }
        if (customName != null) {
            state.setCustomName(customName);
        }
        state.update(true, false);
    }

    private void clearStoredChestData(ItemStack dolly) {
        ItemMeta meta = dolly.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().remove(CHEST_TYPE_KEY);
            meta.getPersistentDataContainer().remove(SECOND_CHEST_TYPE_KEY);
            meta.getPersistentDataContainer().remove(CHEST_LOCK_KEY);
            meta.getPersistentDataContainer().remove(CHEST_NAME_KEY);
            dolly.setItemMeta(meta);
        }
    }

    private void restoreBackpack(PlayerBackpack backpack, ItemStack[] contents) {
        backpack.getInventory().clear();
        backpack.getInventory().setContents(cloneContents(contents));
        try {
            saveBackpack(backpack);
        } catch (RuntimeException saveFailure) {
            FluffyMachines.getInstance().getLogger().severe(
                "Could not persist a Dolly rollback: " + saveFailure.getMessage());
        }
    }

    private boolean isLockItem(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return Utils.checkNonInteractable(item)
            || meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == 6969;
    }
}
