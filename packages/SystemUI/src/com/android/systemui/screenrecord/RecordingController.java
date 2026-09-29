/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.CountDownTimer;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.settings.CurrentUserContextTracker;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class to initiate a screen recording
 */
@Singleton
public class RecordingController
        implements CallbackController<RecordingController.RecordingStateChangeCallback> {
    private static final String TAG = "RecordingController";
    private static final String SYSUI_PACKAGE = "com.android.systemui";
    private static final String SYSUI_SCREENRECORD_LAUNCHER =
            "com.android.systemui.screenrecord.ScreenRecordDialog";
    /**
     * Sent by the system for FN + R2: starts a recording without the dialog, or stops or
     * cancels the one running. Only senders with STATUS_BAR_SERVICE (the system) are heard.
     */
    public static final String ACTION_TOGGLE = "com.android.systemui.screenrecord.TOGGLE";
    // Same countdown as the dialog. Long enough for the toast to be gone before recording.
    private static final long HOTKEY_DELAY_MS = 3000;
    private static final long HOTKEY_INTERVAL_MS = 1000;

    private boolean mIsStarting;
    private boolean mIsRecording;
    private PendingIntent mStopIntent;
    private CountDownTimer mCountDownTimer = null;
    private BroadcastDispatcher mBroadcastDispatcher;
    private final Context mContext;
    private final CurrentUserContextTracker mUserContextTracker;
    private Toast mHotkeyToast;

    private ArrayList<RecordingStateChangeCallback> mListeners = new ArrayList<>();

    @VisibleForTesting
    protected final BroadcastReceiver mUserChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStopIntent != null) {
                stopRecording();
            }
        }
    };

    /**
     * Create a new RecordingController
     */
    private final BroadcastReceiver mToggleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            toggleFromHotkey();
        }
    };

    @Inject
    public RecordingController(BroadcastDispatcher broadcastDispatcher, Context context,
            CurrentUserContextTracker userContextTracker) {
        mBroadcastDispatcher = broadcastDispatcher;
        mContext = context;
        mUserContextTracker = userContextTracker;
        // Not through the BroadcastDispatcher, which can't require a permission from the sender
        mContext.registerReceiver(mToggleReceiver, new IntentFilter(ACTION_TOGGLE),
                Manifest.permission.STATUS_BAR_SERVICE, null);
    }

    /**
     * The FN shortcut: cancel a countdown, stop a recording, or else start one with the
     * default options after the usual countdown. Says what happened in a toast, as the
     * status bar and the tile are usually hidden while a game runs.
     */
    @VisibleForTesting
    void toggleFromHotkey() {
        if (mIsStarting) {
            cancelCountdown();
            showHotkeyToast(R.string.screenrecord_hotkey_cancelled);
        } else if (mIsRecording) {
            stopRecording();
            showHotkeyToast(R.string.screenrecord_hotkey_stopped);
        } else {
            startFromHotkey();
            showHotkeyToast(R.string.screenrecord_hotkey_starting);
        }
    }

    private void startFromHotkey() {
        Context userContext = mUserContextTracker.getCurrentUserContext();
        PendingIntent startIntent = PendingIntent.getForegroundService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(userContext, Activity.RESULT_OK,
                        ScreenRecordingAudioSource.INTERNAL.ordinal(), false),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(userContext),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startCountdown(HOTKEY_DELAY_MS, HOTKEY_INTERVAL_MS, startIntent, stopIntent);
    }

    private void showHotkeyToast(int text) {
        if (mHotkeyToast != null) {
            mHotkeyToast.cancel();
        }
        mHotkeyToast = Toast.makeText(mContext, text, Toast.LENGTH_SHORT);
        mHotkeyToast.show();
    }

    /**
     * Get an intent to show screen recording options to the user.
     */
    public Intent getPromptIntent() {
        final ComponentName launcherComponent = new ComponentName(SYSUI_PACKAGE,
                SYSUI_SCREENRECORD_LAUNCHER);
        final Intent intent = new Intent();
        intent.setComponent(launcherComponent);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Start counting down in preparation to start a recording
     * @param ms Total time in ms to wait before starting
     * @param interval Time in ms per countdown step
     * @param startIntent Intent to start a recording
     * @param stopIntent Intent to stop a recording
     */
    public void startCountdown(long ms, long interval, PendingIntent startIntent,
            PendingIntent stopIntent) {
        mIsStarting = true;
        mStopIntent = stopIntent;

        mCountDownTimer = new CountDownTimer(ms, interval) {
            @Override
            public void onTick(long millisUntilFinished) {
                for (RecordingStateChangeCallback cb : mListeners) {
                    cb.onCountdown(millisUntilFinished);
                }
            }

            @Override
            public void onFinish() {
                mIsStarting = false;
                mIsRecording = true;
                for (RecordingStateChangeCallback cb : mListeners) {
                    cb.onCountdownEnd();
                }
                try {
                    startIntent.send();
                    IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
                    mBroadcastDispatcher.registerReceiver(mUserChangeReceiver, userFilter, null,
                            UserHandle.ALL);
                    Log.d(TAG, "sent start intent");
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Pending intent was cancelled: " + e.getMessage());
                }
            }
        };

        mCountDownTimer.start();
    }

    /**
     * Cancel a countdown in progress. This will not stop the recording if it already started.
     */
    public void cancelCountdown() {
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        } else {
            Log.e(TAG, "Timer was null");
        }
        mIsStarting = false;

        for (RecordingStateChangeCallback cb : mListeners) {
            cb.onCountdownEnd();
        }
    }

    /**
     * Check if the recording is currently counting down to begin
     * @return
     */
    public boolean isStarting() {
        return mIsStarting;
    }

    /**
     * Check if the recording is ongoing
     * @return
     */
    public synchronized boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Stop the recording
     */
    public void stopRecording() {
        try {
            if (mStopIntent != null) {
                mStopIntent.send();
            } else {
                Log.e(TAG, "Stop intent was null");
            }
            updateState(false);
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Error stopping: " + e.getMessage());
        }
        mBroadcastDispatcher.unregisterReceiver(mUserChangeReceiver);
    }

    /**
     * Update the current status
     * @param isRecording
     */
    public synchronized void updateState(boolean isRecording) {
        mIsRecording = isRecording;
        for (RecordingStateChangeCallback cb : mListeners) {
            if (isRecording) {
                cb.onRecordingStart();
            } else {
                cb.onRecordingEnd();
            }
        }
    }

    @Override
    public void addCallback(RecordingStateChangeCallback listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeCallback(RecordingStateChangeCallback listener) {
        mListeners.remove(listener);
    }

    /**
     * A callback for changes in the screen recording state
     */
    public interface RecordingStateChangeCallback {
        /**
         * Called when a countdown to recording has updated
         *
         * @param millisUntilFinished Time in ms remaining in the countdown
         */
        default void onCountdown(long millisUntilFinished) {}

        /**
         * Called when a countdown to recording has ended. This is a separate method so that if
         * needed, listeners can handle cases where recording fails to start
         */
        default void onCountdownEnd() {}

        /**
         * Called when a screen recording has started
         */
        default void onRecordingStart() {}

        /**
         * Called when a screen recording has ended
         */
        default void onRecordingEnd() {}
    }
}
