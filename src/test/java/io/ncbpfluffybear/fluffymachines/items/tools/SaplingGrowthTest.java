package io.ncbpfluffybear.fluffymachines.items.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

/** Locks the bonemeal-only modern sapling path, with no tree replacement or retry. */
class SaplingGrowthTest {

    @Test
    void appliesOnceWithThePlayersOriginalFacing() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<BlockFace> face = new AtomicReference<>();
        Block block = fakeBlock(true, calls, face);

        SaplingGrowth.applyModernBoneMeal(block, BlockFace.NORTH);

        assertEquals(1, calls.get());
        assertSame(BlockFace.NORTH, face.get());
    }

    @Test
    void doesNotRetryOrMutateSaplingWhenPaperReturnsFalse() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<BlockFace> face = new AtomicReference<>();
        Block block = fakeBlock(false, calls, face);

        SaplingGrowth.applyModernBoneMeal(block, BlockFace.UP);

        assertEquals(1, calls.get());
        assertSame(BlockFace.UP, face.get());
    }

    @Test
    void disabledExoticSaplingsCannotSpendAnyWater() {
        assertFalse(SaplingGrowth.canConsumeWater(true, 0.0D));
        assertFalse(SaplingGrowth.canConsumeWater(true, -1.0D));
        assertFalse(SaplingGrowth.canConsumeWater(true, Double.NaN));
        assertTrue(SaplingGrowth.canConsumeWater(true, 0.3D));
        assertTrue(SaplingGrowth.canConsumeWater(true, 1.0D));
    }

    @Test
    void ordinarySaplingsRetainBonemealPathEvenWithExoticDisabled() {
        assertTrue(SaplingGrowth.canConsumeWater(false, 0.0D));
    }

    @Test
    void exoticTreeMappingsUseRealBukkitTreeTypeNames() {
        assertEquals(TreeType.TREE, SaplingGrowth.getTreeFromSapling(Material.OAK_SAPLING));
        assertEquals(TreeType.REDWOOD, SaplingGrowth.getTreeFromSapling(Material.SPRUCE_SAPLING));
        assertEquals(TreeType.SMALL_JUNGLE, SaplingGrowth.getTreeFromSapling(Material.JUNGLE_SAPLING));
        assertEquals(TreeType.BIRCH, SaplingGrowth.getTreeFromSapling(Material.BIRCH_SAPLING));
        assertEquals(TreeType.CHERRY, SaplingGrowth.getTreeFromSapling(Material.CHERRY_SAPLING));
        assertEquals(TreeType.PALE_OAK, SaplingGrowth.getTreeFromSapling(Material.PALE_OAK_SAPLING));
        assertEquals(TreeType.MANGROVE, SaplingGrowth.getTreeFromSapling(Material.MANGROVE_PROPAGULE));
        assertEquals(TreeType.AZALEA, SaplingGrowth.getTreeFromSapling(Material.FLOWERING_AZALEA));
    }

    @Test
    void unsupportedMaterialIsRejectedInsteadOfThrowing() {
        assertNull(SaplingGrowth.getTreeFromSapling(Material.STONE));
        assertNull(SaplingGrowth.getTreeFromSapling(Material.AIR));
        assertNull(SaplingGrowth.getTreeFromSapling(null));
    }

    private static Block fakeBlock(boolean returnValue, AtomicInteger calls,
                                   AtomicReference<BlockFace> face) {
        return (Block) Proxy.newProxyInstance(Block.class.getClassLoader(),
            new Class<?>[] {Block.class}, (proxy, method, args) -> {
                if (method.getName().equals("applyBoneMeal") && method.getParameterCount() == 1) {
                    calls.incrementAndGet();
                    face.set((BlockFace) args[0]);
                    return returnValue;
                }
                throw new AssertionError("Unexpected block method: " + method.getName());
            });
    }
}
