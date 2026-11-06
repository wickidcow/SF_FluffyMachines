package io.ncbpfluffybear.fluffymachines.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

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
        assertFalse(Barrel.samePlainLore(List.of(child), List.of(value)),
                "Only a single unstyled root wrapper may be removed; do not flatten nested trees");
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
}
