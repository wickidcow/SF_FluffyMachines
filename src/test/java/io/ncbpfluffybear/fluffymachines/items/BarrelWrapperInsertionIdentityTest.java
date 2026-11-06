package io.ncbpfluffybear.fluffymachines.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Barrel identity must not flatten metadata carried by a visually empty
 * Adventure root. These checks intentionally do not touch stored quantities.
 */
class BarrelWrapperInsertionIdentityTest {
    @Test
    void emptyRootInsertionIsNotAnIgnorableLegacyWrapper() {
        Component item = Component.text("Aluminum Singularity", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        Component withInsertion = Component.text("").insertion("owner-private-token").append(item);
        assertFalse(Barrel.samePlainDisplayName(withInsertion, item),
                "An empty wrapper with insertion metadata must not be discarded");
        assertFalse(Barrel.samePlainLore(List.of(withInsertion), List.of(item)));
    }

    @Test
    void nestedEmptyWrappersRemainCosmeticOnlyWithoutMetadata() {
        Component item = Component.text("Stored: 731", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false);
        Component plain = Component.text("").append(Component.text("").append(item));
        Component tagged = Component.text("").append(
                Component.text("").insertion("storage-owner").append(item));
        assertTrue(Barrel.samePlainDisplayName(plain, item));
        assertFalse(Barrel.samePlainDisplayName(tagged, item));
    }

    @Test
    void loreLineOrderingAndDuplicateLinesArePartOfIdentity() {
        Component count = Component.text("Stored: 731", NamedTextColor.GRAY);
        Component owner = Component.text("Owner A", NamedTextColor.AQUA);
        assertFalse(Barrel.samePlainLore(List.of(count, owner), List.of(owner, count)));
        assertFalse(Barrel.samePlainLore(List.of(count, owner), List.of(count, count)));
    }
}
