package com.android.systemui.power;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Plain Java tests for the voltage advisory, no Android needed. */
@RunWith(JUnit4.class)
public class LowVoltageStateTest {

    @Test
    public void startsHiddenUntilLowVoltageIsReported() {
        final LowVoltageState state = new LowVoltageState();
        assertFalse(state.isLow());
        state.update(3900, true, false);
        assertFalse(state.isLow());
        state.update(3501, true, false);
        assertFalse(state.isLow());
        state.update(3500, true, false);
        assertTrue(state.isLow());
    }

    @Test
    public void voltageRecoveryHasHysteresis() {
        final LowVoltageState state = new LowVoltageState();
        state.update(3400, true, false);
        assertTrue(state.isLow());
        state.update(3599, true, false);
        assertTrue(state.isLow());
        state.update(3600, true, false);
        assertFalse(state.isLow());
        state.update(3501, true, false);
        assertFalse(state.isLow());
        state.update(3499, true, false);
        assertTrue(state.isLow());
    }

    @Test
    public void pluggingInClearsAndRearmsWarning() {
        final LowVoltageState state = new LowVoltageState();
        state.update(3400, true, false);
        assertTrue(state.isLow());
        state.update(3400, true, true);
        assertFalse(state.isLow());
        state.update(3550, true, false);
        assertFalse(state.isLow());
        state.update(3400, true, false);
        assertTrue(state.isLow());
    }

    @Test
    public void missingBatteryOrVoltageClearsWarning() {
        final LowVoltageState state = new LowVoltageState();
        state.update(3400, false, false);
        assertFalse(state.isLow());
        state.update(3400, true, false);
        state.update(3400, false, false);
        assertFalse(state.isLow());
        state.update(3400, true, false);
        state.update(0, true, false);
        assertFalse(state.isLow());
        state.update(3400, true, false);
        state.update(-1, true, false);
        assertFalse(state.isLow());
    }
}
