package dev.comfyfluffy.caustica.oidn;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class OidnRuntimeTest {
    @Test
    void rtFilterActuallyChangesNoisyHalfImage() {
        OidnRuntime.Session session = OidnRuntime.INSTANCE.acquire();
        assertNotNull(session);
        int width = 64;
        int height = 64;
        long bytes = (long) width * height * 8L;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment color = arena.allocate(bytes, 64);
            MemorySegment albedo = arena.allocate(bytes, 64);
            MemorySegment normal = arena.allocate(bytes, 64);
            MemorySegment output = arena.allocate(bytes, 64);
            for (int i = 0; i < width * height; i++) {
                long p = (long) i * 8L;
                float noisy = (i * 1103515245 + 12345 & 7) == 0 ? 8f : 0.05f;
                putHalf3(color, p, noisy, noisy * 0.8f, noisy * 0.6f);
                putHalf3(albedo, p, 0.7f, 0.5f, 0.3f);
                putHalf3(normal, p, 0f, 1f, 0f);
            }
            MemorySegment filter = session.library().newRtFilter(session.device());
            try {
                session.library().setHalf3Image(filter, "color", color.address(), width, height);
                session.library().setHalf3Image(filter, "albedo", albedo.address(), width, height);
                session.library().setHalf3Image(filter, "normal", normal.address(), width, height);
                session.library().setHalf3Image(filter, "output", output.address(), width, height);
                session.library().setBool(filter, "hdr", true);
                session.library().setBool(filter, "cleanAux", true);
                session.library().setInt(filter, "quality", OidnLibrary.QUALITY_FAST);
                session.library().commitFilter(filter);
                OidnRuntime.INSTANCE.check("test commit");
                session.library().executeFilter(filter);
                OidnRuntime.INSTANCE.check("test execute");
                int changed = 0;
                for (long p = 0; p < bytes; p += 2L) {
                    if (color.get(ValueLayout.JAVA_SHORT, p) != output.get(ValueLayout.JAVA_SHORT, p)) {
                        changed++;
                    }
                }
                assertTrue(changed > width * height, "OIDN output must differ substantially from noisy input");
            } finally {
                session.library().releaseFilter(filter);
                OidnRuntime.INSTANCE.shutdown();
            }
        }
    }

    private static void putHalf3(MemorySegment image, long offset, float r, float g, float b) {
        image.set(ValueLayout.JAVA_SHORT, offset, Float.floatToFloat16(r));
        image.set(ValueLayout.JAVA_SHORT, offset + 2L, Float.floatToFloat16(g));
        image.set(ValueLayout.JAVA_SHORT, offset + 4L, Float.floatToFloat16(b));
        image.set(ValueLayout.JAVA_SHORT, offset + 6L, Float.floatToFloat16(1f));
    }
}
