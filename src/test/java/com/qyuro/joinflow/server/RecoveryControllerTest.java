package com.qyuro.joinflow.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryControllerTest {
    @Test
    void severeLoadImmediatelyProtectsAndGraduallyRestoresBudget() {
        RecoveryController controller = new RecoveryController();

        assertEquals(LoadState.EMERGENCY, controller.update(LoadState.EMERGENCY, 5));
        assertEquals(0.0, controller.recoveryFactor(), 0.0001);
        assertTrue(controller.recovering());

        for (int tick = 0; tick < 5; tick++) {
            assertEquals(LoadState.RECOVERY, controller.update(LoadState.NORMAL, 1));
            assertEquals((tick + 1) * 0.2, controller.recoveryFactor(), 0.0001);
        }

        assertFalse(controller.recovering());
        assertEquals(1.0, controller.recoveryFactor(), 0.0001);
        assertEquals(LoadState.NORMAL, controller.update(LoadState.NORMAL, 1));
    }

    @Test
    void renewedProtectedLoadRestartsTheRecoveryDelay() {
        RecoveryController controller = new RecoveryController();
        controller.update(LoadState.CRITICAL, 3);
        controller.update(LoadState.NORMAL, 3);
        controller.update(LoadState.NORMAL, 3);

        assertEquals(LoadState.PROTECTED, controller.update(LoadState.PROTECTED, 3));
        assertEquals(0.0, controller.recoveryFactor(), 0.0001);
        assertEquals(LoadState.RECOVERY, controller.update(LoadState.NORMAL, 3));
        assertEquals(0.0, controller.recoveryFactor(), 0.0001);
        controller.update(LoadState.NORMAL, 3);
        assertEquals(LoadState.RECOVERY, controller.update(LoadState.NORMAL, 3));
        assertEquals(0.2, controller.recoveryFactor(), 0.0001);
    }
}
