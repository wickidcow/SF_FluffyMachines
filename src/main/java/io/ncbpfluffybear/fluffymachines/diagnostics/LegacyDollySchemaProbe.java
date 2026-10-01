package io.ncbpfluffybear.fluffymachines.diagnostics;

import io.github.thebusybiscuit.slimefun4.api.player.PlayerBackpack;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.UUID;
import javax.annotation.Nullable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Read-only recognition of historical Dolly backpack bindings. */
final class LegacyDollySchemaProbe {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    static final String CANDIDATE_TYPE = "legacy-dolly-backpack-binding";
    private static final String LEGACY_ID_PREFIX = "ID: ";

    private LegacyDollySchemaProbe() {}

    @Nullable
    static Result inspect(ItemStack item) {
        if (item == null) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null || PlayerBackpack.getBackpackUUID(meta).isPresent()) return null;

        List<Component> lore = meta.hasLore() ? meta.lore() : null;
        if (lore == null || lore.isEmpty()) return null;

        boolean legacyLookingBinding = false;
        for (Component line : lore) {
            String plain = PLAIN.serialize(line);
            if (plain == null || !plain.startsWith(LEGACY_ID_PREFIX)) continue;

            legacyLookingBinding = true;
            int separator = plain.lastIndexOf('#');
            if (separator <= LEGACY_ID_PREFIX.length() || separator >= plain.length() - 1) continue;

            try {
                UUID owner = UUID.fromString(plain.substring(LEGACY_ID_PREFIX.length(), separator));
                int backpackId = Integer.parseInt(plain.substring(separator + 1));
                if (backpackId >= 0) {
                    // The claim is kept in-memory by Slimefun Doctor and is never included in operator output.
                    String validationClaim = owner + "#" + backpackId;
                    return new Result(
                            CANDIDATE_TYPE,
                            "VALIDATION_REQUIRED",
                            "Legacy Dolly backpack binding requires database verification before conversion",
                            validationClaim);
                }
            } catch (IllegalArgumentException ignored) {
                // A legacy-looking line exists, but it is not safe to interpret automatically.
            }
        }

        if (legacyLookingBinding) {
            return new Result(
                    "malformed-legacy-dolly-binding",
                    "MANUAL_ONLY",
                    "Dolly contains a legacy-looking storage binding that could not be parsed safely",
                    null);
        }
        return null;
    }

    record Result(String candidateType, String readiness, String detail, @Nullable String validationClaim) {}
}
