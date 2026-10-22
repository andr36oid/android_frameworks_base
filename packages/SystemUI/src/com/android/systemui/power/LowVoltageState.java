package com.android.systemui.power;

/** The voltage warning's hysteresis, independent of the screen and the on/off switch. */
final class LowVoltageState {

    // An advisory near empty, not a kernel throttle threshold. The R36S battery table
    // starts at 3500 mV and the loaded shutdown threshold is 3300 mV. Clones vary.
    private static final int LOW_MV = 3500;
    private static final int RECOVER_MV = 3600;

    private boolean mLow;

    void update(int voltageMv, boolean present, boolean plugged) {
        if (!present || plugged || voltageMv <= 0) {
            mLow = false;
        } else if (voltageMv <= LOW_MV) {
            mLow = true;
        } else if (voltageMv >= RECOVER_MV) {
            mLow = false;
        }
    }

    boolean isLow() {
        return mLow;
    }
}
