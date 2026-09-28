/*
 * Copyright (C) 2026 andr36oid
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

package com.android.server.policy;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The list of FN shortcuts that shows while FN is held. A small window in the middle of the
 * screen that never takes focus or input, so the game underneath keeps every button. Drawn in
 * software (system_server has no GL renderer), which is plenty for a few lines of text.
 * Only touched on the window manager policy's handler thread.
 */
final class FnShortcutHelp {
    private static final String TAG = "FnShortcutHelp";

    private static final int PANEL_COLOR = 0xE6101820;
    private static final int BORDER_COLOR = 0xFF2BB3A6;
    private static final int TITLE_COLOR = 0xFF5FE0D2;
    private static final int TEXT_COLOR = 0xFFF2F5F7;
    private static final int DIM_COLOR = 0xFF9AA7B0;
    private static final int BADGE_COLOR = 0xFF2A3A46;
    private static final int ON_COLOR = 0xFF7CE08A;
    private static final long FADE_IN_MS = 120;

    /** One line: the buttons after "FN +", what they do, and an optional state like "on". */
    static final class Row {
        final String[] keys;
        final String action;
        final String state;

        Row(String action, String state, String... keys) {
            this.keys = keys;
            this.action = action;
            this.state = state;
        }
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private View mView;

    FnShortcutHelp(Context uiContext) {
        mContext = uiContext;
        mWindowManager = uiContext.getSystemService(WindowManager.class);
    }

    boolean isShowing() {
        return mView != null;
    }

    void show(List<Row> rows, String footer) {
        hide();
        final View view = buildView(rows, footer);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        lp.setFitInsetsTypes(0);
        // Gone at once on release, so a screenshot right after never catches it
        lp.windowAnimations = 0;
        lp.setTitle("FnShortcutHelp");
        try {
            mWindowManager.addView(view, lp);
            mView = view;
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(FADE_IN_MS).start();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Couldn't show the FN shortcut list", e);
        }
    }

    void hide() {
        if (mView == null) {
            return;
        }
        final View view = mView;
        mView = null;
        view.animate().cancel();
        try {
            mWindowManager.removeViewImmediate(view);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Couldn't hide the FN shortcut list", e);
        }
    }

    private View buildView(List<Row> rows, String footer) {
        final LinearLayout panel = new LinearLayout(mContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(12), dp(18), dp(12));
        final GradientDrawable background = new GradientDrawable();
        background.setColor(PANEL_COLOR);
        background.setCornerRadius(dp(14));
        background.setStroke(Math.max(1, dp(1.5f)), BORDER_COLOR);
        panel.setBackground(background);

        final TextView title = text("FN shortcuts", 17, TITLE_COLOR);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, 0, 0, dp(6));
        panel.addView(title);

        for (Row row : rows) {
            panel.addView(buildRow(row));
        }

        if (footer != null) {
            final TextView hint = text(footer, 12, DIM_COLOR);
            hint.setPadding(0, dp(8), 0, 0);
            panel.addView(hint);
        }
        return panel;
    }

    private View buildRow(Row row) {
        final LinearLayout line = new LinearLayout(mContext);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(3), 0, dp(3));

        final LinearLayout keys = new LinearLayout(mContext);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.addView(badge("FN"));
        keys.addView(joiner("+"));
        for (int i = 0; i < row.keys.length; i++) {
            if (i > 0) {
                keys.addView(joiner("/"));
            }
            keys.addView(badge(row.keys[i]));
        }
        // A column of its own, so the actions line up
        line.addView(keys, new LinearLayout.LayoutParams(dp(150),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        line.addView(text(row.action, 14, TEXT_COLOR));
        if (row.state != null) {
            final TextView state = text(row.state, 12, ON_COLOR);
            state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            state.setPadding(dp(8), 0, 0, 0);
            line.addView(state);
        }
        return line;
    }

    private TextView badge(String label) {
        final TextView badge = text(label, 12, TEXT_COLOR);
        badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final GradientDrawable background = new GradientDrawable();
        background.setColor(BADGE_COLOR);
        background.setCornerRadius(dp(5));
        badge.setBackground(background);
        badge.setPadding(dp(6), dp(1), dp(6), dp(2));
        return badge;
    }

    private TextView joiner(String label) {
        final TextView view = text(label, 12, DIM_COLOR);
        view.setPadding(dp(3), 0, dp(3), 0);
        return view;
    }

    private TextView text(String content, float sp, int color) {
        final TextView view = new TextView(mContext);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setSingleLine(true);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }
}
