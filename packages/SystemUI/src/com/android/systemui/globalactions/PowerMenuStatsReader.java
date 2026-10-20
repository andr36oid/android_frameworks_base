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

import android.os.SystemProperties;

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

    static final String STORAGE_STAT = "/sys/block/mmcblk0/stat";

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
    /**
     * The RAM graph's layers, stacked, in percent of MemTotal, NaN when unknown: apps (their
     * own memory and shared memory), then the kernel's own, then the rest of the used RAM
     * (GPU, drivers, zram's compressed pages) up to {@link #ramPercent}, then the cache on
     * top. The cache isn't part of ramPercent: Android drops it when memory is needed.
     */
    float ramAppsTop = Float.NaN;
    float ramKernelTop = Float.NaN;
    float ramCacheTop = Float.NaN;
    /** How busy the SD card was since the last sample, 0..100, -1 if unknown. */
    int storagePercent = -1;
    /** What the SD card read and wrote together since the last sample, bytes/s; -1 unknown. */
    long storageBytesPerSec = -1;
    /** 1 if the card mostly read since the last sample, -1 if it mostly wrote, 0 neither. */
    int storageDirection;
    /** Swap is set up, which on this console is only the zram device from the fstab. */
    boolean zramOn;

    private final String mRoot;
    private final String mStoragePath;
    private final String mGpuFreqPath;
    private final String mTempPath;
    private final String mBatteryPath;

    private long mLastCpuBusy = -1;
    private long mLastCpuTotal;
    private long mLastIoTicks = -1;
    private long mLastIoTime;
    private long mLastIoSectors = -1;
    private long mLastIoRead;

    PowerMenuStatsReader() {
        this("");
    }

    /** @param root prefix for every path, "" on the device, a fake tree in tests */
    PowerMenuStatsReader(String root) {
        this(root, SystemProperties.get("ro.boot.boot_devices", ""));
    }

    /** @param bootDevices androidboot.boot_devices: where Android started from */
    PowerMenuStatsReader(String root, String bootDevices) {
        mRoot = root;
        mStoragePath = findStorage(bootDevices);
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
        copy.ramAppsTop = ramAppsTop;
        copy.ramKernelTop = ramKernelTop;
        copy.ramCacheTop = ramCacheTop;
        copy.storagePercent = storagePercent;
        copy.storageBytesPerSec = storageBytesPerSec;
        copy.storageDirection = storageDirection;
        copy.zramOn = zramOn;
        return copy;
    }

    // For empty() and snapshot(): takes the paths, looks nothing up
    private PowerMenuStatsReader(String root, String gpuFreqPath, String tempPath,
            String batteryPath) {
        mRoot = root;
        mStoragePath = root + STORAGE_STAT;
        mGpuFreqPath = gpuFreqPath;
        mTempPath = tempPath;
        mBatteryPath = batteryPath;
    }

    /** Forget the last CPU sample, e.g. after the screen was off. */
    void reset() {
        mLastCpuBusy = -1;
        cpuPercent = -1;
        mLastIoTicks = -1;
        storagePercent = -1;
        mLastIoSectors = -1;
        storageBytesPerSec = -1;
        storageDirection = 0;
    }

    void sample() {
        sampleCpu(read(mRoot + "/proc/stat"));
        cpuMhz = positiveOrMinusOne(readInt(mRoot + CPU_FREQ, 1000));
        gpuMhz = mGpuFreqPath == null ? -1
                : positiveOrMinusOne(readInt(mGpuFreqPath, 1000000));
        final int milliC = mTempPath == null ? Integer.MIN_VALUE : readInt(mTempPath, 1);
        tempC = milliC == Integer.MIN_VALUE || milliC < 0 ? Float.NaN : milliC / 1000f;
        sampleStorage(read(mStoragePath), System.nanoTime() / 1000000);
        sampleBattery();
        sampleRam(read(mRoot + "/proc/meminfo"));
    }

    /**
     * How much of the time since the last sample the SD card was busy, like iostat's %util:
     * field 10 of its stat file (io_ticks, milliseconds spent doing I/O) over the wall clock.
     */
    void sampleStorage(String stat, long nowMs) {
        final String[] fields = stat == null ? new String[0] : stat.trim().split("\\s+");
        long ticks = -1;
        long sectors = -1;
        long read = 0;
        try {
            if (fields.length > 9) {
                ticks = Long.parseLong(fields[9]);
                // sectors read (field 3) and written (field 7), 512 bytes each
                read = Long.parseLong(fields[2]);
                sectors = read + Long.parseLong(fields[6]);
            }
        } catch (NumberFormatException e) {
            ticks = -1;
        }
        if (ticks < 0 || sectors < 0) {
            storagePercent = -1;
            storageBytesPerSec = -1;
            storageDirection = 0;
            mLastIoTicks = -1;
            mLastIoSectors = -1;
            return;
        }
        if (mLastIoTicks >= 0 && nowMs > mLastIoTime && ticks >= mLastIoTicks
                && sectors >= mLastIoSectors) {
            final long ms = nowMs - mLastIoTime;
            storagePercent = (int) Math.max(0, Math.min(100,
                    100 * (ticks - mLastIoTicks) / ms));
            storageBytesPerSec = (sectors - mLastIoSectors) * 512 * 1000 / ms;
            final long readNow = read - mLastIoRead;
            final long writtenNow = sectors - mLastIoSectors - readNow;
            storageDirection = readNow > writtenNow ? 1 : writtenNow > readNow ? -1 : 0;
        }
        mLastIoRead = read;
        mLastIoTicks = ticks;
        mLastIoSectors = sectors;
        mLastIoTime = nowMs;
    }

    /** The storage stat file this reader samples (tests). */
    String storagePath() {
        return mStoragePath;
    }

    /**
     * The stat file of the disk Android started from: the SD card's, or the built-in eMMC's
     * for andr36oid installed there. Its sysfs path has the boot device's name in it.
     */
    private String findStorage(String bootDevices) {
        final File[] disks = new File(mRoot + "/sys/block").listFiles();
        if (disks != null && !bootDevices.isEmpty()) {
            for (File disk : disks) {
                // mmcblkN, not its boot areas (mmcblkNboot0) or rpmb
                if (!disk.getName().matches("mmcblk\\d+")) {
                    continue;
                }
                try {
                    final String path = disk.getCanonicalPath();
                    for (String dev : bootDevices.split(",")) {
                        if (!dev.isEmpty() && path.contains("/" + dev + "/")) {
                            return disk.getPath() + "/stat";
                        }
                    }
                } catch (IOException e) {
                    // not this one
                }
            }
        }
        return mRoot + STORAGE_STAT;
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

    /** Below this the current says nothing about the direction (full, or noise). */
    private static final int MIN_DIRECTION_MICROAMPS = 20000;

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
        // The current's sign is what the battery really does (+ in, - out): a charger the
        // driver missed, or a game drawing more than a weak charger gives, shows right
        if (microAmps != Integer.MIN_VALUE && Math.abs(microAmps) >= MIN_DIRECTION_MICROAMPS) {
            charging = microAmps > 0;
        }
    }

    /** Used is MemTotal - MemAvailable, like the performance overlay. */
    void sampleRam(String meminfo) {
        final long total = meminfoKb(meminfo, "MemTotal:");
        final long available = meminfoKb(meminfo, "MemAvailable:");
        if (total > 0 && available >= 0 && available <= total) {
            ramTotalMb = (int) (total / 1024);
            ramUsedMb = (int) ((total - available) / 1024);
            ramPercent = (int) (100 * (total - available) / total);
            sampleRamLayers(meminfo, total, total - available);
        } else {
            ramTotalMb = ramUsedMb = ramPercent = -1;
            ramAppsTop = ramKernelTop = ramCacheTop = Float.NaN;
        }
        // /proc/swaps is not readable for SystemUI, meminfo is
        zramOn = meminfoKb(meminfo, "SwapTotal:") > 0;
    }

    private void sampleRamLayers(String meminfo, long total, long used) {
        final long anon = meminfoKb(meminfo, "AnonPages:");
        final long shmem = Math.max(0, meminfoKb(meminfo, "Shmem:"));
        final long kernel = Math.max(0, meminfoKb(meminfo, "SUnreclaim:"))
                + Math.max(0, meminfoKb(meminfo, "KernelStack:"))
                + Math.max(0, meminfoKb(meminfo, "PageTables:"));
        final long cached = meminfoKb(meminfo, "Cached:");
        if (anon < 0 || cached < 0) {
            ramAppsTop = ramKernelTop = ramCacheTop = Float.NaN;
            return;
        }
        // Shared memory is counted in Cached, but can't be dropped like the cache
        final long cache = Math.max(0, cached - shmem)
                + Math.max(0, meminfoKb(meminfo, "Buffers:"))
                + Math.max(0, meminfoKb(meminfo, "SReclaimable:"));
        // Each layer within the used RAM, the cache within what is left
        final long apps = Math.min(anon + shmem, used);
        final long kernelTop = Math.min(apps + kernel, used);
        final long cacheTop = Math.min(used + cache, total);
        ramAppsTop = 100f * apps / total;
        ramKernelTop = 100f * kernelTop / total;
        ramCacheTop = 100f * cacheTop / total;
    }

    static long meminfoKb(String meminfo, String key) {
        if (meminfo == null) {
            return -1;
        }
        // At the start of a line, so "Cached:" doesn't find "SwapCached:"
        final int start;
        if (meminfo.startsWith(key)) {
            start = 0;
        } else {
            final int newline = meminfo.indexOf("\n" + key);
            if (newline < 0) {
                return -1;
            }
            start = newline + 1;
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
