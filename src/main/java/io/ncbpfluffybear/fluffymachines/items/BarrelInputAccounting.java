package io.ncbpfluffybear.fluffymachines.items;

/**
 * Read-only accounting for the void-returning BlockMenu.consumeItem API.
 * A caller must snapshot the input before consumption and compare the same
 * slot afterwards. This helper does not change inventory or item metadata.
 */
final class BarrelInputAccounting {
    private BarrelInputAccounting() {}

    /**
     * @param before amount in the slot before consumption
     * @param after amount remaining in that slot, or zero when empty
     * @param requested number requested from consumeItem
     * @param sameIdentity whether a nonempty remainder has exactly the same
     *                     item identity (including components and typed PDC)
     * @return verified amount consumed, or -1 if observation is unsafe
     */
    static int consumed(int before, int after, int requested, boolean sameIdentity) {
        if (before <= 0 || after < 0 || requested <= 0 || requested > before || after > before) {
            return -1;
        }
        if (after > 0 && !sameIdentity) {
            return -1;
        }
        int delta = before - after;
        return delta <= requested ? delta : -1;
    }
}
