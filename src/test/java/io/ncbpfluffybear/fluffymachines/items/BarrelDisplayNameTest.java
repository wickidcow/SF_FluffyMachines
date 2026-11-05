package io.ncbpfluffybear.fluffymachines.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
    void missingNamesAreNotTreatedAsEquivalent() {
        assertFalse(Barrel.samePlainDisplayName(null, Component.text("Aluminum Singularity")));
        assertFalse(Barrel.samePlainDisplayName(Component.text("Aluminum Singularity"), null));
    }
}
