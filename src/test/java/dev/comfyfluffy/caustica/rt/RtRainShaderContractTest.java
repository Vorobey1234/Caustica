package dev.comfyfluffy.caustica.rt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class RtRainShaderContractTest {
    @Test
    void skyMissShadersDoNotProjectRainAcrossTheSky() throws IOException {
        for (String name : new String[] {"world.rmiss.slang", "world_amd.rmiss.slang"}) {
            String shader = Files.readString(Path.of("shaders", "world", name));
            assertFalse(shader.contains("rainStreaks("), name + " must not render angular rain streaks");
        }
    }
}
