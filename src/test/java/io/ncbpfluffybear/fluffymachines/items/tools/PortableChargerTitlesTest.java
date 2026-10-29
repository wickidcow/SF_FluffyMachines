package io.ncbpfluffybear.fluffymachines.items.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

class PortableChargerTitlesTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Test
    void canonicalGoldPortableChargerTitleKeepsExactOldLegacyText() {
        Component title = Component.text("Portable Charger", NamedTextColor.GOLD);
        assertEquals("\u00a76Portable Charger", LEGACY.serialize(title));
        assertTrue(PortableChargerTitles.matches(title));
    }

    @Test
    void substringRuleStillAcceptsExtendedPortableChargerTitles() {
        assertTrue(PortableChargerTitles.matches(Component.text("Portable Charger - battery")));
        assertTrue(PortableChargerTitles.matches(
                Component.text("Screen: ").append(Component.text("Portable Charger", NamedTextColor.GOLD))));
    }

    @Test
    void caseSensitivityAndUnrelatedInventoriesRemainRejected() {
        assertFalse(PortableChargerTitles.matches(Component.text("portable charger")));
        assertFalse(PortableChargerTitles.matches(Component.text("Portable charging station")));
        assertFalse(PortableChargerTitles.matches(Component.text("Rechargeable Items")));
    }

    @Test
    void legacyColorCodesInsidePhraseAreNotSilentlyRemovedBeforeMatching() {
        Component split = Component.text("Portable")
                .append(Component.text(" Charger", NamedTextColor.GOLD));
        assertEquals("Portable\u00a76 Charger", LEGACY.serialize(split));
        assertFalse(PortableChargerTitles.matches(split));
    }
}
