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
                Component.text("Item details", NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
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
    void identicalVisibleNameCannotHideMeaningfulAdventureDifferences() {
        Component original = Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        assertFalse(Barrel.samePlainDisplayName(original,
                Component.text("Aluminum Singularity", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)),
                "Do not collapse a meaningful color change");
        assertFalse(Barrel.samePlainDisplayName(original,
                original.decorate(TextDecoration.BOLD)),
                "Do not collapse a meaningful bold decoration");
        assertFalse(Barrel.samePlainDisplayName(original,
                original.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("Owner A")))),
                "Do not discard hover metadata");
        assertFalse(Barrel.samePlainDisplayName(original,
                original.clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/different"))),
                "Do not discard click metadata");
        assertFalse(Barrel.samePlainDisplayName(original,
                original.insertion("different insertion")),
                "Do not discard insertion metadata");
        assertFalse(Barrel.samePlainDisplayName(original,
                Component.translatable("different.translation.key").fallback("Aluminum Singularity")
                        .color(NamedTextColor.GRAY)),
                "Do not replace a translatable component with matching visible text");
        assertFalse(Barrel.samePlainDisplayName(original,
                Component.text("", NamedTextColor.RED).append(original)),
                "Do not normalize styled wrappers");
    }

    @Test
    void minecraftItemItalicFalseIsNotEquivalentToUnset() {
        Component explicitNotItalic = Component.text("Stored item", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false);
        Component inheritedItalic = Component.text("Stored item", NamedTextColor.AQUA);
        assertFalse(Barrel.samePlainDisplayName(explicitNotItalic, inheritedItalic),
                "Item lore defaults to italic; explicit false changes rendering");
        assertFalse(Barrel.samePlainLore(List.of(explicitNotItalic), List.of(inheritedItalic)));
    }

    @Test
    void loreEventsColorAndLineCountMustRemainExact() {
        Component standard = Component.text("Stored: 731", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false);
        Component legacy = Component.text("").append(
                standard.decoration(TextDecoration.BOLD, false));
        assertTrue(Barrel.samePlainLore(List.of(legacy), List.of(standard)),
                "A purely empty wrapper and redundant false bold is cosmetic");
        assertFalse(Barrel.samePlainLore(List.of(legacy), List.of(
                Component.text("Stored: 731", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))));
        assertFalse(Barrel.samePlainLore(List.of(legacy), List.of(
                standard.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("hidden payload"))))));
        assertFalse(Barrel.samePlainLore(List.of(legacy), List.of(standard, standard)),
                "Never ignore an added or removed lore line");
    }

    @Test
    void missingNamesAreNotTreatedAsEquivalent() {
        assertFalse(Barrel.samePlainDisplayName(null, Component.text("Aluminum Singularity")));
        assertFalse(Barrel.samePlainDisplayName(Component.text("Aluminum Singularity"), null));
    }
}
