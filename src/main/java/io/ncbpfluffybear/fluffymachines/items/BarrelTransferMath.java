package io.ncbpfluffybear.fluffymachines.items;

/** Item transfer accounting that never assumes the inventory accepted the offer. */
final class BarrelTransferMath {
    private BarrelTransferMath() {}

    /** Missing, corrupt and negative saved amounts are unsafe, never silently zero. */
    static int parseStored(String raw) {
        if (raw == null) return -1;
        try {
            int amount = Integer.parseInt(raw);
            return amount < 0 ? -1 : amount;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    /** @return number actually accepted, based on the inventory's returned remainder */
    static int accepted(int offered, int remainder) {
        if (offered <= 0 || remainder < 0 || remainder > offered) {
            throw new IllegalArgumentException("Invalid barrel transfer result: offered=" + offered
                    + " remainder=" + remainder);
        }
        return offered - remainder;
    }
}
