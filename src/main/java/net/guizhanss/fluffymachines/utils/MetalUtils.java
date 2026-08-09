package net.guizhanss.fluffymachines.utils;

import java.util.Locale;

public final class MetalUtils {

    private MetalUtils() {
    }

    public static String getMetalName(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "IRON" -> "Iron";
            case "GOLD" -> "Gold";
            case "COPPER" -> "Copper";
            case "TIN" -> "Tin";
            case "SILVER" -> "Silver";
            case "LEAD" -> "Lead";
            case "ALUMINUM" -> "Aluminum";
            case "ZINC" -> "Zinc";
            case "MAGNESIUM" -> "Magnesium";
            default -> humanize(type);
        };
    }

    /**
     * Converts an enum/key-style value into a readable English name.
     */
    private static String humanize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("The string cannot be null");
        }

        String normalized = value
            .toLowerCase(Locale.ROOT)
            .replace(' ', '_')
            .replace('-', '_');

        StringBuilder result = new StringBuilder();

        for (String word : normalized.split("_+")) {
            if (word.isEmpty()) {
                continue;
            }

            if (!result.isEmpty()) {
                result.append(' ');
            }

            result.append(Character.toUpperCase(word.charAt(0)));

            if (word.length() > 1) {
                result.append(word.substring(1));
            }
        }

        return result.toString();
    }
}
