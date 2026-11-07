package io.ncbpfluffybear.fluffymachines.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Root-only cosmetic normalization must not erase meaningful Adventure
 * metadata in legacy barrel item names or lore. These tests do not touch
 * inventories, storage amounts, or saved-world item identity.
 */
class BarrelCosmeticFalseWrapperTest {
    private static Component itemName() {
        return Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
    }

    @Test
    void redundantFalseDecorationsMakeEmptySingleChildRootCosmetic() {
        Component original = itemName();
        Component wrapped = Component.text("")
                .decoration(TextDecoration.BOLD, false)
                .append(original);

        assertTrue(Barrel.samePlainDisplayName(wrapped, original));
        assertTrue(Barrel.samePlainLore(List.of(wrapped), List.of(original)));
    }

    @Test
    void nestedRedundantFalseWrappersNormalizeAtEveryLevel() {
        Component original = itemName();
        Component nested = Component.text("")
                .decoration(TextDecoration.STRIKETHROUGH, false)
                .append(Component.text("")
                        .decoration(TextDecoration.OBFUSCATED, false)
                        .append(original));

        assertTrue(Barrel.samePlainDisplayName(nested, original));
        assertTrue(Barrel.samePlainLore(List.of(nested, original), List.of(original, original)));
    }

    @Test
    void meaningfulRootStylesAndInsertionRemainIdentitySensitive() {
        Component original = itemName();
        assertFalse(Barrel.samePlainDisplayName(
                Component.text("").decoration(TextDecoration.ITALIC, false).append(original),
                original));
        assertFalse(Barrel.samePlainDisplayName(
                Component.text("").color(NamedTextColor.RED).append(original),
                original));
        assertFalse(Barrel.samePlainDisplayName(
                Component.text("").insertion("owner-private").append(original),
                original));
        assertFalse(Barrel.samePlainDisplayName(
                Component.text("").hoverEvent(HoverEvent.showText(Component.text("owner"))).append(original),
                original));
    }

    @Test
    void multipleChildrenCannotBeDroppedAsCosmeticWrappers() {
        Component original = itemName();
        Component sibling = Component.text("")
                .decoration(TextDecoration.BOLD, false)
                .append(original)
                .append(Component.text("hidden"));

        assertFalse(Barrel.samePlainDisplayName(sibling, original));
        assertFalse(Barrel.samePlainLore(List.of(sibling), List.of(original)));
    }
}
