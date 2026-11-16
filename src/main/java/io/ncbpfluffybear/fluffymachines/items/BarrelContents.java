package io.ncbpfluffybear.fluffymachines.items;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.ToLongFunction;

/**
 * Read-only classification; ambiguous state is not permission to invent an item.
 *
 * <p>A rejection reason is diagnostic only: the caller must never turn a
 * failure into a new reserve value, item identity, transfer or migration.
 */
public final class BarrelContents<T> {
    /** Fail-closed reasons, without exposing any potentially private item metadata. */
    public enum Rejection {
        NONE("Verified"),
        INVALID_SAVED_COUNT("Invalid saved barrel count"),
        MISSING_REGISTERED_ITEM("Saved reserve has no registered item"),
        NONPOSITIVE_OUTPUT_AMOUNT("Physical output has invalid quantity"),
        OUTPUT_IDENTITY_MISMATCH("Output item identity/metadata mismatch"),
        TOTAL_OVERFLOW("Combined storage quantity overflow");

        private final String explanation;

        Rejection(String explanation) {
            this.explanation = explanation;
        }

        public String explanation() {
            return explanation;
        }
    }

    private final T item;
    private final long amount;
    private final Rejection rejection;

    private BarrelContents(T item, long amount, Rejection rejection) {
        this.item = item;
        this.amount = amount;
        this.rejection = rejection;
    }

    public T item() { return item; }
    public long amount() { return amount; }
    public boolean safe() { return rejection == Rejection.NONE; }
    public Rejection rejection() { return rejection; }

    private static <T> BarrelContents<T> refused(Rejection reason) {
        return new BarrelContents<>(null, 0L, reason);
    }

    public static <T> BarrelContents<T> inspect(long stored, T display, T first, T second,
            BiPredicate<T,T> same, ToLongFunction<T> quantity) {
        Objects.requireNonNull(same);
        Objects.requireNonNull(quantity);
        if (stored < 0) return refused(Rejection.INVALID_SAVED_COUNT);
        if (stored > 0 && display == null) return refused(Rejection.MISSING_REGISTERED_ITEM);
        T identity = display;
        long total = stored;
        for (int index = 0; index < 2; index++) {
            T buffer = index == 0 ? first : second;
            if (buffer == null) continue;
            long count = quantity.applyAsLong(buffer);
            if (count <= 0) return refused(Rejection.NONPOSITIVE_OUTPUT_AMOUNT);
            if (identity == null) identity = buffer;
            else if (!same.test(identity, buffer)) return refused(Rejection.OUTPUT_IDENTITY_MISMATCH);
            if (Long.MAX_VALUE - total < count) return refused(Rejection.TOTAL_OVERFLOW);
            total += count;
        }
        return new BarrelContents<>(total == 0 ? null : identity, total, Rejection.NONE);
    }
}
