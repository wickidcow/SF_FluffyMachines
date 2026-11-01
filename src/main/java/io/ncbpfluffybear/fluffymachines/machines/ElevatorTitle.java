package io.ncbpfluffybear.fluffymachines.machines;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.entity.Player;

/**
 * Sends only the elevator title and its original 20/60/20 tick timing.
 * The legacy call supplied a null subtitle; do not replace an existing subtitle
 * with an empty component when modernizing that call.
 */
final class ElevatorTitle {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Title.Times TIMES = Title.Times.times(
            Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(1));

    private ElevatorTitle() {}

    static Component component(String legacyTitle) {
        return LEGACY.deserialize(legacyTitle);
    }

    static Title.Times times() {
        return TIMES;
    }

    static void send(Player player, String legacyTitle) {
        player.sendTitlePart(TitlePart.TIMES, TIMES);
        player.sendTitlePart(TitlePart.TITLE, component(legacyTitle));
    }
}
