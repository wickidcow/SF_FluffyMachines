package io.ncbpfluffybear.fluffymachines.items;

import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.ToLongFunction;

/** Read-only classification; ambiguous state is not permission to invent an item. */
public final class BarrelContents<T> {
    private final T item;
    private final long amount;
    private final boolean safe;
    private BarrelContents(T item, long amount, boolean safe) {
        this.item = item; this.amount = amount; this.safe = safe;
    }
    public T item() { return item; }
    public long amount() { return amount; }
    public boolean safe() { return safe; }
    public static <T> BarrelContents<T> inspect(long stored, T display, T first, T second,
            BiPredicate<T,T> same, ToLongFunction<T> quantity) {
        Objects.requireNonNull(same); Objects.requireNonNull(quantity);
        if (stored < 0 || (stored > 0 && display == null)) return new BarrelContents<>(null, 0, false);
        T identity = display;
        long total = stored;
        for (int index = 0; index < 2; index++) {
            T buffer = index == 0 ? first : second;
            if (buffer == null) continue;
            long count = quantity.applyAsLong(buffer);
            if (count <= 0) return new BarrelContents<>(null, 0, false);
            if (identity == null) identity = buffer;
            else if (!same.test(identity, buffer)) return new BarrelContents<>(null, 0, false);
            if (Long.MAX_VALUE - total < count) return new BarrelContents<>(null, 0, false);
            total += count;
        }
        return new BarrelContents<>(total == 0 ? null : identity, total, true);
    }
}
