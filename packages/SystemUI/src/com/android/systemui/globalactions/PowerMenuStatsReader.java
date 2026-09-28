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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Reads the numbers the power menu stats panel shows. It uses the same sysfs and procfs files
 * as the performance overlay app (device/gameconsole/common PerfOverlay, Stats.java), minus the
 * frame rate, which means nothing while the menu is up.
 *
 * Plain Java on purpose, so it can be tested on the host against a fake file tree.
 * Not thread safe: use it from one thread.
 */
final class PowerMenuStatsReader {

    static final String CPU_FREQ = "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq";

    /** -1 or NaN where a value couldn't be read. */
    int cpuPercent = -1;
    int cpuMhz = -1;
    int gpuMhz = -1;
    float tempC = Float.NaN;
    int batteryPercent = -1;
    boolean charging;
    float batteryVolts = Float.NaN;
    /** Always positive, the direction is in {@link #charging}. */
    float batteryAmps = Float.NaN;
    /** Always positive, the direction is in {@link #charging}. */
    float batteryWatts = Float.NaN;
    int ramUsedMb = -1;
    int ramTotalMb = -1;
    int ramPercent = -1;

    private final String mRoot;
    private final String mGpuFreqPath;
    private final String mTempPath;
    private final String mBatteryPath;

    private long mLastCpuBusy = -1;
    private long mLastCpuTotal;

    PowerMenuStatsReader() {
        this("");
    }

    /** @param root prefix for every path, "" on the device, a fake tree in tests */
    PowerMenuStatsReader(String root) {
        mRoot = root;
        mGpuFreqPath = findGpuFreq();
        mTempPath = findTemperature();
        mBatteryPath = findBattery();
    }

    /** A reader with nothing read yet, all values unknown. Looks up no files. */
    static PowerMenuStatsReader empty() {
        return new PowerMenuStatsReader("", null, null, null);
    }

    /** A copy of the current values, safe to hand to another thread. */
    PowerMenuStatsReader snapshot() {
        final PowerMenuStatsReader copy =
                new PowerMenuStatsReader(mRoot, mGpuFreqPath, mTempPath, mBatteryPath);
        copy.cpuPercent = cpuPercent;
        copy.cpuMhz = cpuMhz;
        copy.gpuMhz = gpuMhz;
        copy.tempC = tempC;
        copy.batteryPercent = batteryPercent;
        copy.charging = charging;
        copy.batteryVolts = batteryVolts;
        copy.batteryAmps = batteryAmps;
        copy.batteryWatts = batteryWatts;
        copy.ramUsedMb = ramUsedMb;
        copy.ramTotalMb = ramTotalMb;
        copy.ramPercent = ramPercent;
        return copy;
    }

    // For empty() and snapshot(): takes the paths, looks nothing up
    private PowerMenuStatsReader(String root, String gpuFreqPath, String tempPath,
            String batteryPath) {
        mRoot = root;
        mGpuFreqPath = gpuFreqPath;
        mTempPath = tempPath;
        mBatteryPath = batteryPath;
    }

    /** Forget the last CPU sample, e.g. after the screen was off. */
    void reset() {
        mLastCpuBusy = -1;
        cpuPercent = -1;
    }

    void sample() {
        sampleCpu(read(mRoot + "/proc/stat"));
        cpuMhz = positiveOrMinusOne(readInt(mRoot + CPU_FREQ, 1000));
        gpuMhz = mGpuFreqPath == null ? -1
                : positiveOrMinusOne(readInt(mGpuFreqPath, 1000000));
        final int milliC = mTempPath == null ? Integer.MIN_VALUE : readInt(mTempPath, 1);
        tempC = milliC == Integer.MIN_VALUE || milliC < 0 ? Float.NaN : milliC / 1000f;
        sampleBattery();
        sampleRam(read(mRoot + "/proc/meminfo"));
    }

    /** Busy share of all cores since the last sample, from the first line of /proc/stat. */
    void sampleCpu(String stat) {
        if (stat == null || !stat.startsWith("cpu ")) {
            cpuPercent = -1;
            return;
        }
        final int end = stat.indexOf('\n');
        final String[] fields = (end < 0 ? stat : stat.substring(0, end)).trim().split("\\s+");
        long total = 0;
        long idle = 0;
        try {
            // user nice system idle iowait irq softirq steal
            for (int i = 1; i < fields.length && i <= 8; i++) {
                final long value = Long.parseLong(fields[i]);
                total += value;
                if (i == 4 || i == 5) {
                    idle += value;
                }
            }
        } catch (NumberFormatException e) {
            cpuPercent = -1;
            return;
        }
        final long busy = total - idle;
        if (mLastCpuBusy >= 0 && total > mLastCpuTotal) {
            cpuPercent = (int) (100 * (busy - mLastCpuBusy) / (total - mLastCpuTotal));
            cpuPercent = Math.max(0, Math.min(100, cpuPercent));
        }
        mLastCpuBusy = busy;
        mLastCpuTotal = total;
    }

    private void sampleBattery() {
        if (mBatteryPath == null) {
            batteryPercent = -1;
            batteryVolts = batteryAmps = batteryWatts = Float.NaN;
            return;
        }
        batteryPercent = readInt(mBatteryPath + "capacity", 1);
        if (batteryPercent == Integer.MIN_VALUE) {
            batteryPercent = -1;
        }
        final String status = read(mBatteryPath + "status");
        charging = status != null && (status.startsWith("Charging") || status.startsWith("Full"));
        final int microAmps = readInt(mBatteryPath + "current_now", 1);
        final int microVolts = readInt(mBatteryPath + "voltage_now", 1);
        batteryAmps = microAmps == Integer.MIN_VALUE ? Float.NaN : Math.abs(microAmps / 1e6f);
        batteryVolts = microVolts == Integer.MIN_VALUE || microVolts <= 0
                ? Float.NaN : microVolts / 1e6f;
        batteryWatts = Float.isNaN(batteryAmps) || Float.isNaN(batteryVolts)
                ? Float.NaN : batteryAmps * batteryVolts;
    }

    /** Used is MemTotal - MemAvailable, like the performance overlay. */
    void sampleRam(String meminfo) {
        final long total = meminfoKb(meminfo, "MemTotal:");
        final long available = meminfoKb(meminfo, "MemAvailable:");
        if (total > 0 && available >= 0 && available <= total) {
            ramTotalMb = (int) (total / 1024);
            ramUsedMb = (int) ((total - available) / 1024);
            ramPercent = (int) (100 * (total - available) / total);
        } else {
            ramTotalMb = ramUsedMb = ramPercent = -1;
        }
    }

    static long meminfoKb(String meminfo, String key) {
        if (meminfo == null) {
            return -1;
        }
        final int start = meminfo.indexOf(key);
        if (start < 0) {
            return -1;
        }
        final int end = meminfo.indexOf('\n', start);
        final String line = meminfo.substring(start + key.length(),
                end < 0 ? meminfo.length() : end).trim();
        try {
            return Long.parseLong(line.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** cur_freq of the first devfreq device named like a GPU (ff400000.gpu on the RK3326). */
    private String findGpuFreq() {
        final File[] devices = new File(mRoot + "/sys/class/devfreq").listFiles();
        if (devices == null) {
            return null;
        }
        for (File device : devices) {
            if (device.getName().contains("gpu")) {
                return device.getPath() + "/cur_freq";
            }
        }
        return null;
    }

    /** The SoC's thermal zone, or the first one there is. */
    private String findTemperature() {
        final File[] zones = new File(mRoot + "/sys/class/thermal").listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null || zones.length == 0) {
            return null;
        }
        java.util.Arrays.sort(zones);
        for (File zone : zones) {
            final String type = read(zone.getPath() + "/type");
            if (type != null && type.contains("soc")) {
                return zone.getPath() + "/temp";
            }
        }
        return zones[0].getPath() + "/temp";
    }

    private String findBattery() {
        final File[] supplies = new File(mRoot + "/sys/class/power_supply").listFiles();
        if (supplies == null) {
            return null;
        }
        for (File supply : supplies) {
            if ("Battery".equals(read(supply.getPath() + "/type"))) {
                return supply.getPath() + "/";
            }
        }
        return null;
    }

    private static int positiveOrMinusOne(int value) {
        return value > 0 ? value : -1;
    }

    /** The number in the file divided by {@code divisor}, MIN_VALUE if unreadable. */
    private static int readInt(String path, int divisor) {
        final String text = read(path);
        if (text == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return (int) (Long.parseLong(text) / divisor);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException | java.nio.file.InvalidPathException e) {
            return null;
        }
    }
}
