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

import android.content.Context;
import android.content.res.TypedArray;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.Locale;

/**
 * Small card above the power menu buttons with what the performance overlay shows: CPU load
 * and clock with the GPU clock, SoC temperature, battery level with voltage, current and
 * power, and RAM, each with a graph of about the last 10 minutes.
 *
 * {@link PowerMenuStatsSampler} keeps the history in the background; while this view is
 * attached it updates every second. It never takes the focus, so the D-pad still moves
 * between the buttons only.
 */
public class PowerMenuStatsPanel extends LinearLayout implements PowerMenuStatsSampler.Listener {

    private Cell mCpu;
    private Cell mTemp;
    private Cell mBattery;
    private Cell mRam;
    private int mLabelColor;
    private float[] mBuffer = new float[PowerMenuStatsSampler.HISTORY_SIZE];
    private boolean mAttached;

    public PowerMenuStatsPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        final TypedArray a = context.obtainStyledAttributes(
                new int[] { android.R.attr.textColorSecondary });
        mLabelColor = a.getColor(0, 0x99ffffff);
        a.recycle();
    }

    /** Whether the panel is switched on, see PowerMenuStatsSampler.PROP_ENABLED. */
    public static boolean isSwitchedOn() {
        return PowerMenuStatsSampler.isEnabled();
    }

    /** Starts the background history, called once when SystemUI starts. */
    public static void startSampling(Context context) {
        PowerMenuStatsSampler.getInstance(context).start();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCpu = new Cell(findViewById(R.id.global_actions_stats_cpu));
        mTemp = new Cell(findViewById(R.id.global_actions_stats_temp));
        mBattery = new Cell(findViewById(R.id.global_actions_stats_bat));
        mRam = new Cell(findViewById(R.id.global_actions_stats_ram));
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        // Placeholders until the first reading, so the height doesn't jump
        showNumbers(PowerMenuStatsReader.empty(), Float.NaN);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateListening(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Still counts as attached during this call
        updateListening(false);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateListening(isAttachedToWindow());
    }

    /** Live updates only while attached and visible, e.g. not behind the app switcher. */
    private void updateListening(boolean attached) {
        final boolean listen = attached && isShown();
        if (listen == mAttached) {
            return;
        }
        mAttached = listen;
        final PowerMenuStatsSampler sampler = PowerMenuStatsSampler.getInstance(getContext());
        if (listen) {
            sampler.attach(this);
        } else {
            sampler.detach(this);
        }
    }

    @Override
    public void onStatsUpdated(PowerMenuStatsReader s, PowerMenuStatsHistory history) {
        if (!mAttached) {
            return;
        }
        final int capacity = history.getCapacity();
        // CPU and RAM in percent; temperature on a window of at least 10 degrees around
        // the values; power from 0 with at least 2 W of range, so noise stays flat
        showGraph(mCpu, history, PowerMenuStatsSampler.SERIES_CPU, capacity, 0f, 100f, 0f);
        final float maxTemp = showGraph(mTemp, history, PowerMenuStatsSampler.SERIES_TEMP,
                capacity, Float.NaN, Float.NaN, 10f);
        showGraph(mBattery, history, PowerMenuStatsSampler.SERIES_POWER, capacity,
                0f, Float.NaN, 2f);
        showGraph(mRam, history, PowerMenuStatsSampler.SERIES_RAM, capacity, 0f, 100f, 0f);
        showNumbers(s, maxTemp);
    }

    /**
     * Draws one series. A NaN {@code min} or {@code max} follows the values, keeping at
     * least {@code minRange} between the two. Returns the highest value, NaN if none.
     */
    private float showGraph(Cell cell, PowerMenuStatsHistory history, int series,
            int capacity, float min, float max, float minRange) {
        if (mBuffer.length < capacity) {
            mBuffer = new float[capacity];
        }
        final int count = history.copy(series, mBuffer);
        float low = Float.NaN;
        float high = Float.NaN;
        for (int i = 0; i < count; i++) {
            final float v = mBuffer[i];
            if (!Float.isNaN(v)) {
                low = Float.isNaN(low) || v < low ? v : low;
                high = Float.isNaN(high) || v > high ? v : high;
            }
        }
        final float scaleMin = !Float.isNaN(min) ? min
                : Float.isNaN(low) ? 0f : (float) Math.floor(low - minRange / 5);
        final float scaleMax = !Float.isNaN(max) ? max
                : Math.max(scaleMin + minRange,
                        Float.isNaN(high) ? 0f : high + (high - scaleMin) / 10);
        cell.graph.setValues(mBuffer, count, capacity, scaleMin, scaleMax);
        return high;
    }

    private void showNumbers(PowerMenuStatsReader s, float maxTemp) {
        final Context c = getContext();
        // CPU: load and clock, GPU clock below
        final SpannableStringBuilder cpu = label(R.string.global_actions_stats_cpu)
                .append(s.cpuPercent < 0 ? "--" : s.cpuPercent + "%");
        if (s.cpuMhz > 0) {
            cpu.append(String.format(Locale.US, " %.2fGHz", s.cpuMhz / 1000f));
        }
        mCpu.value.setText(cpu);
        mCpu.detail.setText(s.gpuMhz > 0
                ? c.getString(R.string.global_actions_stats_gpu) + " " + s.gpuMhz + "MHz" : "");

        // Temperature now, highest in the graph below
        mTemp.value.setText(label(R.string.global_actions_stats_temp)
                .append(Float.isNaN(s.tempC) ? "--" : Math.round(s.tempC) + "°C"));
        mTemp.detail.setText(Float.isNaN(maxTemp) ? ""
                : c.getString(R.string.global_actions_stats_temp_max,
                        Math.round(maxTemp) + "°C"));

        // Battery: level and power, voltage and current below; + charging, - discharging
        final String sign = s.charging ? "+" : "-";
        final SpannableStringBuilder battery = label(R.string.global_actions_stats_bat)
                .append(s.batteryPercent < 0 ? "--" : s.batteryPercent + "%");
        if (!Float.isNaN(s.batteryWatts)) {
            battery.append(String.format(Locale.US, " %s%.1fW", sign, s.batteryWatts));
        }
        mBattery.value.setText(battery);
        final StringBuilder electric = new StringBuilder();
        if (!Float.isNaN(s.batteryVolts)) {
            electric.append(String.format(Locale.US, "%.2fV", s.batteryVolts));
        }
        if (!Float.isNaN(s.batteryAmps)) {
            if (electric.length() > 0) {
                electric.append(' ');
            }
            electric.append(String.format(Locale.US, "%s%.2fA", sign, s.batteryAmps));
        }
        mBattery.detail.setText(electric);

        // RAM: share in use, used and total below
        mRam.value.setText(label(R.string.global_actions_stats_ram)
                .append(s.ramPercent < 0 ? "--" : s.ramPercent + "%"));
        mRam.detail.setText(s.ramTotalMb <= 0 ? ""
                : s.ramUsedMb + "/" + s.ramTotalMb + "MB");
    }

    /** The label in the secondary color and a space, the value follows in the primary one. */
    private SpannableStringBuilder label(int label) {
        final SpannableStringBuilder out = new SpannableStringBuilder(
                getContext().getString(label));
        out.setSpan(new ForegroundColorSpan(mLabelColor), 0, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out.append(' ');
    }

    private static final class Cell {
        final TextView value;
        final PowerMenuSparklineView graph;
        final TextView detail;

        Cell(View root) {
            value = root.findViewById(R.id.global_actions_stats_value);
            graph = root.findViewById(R.id.global_actions_stats_graph);
            detail = root.findViewById(R.id.global_actions_stats_detail);
        }
    }
}
