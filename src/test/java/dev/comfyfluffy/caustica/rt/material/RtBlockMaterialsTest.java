package dev.comfyfluffy.caustica.rt.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtBlockMaterialsTest {
    @Test
    void infersRectangularAnimatedFrameHeight() {
        assertEquals(16, RtBlockMaterials.sampledFrameHeight(32, 64, 32, 16));
        assertEquals(32, RtBlockMaterials.sampledFrameHeight(64, 128, 32, 16));
    }

    @Test
    void clampsSingleFrameToAvailableImage() {
        assertEquals(24, RtBlockMaterials.sampledFrameHeight(48, 24, 16, 16));
    }

    @Test
    void rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> RtBlockMaterials.sampledFrameHeight(0, 16, 16, 16));
    }
}
