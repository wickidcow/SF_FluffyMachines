package io.ncbpfluffybear.fluffymachines.machines;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockPlaceHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockUseHandler;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.items.SimpleSlimefunItem;
import io.github.thebusybiscuit.slimefun4.libraries.dough.common.ChatColors;
import io.github.thebusybiscuit.slimefun4.libraries.paperlib.PaperLib;
import io.github.thebusybiscuit.slimefun4.utils.ChatUtils;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Alternative elevators that use a Chest GUI instead of
 * a Book GUI. Avoids issues with chat plugins.
 * Based off of the ElevatorPlate
 *
 * @author NCBPFluffyBear
 * @author TheBusyBiscuit
 */
public class AlternateElevatorPlate extends SimpleSlimefunItem<BlockUseHandler> {

    private static final String DATA_KEY = "floor";
    private final Set<UUID> users = new HashSet<>();
    private static final int MAX_CHEST_INDEX = 53;

    public AlternateElevatorPlate(ItemGroup category, SlimefunItemStack item, RecipeType recipeType,
                                  ItemStack[] recipe, ItemStack recipeOutput) {
        super(category, item, recipeType, recipe, recipeOutput);

        addItemHandler(onPlace());
    }

    private BlockPlaceHandler onPlace() {
        return new BlockPlaceHandler(false) {

            @Override
            public void onPlayerPlace(@Nonnull BlockPlaceEvent e) {
                Block b = e.getBlock();
                StorageCacheUtils.setData(b.getLocation(), DATA_KEY, "&fFloor #0");
                StorageCacheUtils.setData(b.getLocation(), "owner", e.getPlayer().getUniqueId().toString());
            }
        };
    }

    @Override
    public boolean loadDataByDefault() {
        return true;
    }

    @Nonnull
    public Set<UUID> getUsers() {
        return users;
    }

    @Nonnull
    @Override
    public BlockUseHandler getItemHandler() {
        return e -> {
            Block b = e.getClickedBlock().get();

            if (e.getPlayer().getUniqueId().toString().equals(StorageCacheUtils.getData(b.getLocation(), "owner"))) {
                openEditor(e.getPlayer(), b);
            }
        };
    }

    @Nonnull
    public List<Block> getFloors(@Nonnull Block b) {
        List<Block> floors = new LinkedList<>();

        for (int y = b.getWorld().getMaxHeight(); y > -64; y--) {
            if (y == b.getY()) {
                floors.add(b);
                continue;
            }

            Block block = b.getWorld().getBlockAt(b.getX(), y, b.getZ());

            if (block.getType() == getItem().getType() && StorageCacheUtils.isBlock(block.getLocation(), getId())) {
                floors.add(block);
            }
        }

        return floors;
    }

    @ParametersAreNonnullByDefault
    public void openInterface(Player p, Block b) {
        if (users.remove(p.getUniqueId())) {
            return;
        }

        List<Block> floors = getFloors(b);

        if (floors.size() < 2) {
            Slimefun.getLocalization().sendMessage(p, "machines.ELEVATOR.no-destinations", true);
        } else {
            openFloorSelector(b, floors, p);
        }
    }

    @ParametersAreNonnullByDefault
    private void openFloorSelector(Block b, List<Block> floors, Player p) {
        ChestMenu elevatorMenu = new ChestMenu("Elevator");
        for (int i = 0; i < floors.size(); i++) {

            if (i > MAX_CHEST_INDEX) {
                break;
            }

            Block destination = floors.get(i);
            String floor = ChatColors.color(StorageCacheUtils.getData(destination.getLocation(), DATA_KEY));

            addFloor(elevatorMenu, i, p, floor, b, destination);
        }

        if (floors.size() < MAX_CHEST_INDEX) {
            for (int i = floors.size(); i <= MAX_CHEST_INDEX; i++) {
                elevatorMenu.addItem(i, new CustomItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE, ""));
                elevatorMenu.addMenuClickHandler(i, ChestMenuUtils.getEmptyClickHandler());
            }
        }

        elevatorMenu.open(p);
    }

    @ParametersAreNonnullByDefault
    private void teleport(Player player, String floorName, Block target) {
        Utils.runSync(() -> {
            users.add(player.getUniqueId());

            float yaw = player.getEyeLocation().getYaw() + 180;

            if (yaw > 180) {
                yaw = -180 + (yaw - 180);
            }

            Location destination = new Location(player.getWorld(), target.getX() + 0.5, target.getY() + 0.4,
                target.getZ() + 0.5, yaw, player.getEyeLocation().getPitch());

            PaperLib.teleportAsync(player, destination).thenAccept(teleported -> {
                if (teleported) {
                    player.sendTitle(Utils.color("&f") + ChatColors.color(floorName), null, 20, 60, 20);
                }
            });
        });
    }

    @ParametersAreNonnullByDefault
    public void openEditor(Player p, Block b) {
        ChestMenu menu = new ChestMenu("Elevator Settings");

        menu.addItem(4, new CustomItemStack(Material.NAME_TAG, "&7Set floor name &e(click)", "",
            "&f" + ChatColors.color(StorageCacheUtils.getData(b.getLocation(), DATA_KEY))));
        menu.addMenuClickHandler(4, (pl, slot, item, action) -> {
            pl.closeInventory();
            pl.sendMessage("");
            Slimefun.getLocalization().sendMessage(p, "machines.ELEVATOR.enter-name");
            pl.sendMessage("");

            ChatUtils.awaitInput(pl, message -> {
                StorageCacheUtils.setData(b.getLocation(), DATA_KEY, message.replace('\u00A7', '&'));

                pl.sendMessage("");
                Slimefun.getLocalization().sendMessage(p, "machines.ELEVATOR.named", msg -> msg.replace("%floor" +
                    "%", message));
                pl.sendMessage("");

                openEditor(pl, b);
            });

            return false;
        });

        menu.open(p);
    }

    private void addFloor(ChestMenu menu, int slot, Player p, String floor, Block b, Block destination) {
        if (destination.getY() == b.getY()) {
            menu.addItem(slot, new CustomItemStack(Material.LIME_STAINED_GLASS_PANE,
                ChatColors.color(Slimefun.getLocalization().getMessage(p, "machines.ELEVATOR.current-floor")),
                "", Utils.color("&f") + floor, ""));
            menu.addMenuClickHandler(slot, ChestMenuUtils.getEmptyClickHandler());

        } else {
            menu.addItem(slot, new CustomItemStack(Material.GRAY_STAINED_GLASS_PANE,
                ChatColors.color(Slimefun.getLocalization().getMessage(p,
                    "machines.ELEVATOR.click-to-teleport")), "", Utils.color("&f") + floor, ""));
            menu.addMenuClickHandler(slot, (player, clickSlot, item, action) -> {
                teleport(p, floor, destination);
                return false;
            });
        }
    }

}
