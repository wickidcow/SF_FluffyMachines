package io.ncbpfluffybear.fluffymachines.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fail-closed regression coverage for nested Adventure metadata in Barrel
 * fallback identity matching. No storage or production logic is changed.
 */
class BarrelNestedAdventureIdentityTest {
    @Test
    void emptyWrapperWithHoverOrClickIsNotCosmetic() {
        Component value = Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        assertFalse(Barrel.samePlainDisplayName(
                Component.text("").hoverEvent(HoverEvent.showText(Component.text("owner"))).append(value),
                value));
        assertFalse(Barrel.samePlainDisplayName(
                Component.text("").clickEvent(ClickEvent.suggestCommand("/owner")).append(value),
                value));
    }

    @Test
    void nestedFormattingCannotBeSilentlyDropped() {
        Component value = Component.text("Stored: 731", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        Component child = Component.text("").append(value);
        Component styledChild = Component.text("").color(NamedTextColor.RED).append(value);
        assertTrue(Barrel.samePlainLore(List.of(child), List.of(value)),
                "Nested unstyled single-child root wrappers are cosmetic");
        assertFalse(Barrel.samePlainLore(List.of(styledChild), List.of(value)));
    }

    @Test
    void redundantFalseRootDecorationIsAllowedButItalicIsNot() {
        Component original = Component.text("Stored: 731", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        assertTrue(Barrel.samePlainDisplayName(original.decoration(TextDecoration.OBFUSCATED, false), original));
        assertTrue(Barrel.samePlainDisplayName(original.decoration(TextDecoration.STRIKETHROUGH, false), original));
        assertFalse(Barrel.samePlainDisplayName(
                original, original.decoration(TextDecoration.ITALIC, TextDecoration.State.NOT_SET)));
    }
    @BeforeEach
    void startMockServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void stopMockServer() {
        MockBukkit.unmock();
    }

    @Test
    void legacyCosmeticWrapperKeepsExactTypedPdcAndDoesNotMutateEitherSource() {
        ItemStack historical = itemWithOwner("owner-a", 13);
        ItemStack fresh = historical.clone();
        ItemMeta meta = fresh.getItemMeta();
        Component original = Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(Component.text("").append(original));
        fresh.setItemMeta(meta);
        byte[] beforeHistorical = historical.serializeAsBytes();
        byte[] beforeFresh = fresh.serializeAsBytes();

        assertTrue(Barrel.presentationOnlyDifference(historical, fresh),
                "Only the empty, unstyled Adventure wrapper may be ignored");
        assertArrayEquals(beforeHistorical, historical.serializeAsBytes(),
                "Read-only comparison must not rewrite historical serialized data");
        assertArrayEquals(beforeFresh, fresh.serializeAsBytes(),
                "Read-only comparison must not rewrite new serialized data");
        assertEquals(13, historical.getAmount());
        assertEquals(13, fresh.getAmount());
    }

    @Test
    void matchingVisualsAndSlimefunLikeIdNeverAuthorizeOwnerOrTypedPdcChange() {
        ItemStack original = itemWithOwner("owner-a", 12);
        ItemStack changedOwner = itemWithOwner("owner-b", 12);
        assertFalse(Barrel.presentationOnlyDifference(original, changedOwner),
                "Different typed owner PDC is not a cosmetic legacy difference");

        ItemStack changedType = original.clone();
        ItemMeta meta = changedType.getItemMeta();
        NamespacedKey owner = new NamespacedKey("fluffymachines", "owner_probe");
        meta.getPersistentDataContainer().remove(owner);
        meta.getPersistentDataContainer().set(owner, PersistentDataType.INTEGER, 17);
        changedType.setItemMeta(meta);
        assertFalse(Barrel.presentationOnlyDifference(original, changedType),
                "Even the same PDC key with a different typed value must remain distinct");
        assertEquals(12, original.getAmount());
        assertEquals(12, changedType.getAmount());
    }

    private static ItemStack itemWithOwner(String owner, int amount) {
        ItemStack stack = new ItemStack(Material.NETHERITE_INGOT, amount);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(
                new NamespacedKey("fluffymachines", "owner_probe"),
                PersistentDataType.STRING, owner);
        meta.getPersistentDataContainer().set(
                new NamespacedKey("fluffymachines", "slimefun_id_probe"),
                PersistentDataType.STRING, "SINGULARITY_ALUMINUM");
        stack.setItemMeta(meta);
        return stack;
    }

}
