/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.NONE;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Switch;

import com.android.systemui.R;
import com.android.systemui.settings.CurrentUserContextTracker;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Activity to select screen recording options
 */
public class ScreenRecordDialog extends Activity {
    private static final long DELAY_MS = 3000;
    private static final long INTERVAL_MS = 1000;
    private static final String TAG = "ScreenRecordDialog";

    private final RecordingController mController;
    private final CurrentUserContextTracker mCurrentUserContextTracker;
    private Switch mTapsSwitch;
    private Switch mAudioSwitch;
    private Spinner mOptions;
    private List<ScreenRecordingAudioSource> mModes;
    private boolean mHasMic;

    @Inject
    public ScreenRecordDialog(RecordingController controller,
            CurrentUserContextTracker currentUserContextTracker) {
        mController = controller;
        mCurrentUserContextTracker = currentUserContextTracker;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        // Inflate the decor view, so the attributes below are not overwritten by the theme.
        window.getDecorView();
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        window.addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS);
        window.setGravity(Gravity.TOP);
        setTitle(R.string.screenrecord_name);

        setContentView(R.layout.screen_record_dialog);

        Button cancelBtn = findViewById(R.id.button_cancel);
        cancelBtn.setOnClickListener(v -> {
            finish();
        });

        Button startBtn = findViewById(R.id.button_start);
        startBtn.setOnClickListener(v -> {
            requestScreenCapture();
            finish();
        });

        // Microphone choices only while a microphone is plugged in; the console has none.
        // Checked each time the dialog opens.
        mModes = new ArrayList<>();
        mHasMic = mController.hasMicrophone();
        if (mHasMic) {
            mModes.add(MIC);
        }
        mModes.add(INTERNAL);
        if (mHasMic) {
            mModes.add(MIC_AND_INTERNAL);
        }

        mAudioSwitch = findViewById(R.id.screenrecord_audio_switch);
        mTapsSwitch = findViewById(R.id.screenrecord_taps_switch);
        mOptions = findViewById(R.id.screen_recording_options);
        ArrayAdapter a = new ScreenRecordingAdapter(getApplicationContext(),
                android.R.layout.simple_spinner_dropdown_item,
                mModes);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mOptions.setAdapter(a);
        mOptions.setOnItemClickListenerInt((parent, view, position, id) -> {
            mAudioSwitch.setChecked(true);
        });

        // Last time's choices; the first time device audio is on, so clips have game sound
        mAudioSwitch.setChecked(mController.isAudioOn());
        mOptions.setSelection(Math.max(0, mModes.indexOf(mController.getAudioSource())));
        mTapsSwitch.setChecked(mController.isShowTaps());

        // Without a touchscreen the d-pad would start on the audio list, so A would open the
        // list instead of starting. Start on the Start button; up reaches the options.
        startBtn.requestFocus();
    }

    private void requestScreenCapture() {
        Context userContext = mCurrentUserContextTracker.getCurrentUserContext();
        boolean showTaps = mTapsSwitch.isChecked();
        ScreenRecordingAudioSource audioMode = mAudioSwitch.isChecked()
                ? (ScreenRecordingAudioSource) mOptions.getSelectedItem()
                : NONE;
        // Without a mic the list only has device audio. Record that this time, but keep a
        // saved microphone choice for when a mic is plugged in again.
        mController.saveOptions(mAudioSwitch.isChecked(),
                mHasMic ? (ScreenRecordingAudioSource) mOptions.getSelectedItem() : null,
                showTaps);
        PendingIntent startIntent = PendingIntent.getForegroundService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(
                        userContext, RESULT_OK,
                        audioMode.ordinal(), showTaps),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopIntent = PendingIntent.getService(userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(userContext),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mController.startCountdown(DELAY_MS, INTERVAL_MS, startIntent, stopIntent);
    }
}
