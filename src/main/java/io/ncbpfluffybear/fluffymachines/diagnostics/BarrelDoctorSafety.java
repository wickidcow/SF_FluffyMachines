package io.ncbpfluffybear.fluffymachines.diagnostics;

/** Pure, conservative policy for explicit repairs of historical Fluffy Barrel capacity. */
final class BarrelDoctorSafety {

    private BarrelDoctorSafety() {}

    /**
     * A zero *saved reserve* can still have physical output stock (for
     * example after Cargo accepts 64 + 31 items into the two output slots).
     * Return only a verified, identity-consistent output quantity. Never
     * report an unsafe/missing or mismatched template as an empty barrel.
     *
     * This diagnostic never rewrites an old item or transfers anything.
     */
    static Long confirmedOutputWithZeroReserve(
            Integer savedReserve,
            io.ncbpfluffybear.fluffymachines.items.BarrelContents<?> contents) {
        if (savedReserve == null || savedReserve != 0 || contents == null
                || !contents.safe() || contents.item() == null || contents.amount() <= 0) {
            return null;
        }
        return contents.amount();
    }

    /**
     * Capacity repair may only use an independently valid persisted reserve.
     * An empty display item is not evidence of an empty stored quantity.
     * Never infer zero from missing, malformed or negative saved counts.
     *
     * @return a capacity that can hold all verified reserved items, or null
     *         when the source is unverified and must remain read-only.
     */
    static Integer safeCapacityRepair(Integer verifiedStored, int configuredMaximum) {
        if (verifiedStored == null || verifiedStored < 0 || configuredMaximum <= 0) {
            return null;
        }
        return Math.max(verifiedStored, configuredMaximum);
    }
}
