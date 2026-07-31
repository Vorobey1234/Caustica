package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueNoiseGeneratorTest {
    @Test
    void distributesEveryRankAcrossEachTileWithoutRasterOrdering() {
        ByteBuffer noise = BlueNoiseGenerator.generate();
        try {
            int min = 255;
            int max = 0;
            for (int tileY = 0; tileY < BlueNoiseGenerator.SIZE / 8; tileY++) {
                for (int tileX = 0; tileX < BlueNoiseGenerator.SIZE / 8; tileX++) {
                    int[] rankCounts = new int[64];
                    for (int y = 0; y < 8; y++) {
                        for (int x = 0; x < 8; x++) {
                            int flippedY = BlueNoiseGenerator.SIZE - 1 - (tileY * 8 + y);
                            int sample = noise.get(flippedY * BlueNoiseGenerator.SIZE + tileX * 8 + x) & 0xFF;
                            min = Math.min(min, sample);
                            max = Math.max(max, sample);
                            rankCounts[sample >> 2]++;
                        }
                    }
                    for (int rank = 0; rank < rankCounts.length; rank++) {
                        assertEquals(1, rankCounts[rank], "tile must contain rank " + rank + " exactly once");
                    }
                }
            }
            assertTrue(min < 8, "noise must reach the dark end of UNORM");
            assertTrue(max > 247, "noise must reach the bright end of UNORM");
        } finally {
            MemoryUtil.memFree(noise);
        }
    }
}
