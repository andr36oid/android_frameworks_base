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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * A tiny line graph with a light fill below the line, for the power menu stats panel.
 * The newest value sits at the right edge; the full width is the whole history, so a young
 * history only fills the right part. NaN values leave a gap.
 */
public class PowerMenuSparklineView extends View {

    private final Paint mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBaselinePaint = new Paint();
    private final Path mLine = new Path();
    private final Path mFill = new Path();

    private float[] mValues = new float[0];
    private int mCount;
    private int mCapacity = 1;
    private float mMin;
    private float mMax = 1;

    public PowerMenuSparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.colorAccent, android.R.attr.textColorSecondary });
        final int accent = a.getColor(0, 0xff80deea);
        final int secondary = a.getColor(1, 0x99ffffff);
        a.recycle();

        final float density = getResources().getDisplayMetrics().density;
        mLinePaint.setStyle(Paint.Style.STROKE);
        mLinePaint.setStrokeWidth(1.5f * density);
        mLinePaint.setStrokeJoin(Paint.Join.ROUND);
        mLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mLinePaint.setColor(accent);
        mFillPaint.setStyle(Paint.Style.FILL);
        // Same hue, about a fifth of the opacity
        mFillPaint.setColor((accent & 0x00ffffff) | 0x38000000);
        mBaselinePaint.setStrokeWidth(Math.max(1f, density / 2));
        mBaselinePaint.setColor((secondary & 0x00ffffff) | 0x30000000);

        setFocusable(false);
        setFocusableInTouchMode(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
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

    @Override
    protected void onDraw(Canvas canvas) {
        final float left = getPaddingLeft();
        final float top = getPaddingTop() + mLinePaint.getStrokeWidth();
        final float right = getWidth() - getPaddingRight();
        final float bottom = getHeight() - getPaddingBottom();
        canvas.drawLine(left, bottom, right, bottom, mBaselinePaint);
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
        canvas.drawPath(mFill, mFillPaint);
        canvas.drawPath(mLine, mLinePaint);
    }

    private void closeFill(float startX, float endX, float bottom) {
        mFill.lineTo(endX, bottom);
        mFill.lineTo(startX, bottom);
        mFill.close();
    }
}
