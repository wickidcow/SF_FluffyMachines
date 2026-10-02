package io.ncbpfluffybear.fluffymachines.objects;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Keeps the recipe index and per-machine results in the same generation.
 * The provider's existing size-based invalidation contract is retained.
 * Same-size in-place registry edits and concurrent provider mutation are not
 * made observable by this cache; the provider has no mutation-version API.
 */
final class RecipeIndexCache<I, C> {
    private final IntSupplier sourceSize;
    private final Supplier<I> indexFactory;
    private volatile Generation<I, C> generation;

    RecipeIndexCache(IntSupplier sourceSize, Supplier<I> indexFactory) {
        this.sourceSize = Objects.requireNonNull(sourceSize);
        this.indexFactory = Objects.requireNonNull(indexFactory);
    }

    Generation<I, C> current() {
        Generation<I, C> result = generation;
        if (result != null && result.sourceSize == sourceSize.getAsInt()) {
            return result;
        }
        synchronized (this) {
            result = generation;
            int size = sourceSize.getAsInt();
            if (result == null || result.sourceSize != size) {
                // Build fully before publication. A failed build leaves the old
                // generation intact; callers do not receive a partial index.
                result = new Generation<>(size, Objects.requireNonNull(indexFactory.get()));
                generation = result;
            }
            return result;
        }
    }

    void invalidate(String blockKey) {
        Generation<I, C> result = generation;
        if (result != null) {
            result.results.remove(blockKey);
        }
    }

    static final class Generation<I, C> {
        private final int sourceSize;
        final I index;
        final ConcurrentMap<String, C> results = new ConcurrentHashMap<>();

        private Generation(int sourceSize, I index) {
            this.sourceSize = sourceSize;
            this.index = index;
        }
    }
}
