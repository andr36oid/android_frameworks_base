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

import java.util.Locale;

/**
 * The numbers of the power menu stats strip as text, and how worrying each one is. The
 * formats and thresholds are the performance overlay's (PerfOverlay HudService), so both
 * read the same. Plain Java, so it can be tested without Android.
 */
final class PowerMenuStatsFormat {

    /** Plain value, drawn in the value color. */
    static final int LEVEL_NORMAL = 0;
    /** Green, e.g. charging. */
    static final int LEVEL_GOOD = 1;
    /** Amber, e.g. warm. */
    static final int LEVEL_OK = 2;
    /** Red, e.g. hot or almost empty. */
    static final int LEVEL_BAD = 3;

    private PowerMenuStatsFormat() {
    }

    /** " 23%": load right-aligned to 4 characters so the rest of the line stays put. */
    static String cpuValue(int percent) {
        return percent < 0 ? "  --" : pad(percent + "%", 4);
    }

    /** " 1.30GHz", empty if the clock is unknown. */
    static String cpuExtra(int mhz) {
        return mhz > 0 ? String.format(Locale.US, " %.2fGHz", mhz / 1000f) : "";
    }

    static String tempValue(float celsius) {
        return Float.isNaN(celsius) ? "--" : Math.round(celsius) + "°C";
    }

    static int tempLevel(float celsius) {
        return Float.isNaN(celsius) ? LEVEL_NORMAL
                : celsius >= 75 ? LEVEL_BAD : celsius >= 65 ? LEVEL_OK : LEVEL_NORMAL;
    }

    /** " max 61", the highest point of the graph, empty if there is none. */
    static String tempExtra(float maxCelsius) {
        return Float.isNaN(maxCelsius) ? "" : " max " + Math.round(maxCelsius);
    }

    static String batteryValue(int percent) {
        return percent < 0 ? "--" : percent + "%";
    }

    static int batteryLevel(int percent, boolean charging) {
        return percent < 0 ? LEVEL_NORMAL
                : charging ? LEVEL_GOOD : percent <= 15 ? LEVEL_BAD : LEVEL_NORMAL;
    }

    /** " -1.9W", + while charging, empty if the power is unknown. */
    static String batteryExtra(float watts, boolean charging) {
        return Float.isNaN(watts) ? ""
                : String.format(Locale.US, " %s%.1fW", charging ? "+" : "-", watts);
    }

    static String ramValue(int percent) {
        return percent < 0 ? "--" : percent + "%";
    }

    static int ramLevel(int percent) {
        return percent >= 90 ? LEVEL_OK : LEVEL_NORMAL;
    }

    /** " 612/976M", empty if unknown. */
    static String ramExtra(int usedMb, int totalMb) {
        return usedMb < 0 || totalMb <= 0 ? "" : " " + usedMb + "/" + totalMb + "M";
    }

    /**
     * The scale of a graph as {min, max}. A NaN {@code min} or {@code max} follows the
     * values, keeping at least {@code minRange} between the two.
     */
    static float[] scale(float[] values, int count, float min, float max, float minRange) {
        float low = Float.NaN;
        float high = Float.NaN;
        for (int i = 0; i < count; i++) {
            final float v = values[i];
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
        return new float[] { scaleMin, scaleMax };
    }

    /** The highest value, NaN if there is none. */
    static float max(float[] values, int count) {
        float high = Float.NaN;
        for (int i = 0; i < count; i++) {
            final float v = values[i];
            if (!Float.isNaN(v) && (Float.isNaN(high) || v > high)) {
                high = v;
            }
        }
        return high;
    }

    private static String pad(String text, int width) {
        final StringBuilder padded = new StringBuilder();
        for (int i = text.length(); i < width; i++) {
            padded.append(' ');
        }
        return padded.append(text).toString();
    }
}
