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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * A thin strip along the top of the screen above the power menu, in the look of the
 * performance overlay: for CPU, temperature, battery and RAM a line of monospace text
 * (label in cyan, value in white, or green, amber or red when it matters) with a small
 * graph of about the last 10 minutes under it. No card and no frame, only a dark fade
 * behind it.
 *
 * {@link PowerMenuStatsSampler} keeps the history in the background; while this view is
 * attached it updates every second. It never takes the focus, so the D-pad still moves
 * between the buttons only.
 */
public class PowerMenuStatsPanel extends LinearLayout implements PowerMenuStatsSampler.Listener {

    private Cell mCpu;
    private Cell mTemp;
    private Cell mBattery;
    private Cell mGpu;
    private Cell mRam;
    private Cell mStorage;
    private View mDataCardView;
    private Cell mDataCard;
    private TextView mZram;
    private TextView mRamLegend;
    // The RAM graph's layers, lowest first: apps, kernel, the rest of the used RAM, cache
    private final int[] mRamColors;
    private float[][] mRamLayers = new float[4][PowerMenuStatsSampler.HISTORY_SIZE];
    private final int mLabelColor;
    private final int mValueColor;
    private final int mGoodColor;
    private final int mOkColor;
    private final int mBadColor;
    private float[] mBuffer = new float[PowerMenuStatsSampler.HISTORY_SIZE];
    private boolean mAttached;

    public PowerMenuStatsPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLabelColor = context.getColor(R.color.global_actions_stats_label);
        mValueColor = context.getColor(R.color.global_actions_stats_value);
        mGoodColor = context.getColor(R.color.global_actions_stats_good);
        mOkColor = context.getColor(R.color.global_actions_stats_ok);
        mBadColor = context.getColor(R.color.global_actions_stats_bad);
        mRamColors = new int[] {
                context.getColor(R.color.global_actions_stats_ram_apps),
                context.getColor(R.color.global_actions_stats_ram_kernel),
                context.getColor(R.color.global_actions_stats_ram_other),
                context.getColor(R.color.global_actions_stats_ram_cache),
        };
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
        mGpu = new Cell(findViewById(R.id.global_actions_stats_gpu));
        mRam = new Cell(findViewById(R.id.global_actions_stats_ram));
        mStorage = new Cell(findViewById(R.id.global_actions_stats_storage));
        mDataCardView = findViewById(R.id.global_actions_stats_datacard);
        mDataCard = new Cell(mDataCardView);
        mZram = findViewById(R.id.global_actions_stats_zram);
        mRamLegend = findViewById(R.id.global_actions_stats_ram_legend);
        showRamLegend();
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        // Placeholders until the first reading, so nothing jumps
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
        showGraph(mGpu, history, PowerMenuStatsSampler.SERIES_GPU, capacity, 0f, 100f, 0f);
        showGraph(mRam, history, PowerMenuStatsSampler.SERIES_RAM, capacity, 0f, 100f, 0f);
        showRamLayers(history, capacity);
        showGraph(mStorage, history, PowerMenuStatsSampler.SERIES_STORAGE, capacity,
                0f, 100f, 0f);
        if (s.hasDataCard) {
            showGraph(mDataCard, history, PowerMenuStatsSampler.SERIES_DATA_CARD, capacity,
                    0f, 100f, 0f);
        }
        showNumbers(s, maxTemp);
    }

    /** Draws one series, see PowerMenuStatsFormat.scale. Returns the highest value. */
    private float showGraph(Cell cell, PowerMenuStatsHistory history, int series,
            int capacity, float min, float max, float minRange) {
        if (mBuffer.length < capacity) {
            mBuffer = new float[capacity];
        }
        final int count = history.copy(series, mBuffer);
        final float[] scale = PowerMenuStatsFormat.scale(mBuffer, count, min, max, minRange);
        cell.graph.setValues(mBuffer, count, capacity, scale[0], scale[1]);
        return PowerMenuStatsFormat.max(mBuffer, count);
    }

    /**
     * One RAM graph split by kind: apps, the kernel and the rest stacked up to the used RAM
     * (the line, and the number shown), the cache in green above it, not counted as used.
     */
    private void showRamLayers(PowerMenuStatsHistory history, int capacity) {
        if (mRamLayers[0].length < capacity) {
            mRamLayers = new float[4][capacity];
        }
        history.copy(PowerMenuStatsSampler.SERIES_RAM_APPS, mRamLayers[0]);
        history.copy(PowerMenuStatsSampler.SERIES_RAM_KERNEL, mRamLayers[1]);
        history.copy(PowerMenuStatsSampler.SERIES_RAM, mRamLayers[2]);
        history.copy(PowerMenuStatsSampler.SERIES_RAM_CACHE, mRamLayers[3]);
        mRam.graph.setLayers(mRamLayers, mRamColors);
    }

    /** "apps kernel other cache", each word in its layer's color */
    private void showRamLegend() {
        final int[] words = {
                R.string.global_actions_stats_ram_apps, R.string.global_actions_stats_ram_kernel,
                R.string.global_actions_stats_ram_other, R.string.global_actions_stats_ram_cache,
        };
        final SpannableStringBuilder out = new SpannableStringBuilder();
        for (int i = 0; i < words.length; i++) {
            append(out, (i == 0 ? "" : " ") + getContext().getString(words[i]), mRamColors[i]);
        }
        mRamLegend.setText(out);
    }

    private void showNumbers(PowerMenuStatsReader s, float maxTemp) {
        // CPU  23% 1.30GHz
        show(mCpu, R.string.global_actions_stats_cpu,
                PowerMenuStatsFormat.cpuValue(s.cpuPercent),
                PowerMenuStatsFormat.LEVEL_NORMAL, PowerMenuStatsFormat.cpuExtra(s.cpuMhz));
        // TEMP 52°C max 61 (the highest point of the graph)
        show(mTemp, R.string.global_actions_stats_temp,
                PowerMenuStatsFormat.tempValue(s.tempC),
                PowerMenuStatsFormat.tempLevel(s.tempC), PowerMenuStatsFormat.tempExtra(maxTemp));
        // BAT 87% -1.9W, + while charging
        show(mBattery, R.string.global_actions_stats_bat,
                PowerMenuStatsFormat.batteryValue(s.batteryPercent),
                PowerMenuStatsFormat.batteryLevel(s.batteryPercent, s.charging),
                PowerMenuStatsFormat.batteryExtra(s.batteryWatts, s.charging));
        // GPU  40% 400MHz, under the CPU
        show(mGpu, R.string.global_actions_stats_gpu,
                PowerMenuStatsFormat.cpuValue(s.gpuPercent),
                PowerMenuStatsFormat.LEVEL_NORMAL, PowerMenuStatsFormat.gpuExtra(s.gpuMhz));
        // RAM 61% 612/976M
        show(mRam, R.string.global_actions_stats_ram,
                PowerMenuStatsFormat.ramValue(s.ramPercent),
                PowerMenuStatsFormat.ramLevel(s.ramPercent),
                PowerMenuStatsFormat.ramExtra(s.ramUsedMb, s.ramTotalMb));
        // SD   12% 3.4MB/s (how busy the card was, what it read and wrote together). With a
        // data card the card has a graph of its own, under the temperature.
        showStorage(mStorage, s.storageEmmc ? R.string.global_actions_stats_storage_emmc
                : R.string.global_actions_stats_storage, s.storagePercent,
                s.storageBytesPerSec, s.storageDirection);
        mDataCardView.setVisibility(s.hasDataCard ? VISIBLE : GONE);
        if (s.hasDataCard) {
            showStorage(mDataCard, R.string.global_actions_stats_storage, s.dataCardPercent,
                    s.dataCardBytesPerSec, s.dataCardDirection);
        }
        // ✓ ZRAM on, under the RAM graph; hidden (keeping its space) while there is no swap
        mZram.setVisibility(s.zramOn ? VISIBLE : INVISIBLE);
    }

    /**
     * One metric the way the overlay writes it: the label in the label color, the value in
     * the color of its level, the extra in the value color. The graph takes the level's
     * color too, or the label color while the value is unremarkable.
     */
    /** A disk: how busy, the speed, ↑ green while it mostly reads, ↓ red while it writes. */
    private void showStorage(Cell cell, int label, int percent, long bytesPerSec,
            int direction) {
        show(cell, label, PowerMenuStatsFormat.storageValue(percent),
                PowerMenuStatsFormat.storageLevel(percent),
                PowerMenuStatsFormat.storageExtra(bytesPerSec));
        final String arrow = PowerMenuStatsFormat.storageArrow(direction);
        if (!arrow.isEmpty() && bytesPerSec > 0) {
            final SpannableStringBuilder sd = new SpannableStringBuilder(cell.text.getText());
            append(sd, arrow, colorOf(PowerMenuStatsFormat.storageArrowLevel(direction)));
            cell.text.setText(sd);
        }
    }

    private void show(Cell cell, int label, String value, int level, String extra) {
        final int valueColor = colorOf(level);
        final SpannableStringBuilder out = new SpannableStringBuilder();
        append(out, getContext().getString(label) + " ", mLabelColor);
        append(out, value, valueColor);
        append(out, extra, mValueColor);
        cell.text.setText(out);
        cell.graph.setColor(level == PowerMenuStatsFormat.LEVEL_NORMAL
                ? mLabelColor : valueColor);
    }

    private int colorOf(int level) {
        switch (level) {
            case PowerMenuStatsFormat.LEVEL_GOOD:
                return mGoodColor;
            case PowerMenuStatsFormat.LEVEL_OK:
                return mOkColor;
            case PowerMenuStatsFormat.LEVEL_BAD:
                return mBadColor;
            default:
                return mValueColor;
        }
    }

    private static void append(SpannableStringBuilder out, String text, int color) {
        final int start = out.length();
        out.append(text);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static final class Cell {
        final TextView text;
        final PowerMenuSparklineView graph;

        Cell(View root) {
            text = root.findViewById(R.id.global_actions_stats_value);
            graph = root.findViewById(R.id.global_actions_stats_graph);
        }
    }
}
