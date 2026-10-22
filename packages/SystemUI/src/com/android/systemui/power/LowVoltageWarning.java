package com.android.systemui.power;

import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * A small advisory over apps, using PowerUI's battery and screen broadcasts. No polling.
 * RK817 reports voltage but no CPU throttle reason. Its low power check handles shutdown;
 * Rockchip's low power status does not cap the CPU here. A cpufreq cap could also come from
 * a profile, underclock or heat, so the text says "may be throttled".
 */
final class LowVoltageWarning {

    static final String SETTING = "andr36oid_low_voltage_warning";

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final LowVoltageState mState = new LowVoltageState();
    private final ContentObserver mObserver;

    private boolean mEnabled;
    private boolean mScreenOn;
    private boolean mShown;
    private TextView mView;

    LowVoltageWarning(Context context, Handler handler) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                userSwitched();
            }
        };
    }

    void start(boolean screenOn) {
        mScreenOn = screenOn;
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(SETTING),
                false, mObserver, UserHandle.USER_ALL);
        userSwitched();
    }

    void userSwitched() {
        mEnabled = Settings.Secure.getIntForUser(mContext.getContentResolver(), SETTING,
                1, UserHandle.USER_CURRENT) != 0;
        updateVisibility();
    }

    void batteryChanged(Intent intent) {
        mState.update(intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false),
                intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 1) != 0);
        updateVisibility();
    }

    void setScreenOn(boolean on) {
        mScreenOn = on;
        updateVisibility();
    }

    void onConfigurationChanged() {
        if (mShown) {
            mWindowManager.removeView(mView);
            mShown = false;
        }
        mView = null;
        updateVisibility();
    }

    private void updateVisibility() {
        final boolean show = mEnabled && mScreenOn && mState.isLow();
        if (show == mShown) {
            return;
        }
        if (show) {
            if (mView == null) {
                mView = new TextView(mContext);
                mView.setText(R.string.andr36oid_low_voltage_warning);
                mView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                mView.setTextColor(Color.WHITE);
                mView.setIncludeFontPadding(false);
                mView.setShadowLayer(2, 1, 1, Color.BLACK);
                mView.setGravity(Gravity.RIGHT);
                mView.setMaxWidth(mContext.getResources().getDisplayMetrics().widthPixels - dp(8));
                final GradientDrawable background = new GradientDrawable();
                background.setColor(0xb3000000);
                background.setCornerRadius(dp(4));
                mView.setBackground(background);
                mView.setPadding(dp(5), dp(3), dp(5), dp(3));
            }
            mWindowManager.addView(mView, layoutParams());
        } else {
            mWindowManager.removeView(mView);
        }
        mShown = show;
    }

    private WindowManager.LayoutParams layoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("LowVoltageWarning");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.gravity = Gravity.TOP | Gravity.RIGHT;
        lp.x = dp(4);
        lp.y = dp(4);
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }
}
