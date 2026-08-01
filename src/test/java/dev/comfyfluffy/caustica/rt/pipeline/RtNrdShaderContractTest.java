package dev.comfyfluffy.caustica.rt.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtNrdShaderContractTest {
    @Test
    void relaxFiltersOnlyDiffuseEnergyWithoutChangingReblurPacking() throws IOException {
        String pack = Files.readString(Path.of("shaders", "display", "nrd_pack.comp"));
        String resolve = Files.readString(Path.of("shaders", "display", "nrd_resolve.comp"));

        assertAll(
                () -> assertTrue(pack.contains("diffuseShare")),
                () -> assertTrue(pack.contains("specularAlbedo")),
                () -> assertTrue(pack.contains("nonDiffuseRemainder")),
                () -> assertTrue(pack.contains("clampOverflow")),
                () -> assertTrue(pack.contains("imageStore(relaxResidual")),
                () -> assertTrue(pack.contains("vec3(250.0)")),
                () -> assertTrue(resolve.contains(
                        "color = color * diffuseAlbedo + imageLoad(relaxResidual, pixel).rgb;")),
                () -> assertFalse(resolve.contains("color *= materialFactor")),
                () -> assertFalse(pack.contains("RELAX_RADIANCE_SCALE")),
                () -> assertFalse(resolve.contains("RELAX_RADIANCE_SCALE")),
                () -> assertTrue(pack.contains("if (pc.method == 1)")),
                () -> assertTrue(pack.contains("linearToYCoCg(radiance)")),
                () -> assertTrue(resolve.contains("if (pc.method == 1) color = yCoCgToLinear(color);")));
    }
}
