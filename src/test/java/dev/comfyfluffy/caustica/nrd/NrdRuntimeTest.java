package dev.comfyfluffy.caustica.nrd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

final class NrdRuntimeTest {
    @Test
    void bundledRuntimeAndBridgeLoad() {
        assertNotNull(NrdRuntime.INSTANCE.acquire(),
                "NRD, NRI and the Caustica bridge must load and expose their FFM entry points");
    }
}
