package io.ncbpfluffybear.fluffymachines.diagnostics;

import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import io.ncbpfluffybear.fluffymachines.utils.FluffyItems;
import io.ncbpfluffybear.fluffymachines.utils.Utils;
import java.util.ArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Converts a Doctor-validated legacy Dolly lore binding into the modern PDC binding. */
final class LegacyDollySchemaMigrator {

    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private LegacyDollySchemaMigrator() {}

    static boolean migrate(ItemStack dolly, String validationClaim, String backpackUuid) {
        ParsedClaim claim = parseClaim(validationClaim);
        if (claim == null || !isUuid(backpackUuid)) return false;

        ItemMeta meta = dolly.getItemMeta();
        if (meta == null || PlayerBackpack.getBackpackUUID(meta).isPresent()) return false;

        PlayerBackpack.setItemPdc(dolly, backpackUuid, claim.owner().toString());
        meta = dolly.getItemMeta();
        if (meta == null) return false;

        List<Component> currentLore = meta.hasLore() ? meta.lore() : null;
        List<Component> lore = currentLore != null
                ? new ArrayList<>(currentLore)
                : new ArrayList<>();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(claim.owner());
        String ownerName = owner.getName() == null ? "Unknown" : owner.getName();
        Component ownerLine = LEGACY_AMPERSAND.deserialize(FluffyItems.DOLLY_OWNER_LORE + ownerName);

        int ownerIndex = findOwnerLine(lore);
        if (ownerIndex >= 0) {
            lore.set(ownerIndex, ownerLine);
        } else {
            lore.add(ownerLine);
        }
        meta.lore(lore);
        dolly.setItemMeta(meta);
        return true;
    }

    private static int findOwnerLine(List<Component> lore) {
        String ownerPrefix = PLAIN.serialize(LEGACY_AMPERSAND.deserialize(FluffyItems.DOLLY_OWNER_LORE));
        for (int i = 0; i < lore.size(); i++) {
            String plain = PLAIN.serialize(lore.get(i));
            if (plain != null && ownerPrefix != null && plain.startsWith(ownerPrefix)) return i;
        }
        for (int i = lore.size() - 1; i >= 3; i--) {
            String plain = ChatColor.stripColor(lore.get(i));
            if (plain != null && !plain.startsWith("ID: ")) return i;
        }
        return -1;
    }

    private static ParsedClaim parseClaim(String claim) {
        if (claim == null) return null;
        int separator = claim.lastIndexOf('#');
        if (separator <= 0 || separator >= claim.length() - 1) return null;
        try {
            UUID owner = UUID.fromString(claim.substring(0, separator));
            int backpackId = Integer.parseInt(claim.substring(separator + 1));
            return backpackId < 0 ? null : new ParsedClaim(owner, backpackId);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private record ParsedClaim(UUID owner, int backpackId) {}
}
