/*
 * Copyright (C) 2026 The andr36oid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;

import com.android.internal.os.BackgroundThread;

/**
 * Keeps about ten minutes of history for the power menu stats panel, so the graphs are
 * already filled when the menu opens.
 *
 * Reads a few sysfs and procfs files every 5 seconds while the screen is on, and nothing
 * while it is off. No wake locks, no disk writes: the history is a small ring buffer in
 * memory. While the power menu is open it reads every second and hands the numbers to the
 * panel on the main thread.
 */
final class PowerMenuStatsSampler {

    /** Set by the device (device.mk) to offer the panel. */
    static final String PROP_AVAILABLE = "ro.andr36oid.power_menu_stats";
    /** 0 hides the panel and stops the sampling, 1 shows it. Unset follows PROP_AVAILABLE. */
    static final String PROP_ENABLED = "persist.sys.power_menu_stats";

    static final int SERIES_CPU = 0;
    static final int SERIES_TEMP = 1;
    static final int SERIES_POWER = 2;
    static final int SERIES_RAM = 3;
    private static final int SERIES_COUNT = 4;

    static final long HISTORY_INTERVAL_MS = 5000;
    private static final long LIVE_INTERVAL_MS = 1000;
    /** 120 points of 5 seconds, 10 minutes */
    static final int HISTORY_SIZE = 120;

    /** Gets the numbers while the power menu is open, on the main thread. */
    interface Listener {
        void onStatsUpdated(PowerMenuStatsReader stats, PowerMenuStatsHistory history);
    }

    private static PowerMenuStatsSampler sInstance;

    private final Context mContext;
    private final Handler mHandler;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final PowerMenuStatsHistory mHistory =
            new PowerMenuStatsHistory(SERIES_COUNT, HISTORY_SIZE);

    // Only touched on the background thread
    private PowerMenuStatsReader mReader;
    private final PowerMenuStatsHistory.Averager mAverager =
            new PowerMenuStatsHistory.Averager(SERIES_COUNT);
    private boolean mScreenOn;
    private boolean mLive;
    private long mLastHistoryTime;

    // Only touched on the main thread
    private boolean mStarted;
    private Listener mListener;

    private final Runnable mTick = this::tick;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Delivered on mHandler
            setScreenOn(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
        }
    };

    static boolean isEnabled() {
        return SystemProperties.getBoolean(PROP_ENABLED,
                SystemProperties.getBoolean(PROP_AVAILABLE, false));
    }

    static synchronized PowerMenuStatsSampler getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PowerMenuStatsSampler(context.getApplicationContext() != null
                    ? context.getApplicationContext() : context);
        }
        return sInstance;
    }

    private PowerMenuStatsSampler(Context context) {
        mContext = context;
        mHandler = BackgroundThread.getHandler();
    }

    /** Starts sampling in the background, if the panel is switched on. Main thread. */
    void start() {
        if (mStarted || !isEnabled()) {
            return;
        }
        mStarted = true;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, filter, null, mHandler);
        final boolean interactive = mContext.getSystemService(PowerManager.class).isInteractive();
        mHandler.post(() -> setScreenOn(interactive));
    }

    /** Starts reading every second and reporting to {@code listener}. Main thread. */
    void attach(Listener listener) {
        // Also covers the panel being switched on after boot
        start();
        mListener = listener;
        mHandler.post(() -> {
            mLive = true;
            mHandler.removeCallbacks(mTick);
            tick();
        });
    }

    /** Back to sampling every 5 seconds in the background. Main thread. */
    void detach(Listener listener) {
        if (mListener != listener) {
            return;
        }
        mListener = null;
        mHandler.post(() -> {
            mLive = false;
            mHandler.removeCallbacks(mTick);
            if (mScreenOn) {
                final long wait = mLastHistoryTime + HISTORY_INTERVAL_MS
                        - SystemClock.elapsedRealtime();
                mHandler.postDelayed(mTick, Math.max(0, wait));
            }
        });
    }

    private void setScreenOn(boolean on) {
        if (on == mScreenOn) {
            return;
        }
        mScreenOn = on;
        mHandler.removeCallbacks(mTick);
        if (on) {
            tick();
        } else {
            // The CPU share over the time the screen was off means nothing, and a gap in
            // the graphs shows the pause
            if (mReader != null) {
                mReader.reset();
            }
            mAverager.reset();
            if (mHistory.size() > 0) {
                mHistory.add(Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            }
            mLastHistoryTime = 0;
        }
    }

    private void tick() {
        if (!mScreenOn && !mLive) {
            return;
        }
        if (!mLive && !isEnabled()) {
            // Switched off at runtime; the receiver stays and checks again next time
            return;
        }
        if (mReader == null) {
            // Looks the sysfs nodes up once
            mReader = new PowerMenuStatsReader();
        }
        final PowerMenuStatsReader r = mReader;
        r.sample();
        mAverager.add(r.cpuPercent < 0 ? Float.NaN : r.cpuPercent, r.tempC,
                r.batteryWatts, r.ramPercent < 0 ? Float.NaN : r.ramPercent);
        final long now = SystemClock.elapsedRealtime();
        // A little slack so a live second landing just before the mark still counts
        if (mLastHistoryTime == 0 || now - mLastHistoryTime >= HISTORY_INTERVAL_MS - 500) {
            mHistory.add(mAverager.takeAverages());
            mLastHistoryTime = now;
        }
        if (mLive) {
            final PowerMenuStatsReader snapshot = r.snapshot();
            mMainHandler.post(() -> {
                final Listener listener = mListener;
                if (listener != null) {
                    listener.onStatsUpdated(snapshot, mHistory);
                }
            });
        }
        mHandler.postDelayed(mTick, mLive ? LIVE_INTERVAL_MS : HISTORY_INTERVAL_MS);
    }
}
