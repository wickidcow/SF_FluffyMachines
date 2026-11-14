package io.ncbpfluffybear.fluffymachines.items.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
