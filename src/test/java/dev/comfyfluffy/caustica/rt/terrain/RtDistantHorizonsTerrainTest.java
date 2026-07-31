package dev.comfyfluffy.caustica.rt.terrain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtDistantHorizonsTerrainTest {
    @Test
    void recognizesDistantHorizonsEmissiveMaterialIndices() {
        assertTrue(RtDistantHorizonsTerrain.isDhEmissiveMaterial(6), "lava must emit");
        assertTrue(RtDistantHorizonsTerrain.isDhEmissiveMaterial(15), "illuminated material must emit");
        assertFalse(RtDistantHorizonsTerrain.isDhEmissiveMaterial(12), "water is handled separately");
        assertFalse(RtDistantHorizonsTerrain.isDhEmissiveMaterial(0), "unknown remains ordinary terrain");
    }

    @Test
    void splitsBakedGlassCoverageAcrossEntryAndExitInterfaces() {
        for (float coverage : new float[]{0.06f, 0.24f, 0.50f, 0.72f}) {
            float interfaceAlpha = RtDistantHorizonsTerrain.dhGlassInterfaceAlpha(coverage);
            float recomposed = 1.0f - (1.0f - interfaceAlpha) * (1.0f - interfaceAlpha);
            assertEquals(coverage, recomposed, 1.0e-6f);
        }
        assertEquals(RtDistantHorizonsTerrain.dhGlassInterfaceAlpha(0.24f),
                RtDistantHorizonsTerrain.dhGlassInterfaceAlpha(1.0f), 1.0e-6f,
                "opaque alpha in DH's transparent VBO must use the clear-glass fallback");
    }

    @Test
    void excludesDhQuadFootprintsThatTouchVanillaTerrain() {
        assertFalse(RtDistantHorizonsTerrain.outsideVanillaSeam(-8, 8, -8, 8, 0, 0, 64),
                "a DH quad inside vanilla terrain must not be packed");
        assertFalse(RtDistantHorizonsTerrain.outsideVanillaSeam(64, 80, -8, 8, 0, 0, 64),
                "a seam-touching DH quad must not duplicate vanilla's boundary");
        assertFalse(RtDistantHorizonsTerrain.outsideVanillaSeam(56, 72, 56, 72, 0, 0, 64),
                "a quad crossing a seam corner must not be packed");
        assertTrue(RtDistantHorizonsTerrain.outsideVanillaSeam(65, 80, -8, 8, 0, 0, 64),
                "a DH quad wholly beyond vanilla terrain must remain");
    }
}
