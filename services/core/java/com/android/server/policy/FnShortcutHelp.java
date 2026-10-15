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
import android.content.res.TypedArray;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.Slog;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

import java.util.List;

/**
 * The list of FN shortcuts that shows while FN is held. A window across the screen, in the
 * look of the system's dark dialogs and its accent colour, with two columns when there are
 * many shortcuts; it never takes focus or input, so the game underneath keeps every button.
 * Drawn in software (system_server has no GL renderer), which is plenty for a few lines.
 * Only touched on the window manager policy's handler thread.
 */
final class FnShortcutHelp {
    private static final String TAG = "FnShortcutHelp";

    private static final long FADE_IN_MS = 120;
    /** more rows than this go into two columns */
    private static final int ONE_COLUMN_MAX = 4;

    /** One line: the buttons to press with FN, what they do, and an optional state like "on". */
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

    // the system's dark theme, read when the list opens (the accent can change)
    private int mSurface;
    private int mTextPrimary;
    private int mTextSecondary;
    private int mAccent;
    private float mCorner;

    FnShortcutHelp(Context uiContext) {
        mContext = uiContext;
        mWindowManager = uiContext.getSystemService(WindowManager.class);
    }

    boolean isShowing() {
        return mView != null;
    }

    void show(List<Row> rows, String footer) {
        hide();
        readTheme();
        final View view = buildView(rows, footer);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
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

    private void readTheme() {
        final Context themed = new ContextThemeWrapper(mContext,
                android.R.style.Theme_DeviceDefault);
        final TypedArray a = themed.obtainStyledAttributes(new int[] {
                android.R.attr.colorBackgroundFloating,
                android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary,
                android.R.attr.colorAccent,
                android.R.attr.dialogCornerRadius,
        });
        mSurface = a.getColor(0, 0xFF202124);
        mTextPrimary = a.getColor(1, 0xFFFFFFFF);
        mTextSecondary = a.getColor(2, 0xB3FFFFFF);
        mAccent = a.getColor(3, 0xFF8AB4F8);
        mCorner = a.getDimension(4, dp(8));
        a.recycle();
        // a bit see-through, like the volume dialog over a game
        mSurface = (mSurface & 0x00FFFFFF) | 0xF2000000;
    }

    private View buildView(List<Row> rows, String footer) {
        // the margins around the card: the window itself spans the screen
        final FrameLayout root = new FrameLayout(mContext);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        final LinearLayout panel = new LinearLayout(mContext);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(16), dp(20), dp(14));
        final GradientDrawable background = new GradientDrawable();
        background.setColor(mSurface);
        background.setCornerRadius(Math.max(mCorner, dp(8)));
        panel.setBackground(background);
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView title = text(mContext.getString(R.string.fn_help_title), 18,
                mTextPrimary);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, 0, 0, dp(10));
        panel.addView(title);

        final LinearLayout columns = new LinearLayout(mContext);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        final int columnCount = rows.size() > ONE_COLUMN_MAX ? 2 : 1;
        final int perColumn = (rows.size() + columnCount - 1) / columnCount;
        for (int c = 0; c < columnCount; c++) {
            final LinearLayout column = new LinearLayout(mContext);
            column.setOrientation(LinearLayout.VERTICAL);
            for (int i = c * perColumn; i < Math.min(rows.size(), (c + 1) * perColumn); i++) {
                column.addView(buildRow(rows.get(i)));
            }
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (c > 0) {
                lp.setMarginStart(dp(16));
            }
            columns.addView(column, lp);
        }
        panel.addView(columns);

        if (footer != null) {
            final TextView hint = text(footer, 12, mTextSecondary);
            hint.setSingleLine(false);
            hint.setPadding(0, dp(10), 0, 0);
            panel.addView(hint);
        }
        return root;
    }

    private View buildRow(Row row) {
        final LinearLayout line = new LinearLayout(mContext);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(4), 0, dp(4));

        final LinearLayout keys = new LinearLayout(mContext);
        keys.setOrientation(LinearLayout.HORIZONTAL);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < row.keys.length; i++) {
            final TextView chip = chip(row.keys[i]);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.setMarginStart(dp(4));
            }
            keys.addView(chip, lp);
        }
        // A column of its own, so the actions line up
        line.addView(keys, new LinearLayout.LayoutParams(dp(92),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView action = text(row.action, 14, mTextPrimary);
        action.setSingleLine(false);
        action.setMaxLines(2);
        action.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(action, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (row.state != null) {
            final TextView state = text(row.state, 13, mAccent);
            state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            state.setPadding(dp(8), 0, 0, 0);
            line.addView(state);
        }
        return line;
    }

    /** A button name in a rounded chip in the accent colour, like Android's chips. */
    private TextView chip(String label) {
        final TextView chip = text(label, 12, mAccent);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        final GradientDrawable background = new GradientDrawable();
        background.setColor((mAccent & 0x00FFFFFF) | 0x29000000);
        background.setCornerRadius(dp(10));
        chip.setBackground(background);
        chip.setPadding(dp(8), dp(2), dp(8), dp(3));
        return chip;
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
