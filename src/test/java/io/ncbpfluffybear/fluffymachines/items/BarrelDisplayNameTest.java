package io.ncbpfluffybear.fluffymachines.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression for the live IE_ALUMINUM_SINGULARITY extra-vs-text name encoding. */
class BarrelDisplayNameTest {
    @Test
    void extraComponentAndSimpleTextWithSameNameMatch() {
        Component legacy = Component.text("")
                .append(Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, false)
                        .decoration(TextDecoration.UNDERLINED, false));
        Component modern = Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        assertNotEquals(legacy, modern);
        assertTrue(Barrel.samePlainDisplayName(legacy, modern));
    }

    @Test
    void differentItemNamesNeverMatch() {
        assertFalse(Barrel.samePlainDisplayName(Component.text("Aluminum Singularity"),
                Component.text("Gold Singularity")));
    }

    @Test
    void genericAddonItemsAlsoSupportEquivalentLegacyFormatting() {
        for (String name : new String[] {"Aluminum Singularity", "Void Ingot", "Ancient Rune", "Advanced Circuit"}) {
            Component legacy = Component.text("").append(Component.text(name, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, false));
            Component modern = Component.text(name, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false);
            assertTrue(Barrel.samePlainDisplayName(legacy, modern), name);
        }
    }

    @Test
    void visuallySimilarButDifferentTextRemainsDifferent() {
        assertFalse(Barrel.samePlainDisplayName(Component.text("Void Ingot"), Component.text("Void Ingot ")));
        assertFalse(Barrel.samePlainDisplayName(Component.text("Ancient Rune"), Component.text("Ancient Runes")));
    }

    @Test
    void equivalentLoreComponentEncodingsHaveTheSameVisibleLines() {
        List<Component> legacy = List.of(
                Component.text("").append(Component.text("Stored: 15 / 100", NamedTextColor.GRAY)),
                Component.text("").append(Component.text("Item details", NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false)));
        List<Component> modern = List.of(
                Component.text("Stored: 15 / 100", NamedTextColor.GRAY),
                Component.text("Item details", NamedTextColor.AQUA));
        assertNotEquals(legacy, modern);
        assertTrue(Barrel.samePlainLore(legacy, modern));
    }

    @Test
    void importantLoreTextAndLineStructureCannotBeIgnored() {
        List<Component> original = List.of(Component.text("Stored: 15 / 100"), Component.text("Owner A"));
        assertFalse(Barrel.samePlainLore(original, List.of(Component.text("Stored: 16 / 100"), Component.text("Owner A"))));
        assertFalse(Barrel.samePlainLore(original, List.of(Component.text("Stored: 15 / 100"), Component.text("Owner B"))));
        assertFalse(Barrel.samePlainLore(original, List.of(Component.text("Stored: 15 / 100"))));
        assertFalse(Barrel.samePlainLore(original, null));
        assertTrue(Barrel.samePlainLore(null, null));
    }

    @Test
    void missingNamesAreNotTreatedAsEquivalent() {
        assertFalse(Barrel.samePlainDisplayName(null, Component.text("Aluminum Singularity")));
        assertFalse(Barrel.samePlainDisplayName(Component.text("Aluminum Singularity"), null));
    }
}
