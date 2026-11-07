package io.ncbpfluffybear.fluffymachines.multiblocks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Registry-independent legacy Foundry material baseline. Constructing a real
 * Paper ItemStack during plain JUnit is not supported without RegistryAccess.
 * In-place stack identity, amount, Slimefun PDC, Adventure name/lore and
 * metadata-preserving withType are verified by the disposable native Paper
 * lifecycle probe, not guessed from this unit test.
 */
class FoundryLavaBucketParityTest {

    @Test
    void lavaBucketBecomesOrdinaryBucket() {
        assertEquals(Material.BUCKET, Foundry.materialAfterIgnition(Material.LAVA_BUCKET));
    }

    @Test
    void allOtherBucketsAndNonBucketsRetainTheirMaterial() {
        for (Material material : new Material[] {
                Material.BUCKET, Material.WATER_BUCKET, Material.POWDER_SNOW_BUCKET,
                Material.MILK_BUCKET, Material.STONE, Material.AIR }) {
            assertEquals(material, Foundry.materialAfterIgnition(material),
                    "Non-lava item must not be consumed: " + material);
        }
    }

    @Test
    void convertedBucketNeverConvertsAgain() {
        Material first = Foundry.materialAfterIgnition(Material.LAVA_BUCKET);
        assertEquals(Material.BUCKET, first);
        assertEquals(first, Foundry.materialAfterIgnition(first));
    }
}
