package io.ncbpfluffybear.fluffymachines.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ElevatorTitleTest {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    @Test
    void originalTwentySixtyTwentyTickTimingIsPreserved() {
        Title.Times times = ElevatorTitle.times();
        assertEquals(Duration.ofSeconds(1), times.fadeIn());
        assertEquals(Duration.ofSeconds(3), times.stay());
        assertEquals(Duration.ofSeconds(1), times.fadeOut());
    }

    @Test
    void floorNameRetainsLegacyColorAndLiteralAmpersands() {
        Component title = ElevatorTitle.component("\u00a7fFloor \u00a7aGarden & Friends");
        assertEquals("Floor Garden & Friends", PlainTextComponentSerializer.plainText().serialize(title));
        assertTrue(LEGACY.serialize(title).contains("\u00a7aGarden & Friends"));
    }

    @Test
    void sendUpdatesTitleAndTimingButDoesNotReplaceExistingSubtitle() {
        List<TitlePart<?>> parts = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        Player player = (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[] {Player.class},
                (proxy, method, arguments) -> {
                    if (!method.getName().equals("sendTitlePart")) {
                        throw new AssertionError("Unexpected player call: " + method.getName());
                    }
                    parts.add((TitlePart<?>) arguments[0]);
                    values.add(arguments[1]);
                    return null;
                });

        ElevatorTitle.send(player, "\u00a7fUpper \u00a7bLanding");

        assertEquals(List.of(TitlePart.TIMES, TitlePart.TITLE), parts);
        assertSame(ElevatorTitle.times(), values.get(0));
        assertEquals("Upper Landing",
                PlainTextComponentSerializer.plainText().serialize((Component) values.get(1)));
        assertTrue(LEGACY.serialize((Component) values.get(1)).contains("\u00a7bLanding"));
    }
}
