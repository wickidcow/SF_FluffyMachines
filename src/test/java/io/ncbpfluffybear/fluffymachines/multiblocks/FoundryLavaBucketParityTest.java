package io.ncbpfluffybear.fluffymachines.multiblocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Locks the historical Foundry bucket semantics before migrating Paper's
 * deprecated ItemStack.setType bridge. These tests intentionally do NOT claim
 * preservation of custom ItemMeta/PDC, inventory transaction persistence or
 * successful ignition after failures; those need native server fixtures.
 */
class FoundryLavaBucketParityTest {

    @Test
    void onlyLavaBucketBecomesBucketOnTheSameStack() {
        ItemStack held = new ItemStack(Material.LAVA_BUCKET);
        ItemStack originalReference = held;

        assertTrue(Foundry.consumeLavaBucketInPlace(held));
        assertSame(originalReference, held, "Never substitute a fresh stack");
        assertEquals(Material.BUCKET, originalReference.getType());
        assertEquals(1, held.getAmount());
    }

    @Test
    void nonLavaItemsAreNotConsumed() {
        for (Material type : new Material[] {
                Material.BUCKET, Material.WATER_BUCKET, Material.POWDER_SNOW_BUCKET,
                Material.MILK_BUCKET, Material.STONE, Material.AIR }) {
            ItemStack held = new ItemStack(type);
            assertFalse(Foundry.consumeLavaBucketInPlace(held), type.toString());
            assertEquals(type, held.getType(), "Non-lava stack must retain its type");
            assertEquals(1, held.getAmount(), "Non-lava stack amount must not change");
        }
    }

    @Test
    void noSecondConsumptionAfterFirstLavaBucketChange() {
        ItemStack held = new ItemStack(Material.LAVA_BUCKET);

        assertTrue(Foundry.consumeLavaBucketInPlace(held));
        assertFalse(Foundry.consumeLavaBucketInPlace(held));
        assertEquals(Material.BUCKET, held.getType());
        assertEquals(1, held.getAmount());
    }

    @Test
    void inPlaceConversionDoesNotChangeTheExistingStackAmount() {
        // Documents legacy behavior, not authorization to create stacked
        // vanilla lava buckets or a data-safety guarantee for malformed data.
        ItemStack held = new ItemStack(Material.LAVA_BUCKET, 2);

        assertTrue(Foundry.consumeLavaBucketInPlace(held));
        assertEquals(Material.BUCKET, held.getType());
        assertEquals(2, held.getAmount());
    }
}
