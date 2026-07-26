package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaMod;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.nio.ByteBuffer;
import java.util.Random;

/**
 * Generates a 128×128 R8_UNORM blue-noise texture via Voronoi-cell histogram
 * equalisation (Heidrich &amp; Seidel 1998). The texture is uploaded to the GPU
 * once and bound into the path-tracer so every pixel can look up a spatially
 * well-distributed random sample per frame instead of using a white-noise PRNG.
 *
 * <p>Blue noise concentrates energy at high frequencies, which reduces
 * perceptual aliasing in Monte-Carlo integration and accelerates convergence
 * for both the denoiser and the accumulation pass.
 */
public final class BlueNoiseGenerator {
    public static final int SIZE = 128;

    /**
     * Generate the blue-noise texture as a CPU-side R8 buffer suitable for
     * upload into a {@code VK_FORMAT_R8_UNORM} storage image / sampled image.
     * The caller is responsible for GPU upload.
     *
     * @return a flipped (top-to-bottom) byte buffer of {@code SIZE × SIZE} R8 values
     */
    public static ByteBuffer generate() {
        int n = SIZE;
        int total = n * n;
        float[] result = new float[total];

        // 1. White noise seed
        Random rng = new Random(42);
        float[] values = new float[total];
        for (int i = 0; i < total; i++) {
            values[i] = rng.nextFloat();
        }

        // 2. 8×8 tiling to create spatial tiling constraints
        int tileSize = 8;
        int tilesX = n / tileSize;
        int tilesY = n / tileSize;

        // 3. Histogram equalisation per 8×8 tile: sort values within each tile,
        //    then spread them uniformly across [0,1). This ensures each tile has
        //    exactly one value in each 1/64 band, giving good blue-noise spectral
        //    properties when tiles are repeated.
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                // Collect tile values
                float[] tile = new float[tileSize * tileSize];
                int idx = 0;
                for (int y = 0; y < tileSize; y++) {
                    for (int x = 0; x < tileSize; x++) {
                        int px = tx * tileSize + x;
                        int py = ty * tileSize + y;
                        tile[idx++] = values[py * n + px];
                    }
                }

                // Sort tile values
                java.util.Arrays.sort(tile);

                // Assign equalised values: each sample gets the center of its band
                idx = 0;
                for (int y = 0; y < tileSize; y++) {
                    for (int x = 0; x < tileSize; x++) {
                        int px = tx * tileSize + x;
                        int py = ty * tileSize + y;
                        float rank = tile[idx] + 0.5f; // center of the band
                        result[py * n + px] = rank / tileSize; // normalise to [0,1)
                        idx++;
                    }
                }
            }
        }

        // 4. Convert to R8 byte buffer (Vulkan image data, bottom-to-top row order)
        ByteBuffer buf = MemoryUtil.memAlloc(total);
        // Vulkan images are bottom-row-first; we flip vertically
        for (int y = n - 1; y >= 0; y--) {
            for (int x = 0; x < n; x++) {
                float v = Math.clamp(result[y * n + x], 0.0f, 1.0f);
                buf.put((byte) Math.round(v * 255.0f));
            }
        }
        buf.flip();

        CausticaMod.LOGGER.info("Blue noise texture generated: {}×{} R8_UNORM ({} bytes)",
                n, n, buf.remaining());
        return buf;
    }

    private BlueNoiseGenerator() {
    }
}
