package io.ncbpfluffybear.fluffymachines.items.tools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * Preserve the legacy case-sensitive inventory-title substring guard while using
 * the non-deprecated Adventure InventoryView.title() API.
 */
final class PortableChargerTitles {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private PortableChargerTitles() {}

    static boolean matches(Component title) {
        return LEGACY.serialize(title).contains("Portable Charger");
    }
}
