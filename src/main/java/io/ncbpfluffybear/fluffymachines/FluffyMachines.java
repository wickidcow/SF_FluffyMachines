package io.ncbpfluffybear.fluffymachines;

import com.xzavier0722.mc.plugin.slimefun4.storage.callback.IAsyncReadCallback;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.collections.Pair;
import io.ncbpfluffybear.fluffymachines.listeners.BarrelItemFrameListener;
import io.ncbpfluffybear.fluffymachines.listeners.KeyedCrafterListener;
import io.ncbpfluffybear.fluffymachines.utils.BarrelHoverNameManager;
import io.ncbpfluffybear.fluffymachines.utils.Events;
import io.ncbpfluffybear.fluffymachines.utils.McMMOEvents;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.guizhanss.slimefun4.utils.WikiUtils;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentWrapper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

public class FluffyMachines extends JavaPlugin implements SlimefunAddon {

    private static FluffyMachines instance;

    public static final HashMap<ItemStack, List<Pair<ItemStack, List<RecipeChoice>>>> shapedVanillaRecipes = new HashMap<>();
    public static final HashMap<ItemStack, List<Pair<ItemStack, List<RecipeChoice>>>> shapelessVanillaRecipes =
        new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;

        try {
            enablePlugin();
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "FluffyMachines could not finish enabling.", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void enablePlugin() throws Exception {
        // Register ACT Recipes
        Iterator<Recipe> recipeIterator = Bukkit.recipeIterator();

        while (recipeIterator.hasNext()) {
            Recipe r = recipeIterator.next();

            if (r instanceof ShapedRecipe) {
                ShapedRecipe sr = (ShapedRecipe) r;
                List<RecipeChoice> rc = new ArrayList<>();
                ItemStack key = new ItemStack(sr.getResult().getType(), 1);

                // Convert the recipe to a list
                for (Map.Entry<Character, RecipeChoice> choice : sr.getChoiceMap().entrySet()) {
                    if (choice.getValue() != null) {
                        rc.add(choice.getValue());
                    }
                }

                if (!shapedVanillaRecipes.containsKey(key)) {
                    shapedVanillaRecipes.put(
                        key,
                        new ArrayList<>(Collections.singletonList(new Pair<>(sr.getResult(), rc)))
                    );
                } else {
                    shapedVanillaRecipes.get(key).add(new Pair<>(sr.getResult(), rc));
                }
            } else if (r instanceof ShapelessRecipe) {
                ShapelessRecipe slr = (ShapelessRecipe) r;
                ItemStack key = new ItemStack(slr.getResult().getType(), 1);

                // Key has a list of recipe options
                if (!shapelessVanillaRecipes.containsKey(key)) {
                    shapelessVanillaRecipes.put(
                        key,
                        new ArrayList<>(Collections.singletonList(new Pair<>(slr.getResult(), slr.getChoiceList())))
                    );
                } else {
                    shapelessVanillaRecipes.get(key).add(new Pair<>(slr.getResult(), slr.getChoiceList()));
                }
            }
        }

        // Registering Items
        FluffyItemSetup.setup(this);
        FluffyItemSetup.setupBarrels(this);
        BarrelHoverNameManager.initialize();

        // mcMMO remains an optional runtime integration. The listener is registered
        // dynamically so building FluffyMachines does not require the mcMMO API JAR.
        if (getServer().getPluginManager().isPluginEnabled("mcMMO")) {
            if (McMMOEvents.register(this)) {
                getLogger().info("mcMMO integration enabled.");
            } else {
                getLogger().warning("mcMMO is installed, but its ability event could not be registered.");
            }
        }

        WikiUtils.setupJson(this);

        // Register Events Class
        getServer().getPluginManager().registerEvents(new Events(), this);
        getServer().getPluginManager().registerEvents(new BarrelItemFrameListener(), this);
        getServer().getPluginManager().registerEvents(new KeyedCrafterListener(), this);

        final Metrics metrics = new Metrics(this, 8927);
    }

    @Override
    public void onDisable() {
        BarrelHoverNameManager.shutdown();
    }

    @Override
    public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command cmd, @Nonnull String label, String[] args) {

        if (args.length == 0) {
            Utils.send(sender, "&cInvalid command.");
            return true;
        }

        if (!(sender instanceof Player)) {
            Utils.send(sender, "&cOnly players can use this command.");
            return true;
        }

        Player p = (Player) sender;

        switch (args[0].toUpperCase()) {
            case "META":
                Utils.send(p, String.valueOf(p.getInventory().getItemInMainHand().getItemMeta()));
                return true;

            case "RAWMETA":
                p.sendMessage(String.valueOf(p.getInventory().getItemInMainHand().getItemMeta()).replace("§", "&"));
                return true;

            case "VERSION":
            case "V":
                Utils.send(p, "&eCurrent version: " + this.getPluginVersion());
                return true;
        }

        if (p.hasPermission("fluffymachines.admin")) {
            switch (args[0].toUpperCase()) {
                case "ADDINFO":

                    if (args.length != 3) {
                        Utils.send(p, "&cPlease specify a key and value.");
                    } else {
                        RayTraceResult rayResult = p.rayTraceBlocks(5d);
                        SlimefunBlockData blockData = (rayResult != null && rayResult.getHitBlock() != null)
                            ? StorageCacheUtils.getBlock(rayResult.getHitBlock().getLocation())
                            : null;

                        if (blockData != null) {
                            if (blockData.isDataLoaded()) {
                                blockData.setData(args[1], args[2]);
                                Utils.send(p, "&aData applied.");
                            } else {
                                Slimefun.getDatabaseManager().getBlockDataController().loadBlockDataAsync(
                                    blockData,
                                    new IAsyncReadCallback<SlimefunBlockData>() {
                                        @Override
                                        public void onResult(SlimefunBlockData result) {
                                            blockData.setData(args[1], args[2]);
                                            Utils.send(p, "&aData applied.");
                                        }
                                    }
                                );
                            }
                        } else {
                            Utils.send(p, "&cYou must be looking at a Slimefun block.");
                        }
                    }

                    return true;

                case "SAVEPLAYERS":
                    saveAllPlayers();
                    return true;
            }
        }

        Utils.send(p, "&cUnknown command.");
        return false;
    }

    private void saveAllPlayers() {
        Iterator<PlayerProfile> iterator = PlayerProfile.iterator();
        int players = 0;

        while (iterator.hasNext()) {
            PlayerProfile profile = iterator.next();

            profile.save();
            players++;
        }

        if (players > 0) {
            Bukkit.getLogger().log(Level.INFO, "Automatically saved {0} player profiles!", players);
        }
    }

    @Override
    public String getBugTrackerURL() {
        return "https://github.com/SlimefunGuguProject/FluffyMachines/issues";
    }

    @Override
    public String getWikiURL() {
        return "https://slimefun-addons-wiki.guizhanss.cn/fluffy-machines/{0}";
    }

    @Nonnull
    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    public static FluffyMachines getInstance() {
        return instance;
    }
}
