package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtDenoiserPlanTest {
    @Test
    void bmfrUsesSelectedRealtimeResolution() {
        RtDenoiserPlan plan = RtDenoiserPlan.create(true,
                true, false, false, false, false, false);

        assertEquals(37, plan.renderPercent(37));
    }

    @Test
    void realtimeStagesCanRunTogether() {
        RtDenoiserPlan plan = RtDenoiserPlan.create(true,
                true, true, true, false, false, true);

        assertTrue(plan.bmfr());
        assertTrue(plan.nrd());
        assertTrue(plan.oidnRealtime());
        assertFalse(plan.dlssRr());
    }

    @Test
    void referenceOidnIsTerminalAndNativeResolution() {
        RtDenoiserPlan plan = RtDenoiserPlan.create(true,
                true, true, true, true, false, true);

        assertFalse(plan.bmfr());
        assertFalse(plan.nrd());
        assertFalse(plan.oidnRealtime());
        assertTrue(plan.oidnReferenceRequested());
        assertEquals(100, plan.renderPercent(25));
    }
}
