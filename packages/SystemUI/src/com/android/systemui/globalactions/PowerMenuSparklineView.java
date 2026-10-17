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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;

/**
 * A tiny line graph for the power menu stats strip, drawn the way the performance overlay
 * draws its text: one flat color per metric, a 1 px black drop shadow so it reads over any
 * background, and a see-through fill under the line in the same color. No frame and no
 * axis, only a faint baseline. The newest value sits at the right edge; the full width is
 * the whole history, so a young history only fills the right part. NaN values leave a gap.
 */
public class PowerMenuSparklineView extends View {

    /** Opacity of the fill under the line, out of 255. */
    private static final int FILL_ALPHA = 0x40;
    /** Opacity of stacked layers, out of 255: solid enough to tell the colors apart. */
    private static final int LAYER_ALPHA = 0x99;
    /** Opacity of the baseline, out of 255. */
    private static final int BASELINE_ALPHA = 0x4d;

    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBaselinePaint = new Paint();
    private final Path mLine = new Path();
    private final Path mFill = new Path();

    // Stacked layers under the line (see setLayers): the top of each layer, lowest first,
    // and its color. Without layers the area under the line is one see-through fill.
    private float[][] mLayers;
    private int[] mLayerColors;
    private final Paint mLayerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mLayer = new Path();

    private float[] mValues = new float[0];
    private int mCount;
    private int mCapacity = 1;
    private float mMin;
    private float mMax = 1;

    public PowerMenuSparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(Math.max(1f, density));
        mLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        // The overlay's text shadow: radius 2, offset 1/1, black
        mLinePaint.setShadowLayer(2, 1, 1, Color.BLACK);
        mFillPaint.setStyle(Paint.Style.FILL);
        mBaselinePaint.setStrokeWidth(1f);
        setColor(context.getColor(R.color.global_actions_stats_label));

        setFocusable(false);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
    }

    /** The metric's color: the line in full, the fill and the baseline see-through. */
    public void setColor(int color) {
        final int rgb = color & 0x00ffffff;
        if (mLinePaint.getColor() == (rgb | 0xff000000)) {
            return;
        }
        mLinePaint.setColor(rgb | 0xff000000);
        mFillPaint.setColor(rgb | (FILL_ALPHA << 24));
        mBaselinePaint.setColor(rgb | (BASELINE_ALPHA << 24));
        invalidate();
    }

    /**
     * Shows the first {@code count} entries of {@code values}, oldest first, on a scale from
     * {@code min} to {@code max}, with {@code capacity} points across the full width.
     */
    public void setValues(float[] values, int count, int capacity, float min, float max) {
        if (mValues.length < count) {
            mValues = new float[Math.max(count, capacity)];
        }
        System.arraycopy(values, 0, mValues, 0, count);
        mCount = count;
        mCapacity = Math.max(2, capacity);
        mMin = min;
        mMax = max > min ? max : min + 1;
        invalidate();
    }

    /**
     * Stacks colored layers under the graph, one graph split by kind: {@code tops[i]} holds
     * the top of layer i for each point (same order and scale as setValues), lowest layer
     * first, so each layer fills from the one below it up to its own top. A layer may reach
     * above the line, like the cache over the used RAM. Call after setValues; null clears.
     */
    public void setLayers(float[][] tops, int[] colors) {
        mLayers = tops;
        mLayerColors = colors;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float left = getPaddingLeft();
        final float top = getPaddingTop() + mLinePaint.getStrokeWidth();
        final float right = getWidth() - getPaddingRight();
        final float bottom = getHeight() - getPaddingBottom();
        canvas.drawLine(left, bottom - 0.5f, right, bottom - 0.5f, mBaselinePaint);
        if (mCount == 0 || right <= left || bottom <= top) {
            return;
        }
        final float step = (right - left) / (mCapacity - 1);
        final float height = bottom - top;
        mLine.rewind();
        mFill.rewind();
        boolean inRun = false;
        float runStartX = 0;
        float lastX = 0;
        for (int i = 0; i < mCount; i++) {
            final float value = mValues[i];
            final float x = right - (mCount - 1 - i) * step;
            if (Float.isNaN(value)) {
                if (inRun) {
                    closeFill(runStartX, lastX, bottom);
                    inRun = false;
                }
                continue;
            }
            final float clamped = Math.max(mMin, Math.min(mMax, value));
            final float y = bottom - (clamped - mMin) / (mMax - mMin) * height;
            if (!inRun) {
                mLine.moveTo(x, y);
                mFill.moveTo(x, bottom);
                mFill.lineTo(x, y);
                runStartX = x;
                inRun = true;
            } else {
                mLine.lineTo(x, y);
                mFill.lineTo(x, y);
            }
            lastX = x;
        }
        if (inRun) {
            closeFill(runStartX, lastX, bottom);
        }
        if (mLayers != null) {
            drawLayers(canvas, right, bottom, step, height);
        } else {
            canvas.drawPath(mFill, mFillPaint);
        }
        canvas.drawPath(mLine, mLinePaint);
    }

    /** Each layer as a band from the top of the one below (or the bottom) to its own top. */
    private void drawLayers(Canvas canvas, float right, float bottom, float step, float height) {
        for (int layer = 0; layer < mLayers.length; layer++) {
            final float[] tops = mLayers[layer];
            final float[] lows = layer == 0 ? null : mLayers[layer - 1];
            mLayerPaint.setColor((mLayerColors[layer] & 0x00ffffff) | (LAYER_ALPHA << 24));
            int i = 0;
            while (i < mCount) {
                // One run of points where this layer and the one below are known
                while (i < mCount && !known(tops, lows, i)) {
                    i++;
                }
                final int first = i;
                while (i < mCount && known(tops, lows, i)) {
                    i++;
                }
                if (i - first < 1) {
                    continue;
                }
                mLayer.rewind();
                for (int j = first; j < i; j++) {
                    final float x = right - (mCount - 1 - j) * step;
                    final float y = yOf(tops[j], bottom, height);
                    if (j == first) {
                        mLayer.moveTo(x, y);
                    } else {
                        mLayer.lineTo(x, y);
                    }
                }
                for (int j = i - 1; j >= first; j--) {
                    final float x = right - (mCount - 1 - j) * step;
                    mLayer.lineTo(x, lows == null ? bottom : yOf(lows[j], bottom, height));
                }
                mLayer.close();
                canvas.drawPath(mLayer, mLayerPaint);
            }
        }
    }

    private boolean known(float[] tops, float[] lows, int i) {
        return i < tops.length && !Float.isNaN(tops[i])
                && (lows == null || (i < lows.length && !Float.isNaN(lows[i])));
    }

    private float yOf(float value, float bottom, float height) {
        final float clamped = Math.max(mMin, Math.min(mMax, value));
        return bottom - (clamped - mMin) / (mMax - mMin) * height;
    }

    private void closeFill(float startX, float endX, float bottom) {
        mFill.lineTo(endX, bottom);
        mFill.lineTo(startX, bottom);
        mFill.close();
    }
}
