package io.ncbpfluffybear.fluffymachines.objects;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cache-policy tests with explicit values; native machine operations are tested separately. */
class RecipeIndexCacheTest {
    private final AtomicInteger size = new AtomicInteger(2);
    private final AtomicInteger builds = new AtomicInteger();
    private final RecipeIndexCache<Integer, String> cache = new RecipeIndexCache<>(size::get, builds::incrementAndGet);

    @Test void buildsOnFirstDemand() {
        assertEquals(1, cache.current().index);
        assertEquals(1, builds.get());
    }
    @Test void unchangedSourceReusesIndexAndMachineResult() {
        var first = cache.current();
        first.results.put("world:1:2:3", "recipe");
        for (int i = 0; i < 10000; i++) {
            assertSame(first, cache.current());
            assertEquals("recipe", cache.current().results.get("world:1:2:3"));
        }
        assertEquals(1, builds.get());
    }
    @Test void addedRecipesInvalidateNegativeResults() {
        var old = cache.current();
        old.results.put("machine", "NO_RECIPE");
        size.set(4);
        var fresh = cache.current();
        assertNotSame(old, fresh);
        assertNull(fresh.results.get("machine"));
        assertEquals(2, fresh.index);
    }
    @Test void removedRecipesInvalidatePositiveResults() {
        var old = cache.current();
        old.results.put("machine", "removed-output");
        size.set(0);
        assertNull(cache.current().results.get("machine"));
    }
    @Test void initiallyEmptyRegistryAcceptsLateRegistration() {
        size.set(0);
        cache.current().results.put("machine", "NO_RECIPE");
        size.set(2);
        assertTrue(cache.current().results.isEmpty());
    }
    @Test void oldResolverCannotRepopulateNewGeneration() {
        var old = cache.current();
        size.set(4);
        var fresh = cache.current();
        old.results.put("machine", "obsolete");
        assertNull(fresh.results.get("machine"));
        assertSame(fresh, cache.current());
    }
    @Test void observedSizeReturningToPriorValueStillUsesNewGeneration() {
        var first = cache.current();
        size.set(4);
        var second = cache.current();
        size.set(2);
        var third = cache.current();
        assertNotSame(first, third);
        assertNotSame(second, third);
        assertEquals(3, builds.get());
    }
    @Test void clearingOneGridDoesNotInvalidateOtherMachines() {
        var current = cache.current();
        current.results.put("first", "one");
        current.results.put("second", "two");
        cache.invalidate("first");
        assertFalse(current.results.containsKey("first"));
        assertEquals("two", current.results.get("second"));
        assertSame(current, cache.current());
    }
    @Test void clearingBeforeAnyRecipeLookupDoesNotBuildIndex() {
        cache.invalidate("empty-machine");
        assertEquals(0, builds.get());
    }
    @Test void failedBuildDoesNotPublishPartialGeneration() {
        AtomicBoolean fail = new AtomicBoolean(false);
        var guarded = new RecipeIndexCache<Integer, String>(size::get, () -> {
            if (fail.get()) throw new IllegalStateException("broken provider");
            return size.get();
        });
        var old = guarded.current();
        old.results.put("machine", "existing");
        size.set(4);
        fail.set(true);
        assertThrows(IllegalStateException.class, guarded::current);
        size.set(2);
        assertSame(old, guarded.current());
        assertEquals("existing", old.results.get("machine"));
        fail.set(false);
        size.set(4);
        assertTrue(guarded.current().results.isEmpty());
    }
    @Test void nullIndexIsRejectedBeforePublication() {
        var invalid = new RecipeIndexCache<Object, String>(size::get, () -> null);
        assertThrows(NullPointerException.class, invalid::current);
    }
    @Test void independentCrafterTypesDoNotShareResults() {
        var other = new RecipeIndexCache<Integer, String>(size::get, builds::incrementAndGet);
        cache.current().results.put("same-location", "enhanced");
        assertNull(other.current().results.get("same-location"));
    }
    @Test void suppliersAreRequired() {
        assertThrows(NullPointerException.class, () -> new RecipeIndexCache<>(null, builds::incrementAndGet));
        assertThrows(NullPointerException.class, () -> new RecipeIndexCache<>(size::get, null));
    }
}
