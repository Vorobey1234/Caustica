package dev.comfyfluffy.caustica.rt.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtEntitiesCoordinatesTest {
    @Test
    void keepsSubBlockPlacementAcrossLargeRebaseBoundary() {
        assertEquals(0.25f, RtEntities.relativeCoordinate(30_000_128.25, 30_000_128));
    }

    @Test
    void keepsSubBlockMotionAtLargeWorldCoordinates() {
        assertEquals(0.25f, RtEntities.relativeCoordinate(30_000_128.5, 30_000_128.25));
    }
}
