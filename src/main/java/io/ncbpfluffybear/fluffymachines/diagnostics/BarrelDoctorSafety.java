package io.ncbpfluffybear.fluffymachines.diagnostics;

/** Pure, conservative policy for explicit repairs of historical Fluffy Barrel capacity. */
final class BarrelDoctorSafety {

    private BarrelDoctorSafety() {}

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
