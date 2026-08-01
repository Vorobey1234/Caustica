package dev.comfyfluffy.caustica.rt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtAccumulationShaderContractTest {
    @Test
    void entitiesAndWaterUseClassIsolatedBoundedAccumulation() throws IOException {
        for (String name : new String[] {"world.rgen.slang", "world_amd.rgen.slang"}) {
            String shader = Files.readString(Path.of("shaders", "world", name));
            assertAll(name,
                    () -> assertTrue(shader.contains("static uint gv_historyClass")),
                    () -> assertTrue(shader.contains("material == MATERIAL_WATER ? 3u")),
                    () -> assertTrue(shader.contains("entitySurface ? 2u : 1u")),
                    () -> assertTrue(shader.contains("rawHistoryColor[pix]")),
                    () -> assertFalse(shader.contains("rawHistoryColor[historyPix]")),
                    () -> assertTrue(shader.contains("previousHistoryClass == gv_historyClass")),
                    () -> assertTrue(shader.contains("uint currentAlbedoKey =")),
                    () -> assertTrue(shader.contains("gv_historyClass | (currentAlbedoKey << 2u)")),
                    () -> assertTrue(shader.contains("previousAlbedoKey == currentAlbedoKey")),
                    () -> assertTrue(shader.contains("dynamicHistory ? 0.78 : 0.82")),
                    () -> assertTrue(shader.contains("dynamicHistory ? 0.065 : 0.055")),
                    () -> assertTrue(shader.contains("dynamicHistory ? 4.0 : 1024.0")));
        }
    }
}
