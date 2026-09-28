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

/**
 * Fixed-size ring buffer of samples for the power menu graphs: one float per series and
 * sample, NaN where a value was missing. Once full, every new sample drops the oldest.
 *
 * Plain Java so it can be tested on the host. All methods are synchronized: the sampler
 * writes from a background thread and the panel reads on the main thread.
 */
final class PowerMenuStatsHistory {

    private final int mSeries;
    private final int mCapacity;
    // mValues[series][slot]
    private final float[][] mValues;
    // Next slot to write, and how many slots hold samples
    private int mNext;
    private int mSize;

    PowerMenuStatsHistory(int series, int capacity) {
        if (series <= 0 || capacity <= 0) {
            throw new IllegalArgumentException("series and capacity must be positive");
        }
        mSeries = series;
        mCapacity = capacity;
        mValues = new float[series][capacity];
    }

    int getCapacity() {
        return mCapacity;
    }

    synchronized int size() {
        return mSize;
    }

    /** Adds one sample, {@code values} holds one value per series. */
    synchronized void add(float... values) {
        for (int s = 0; s < mSeries; s++) {
            mValues[s][mNext] = s < values.length ? values[s] : Float.NaN;
        }
        mNext = (mNext + 1) % mCapacity;
        if (mSize < mCapacity) {
            mSize++;
        }
    }

    synchronized void clear() {
        mNext = 0;
        mSize = 0;
    }

    /**
     * Copies one series into {@code out}, oldest first, and returns how many values it copied.
     * If {@code out} is shorter than the history, it gets the newest values.
     */
    synchronized int copy(int series, float[] out) {
        final int count = Math.min(mSize, out.length);
        // Oldest of the samples that fit
        int slot = (mNext - count + mCapacity) % mCapacity;
        for (int i = 0; i < count; i++) {
            out[i] = mValues[series][slot];
            slot = (slot + 1) % mCapacity;
        }
        return count;
    }

    /**
     * Averages the samples taken between two history points, ignoring NaN. While the power
     * menu is open the sampler reads every second but keeps one history point per interval.
     */
    static final class Averager {
        private final float[] mSums;
        private final int[] mCounts;

        Averager(int series) {
            mSums = new float[series];
            mCounts = new int[series];
        }

        void add(float... values) {
            for (int s = 0; s < mSums.length && s < values.length; s++) {
                if (!Float.isNaN(values[s])) {
                    mSums[s] += values[s];
                    mCounts[s]++;
                }
            }
        }

        /** The averages, NaN for a series without values, and starts over. */
        float[] takeAverages() {
            final float[] averages = new float[mSums.length];
            for (int s = 0; s < mSums.length; s++) {
                averages[s] = mCounts[s] == 0 ? Float.NaN : mSums[s] / mCounts[s];
                mSums[s] = 0;
                mCounts[s] = 0;
            }
            return averages;
        }

        void reset() {
            takeAverages();
        }
    }
}
