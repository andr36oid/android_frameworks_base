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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Plain JUnit tests for the power menu stats history and reader, no Android needed.
 */
@RunWith(JUnit4.class)
public class PowerMenuStatsTest {

    private File mRoot;

    @Before
    public void setUp() throws IOException {
        mRoot = Files.createTempDirectory("powermenustats").toFile();
    }

    @After
    public void tearDown() {
        delete(mRoot);
    }

    @Test
    public void history_keepsNewestInOrder() {
        final PowerMenuStatsHistory history = new PowerMenuStatsHistory(2, 3);
        final float[] out = new float[3];
        assertEquals(0, history.copy(0, out));

        history.add(1, 10);
        history.add(2, 20);
        assertEquals(2, history.copy(0, out));
        assertEquals(1f, out[0], 0f);
        assertEquals(2f, out[1], 0f);

        history.add(3, 30);
        history.add(4, 40);
        history.add(5, 50);
        assertEquals(3, history.size());
        assertEquals(3, history.copy(1, out));
        assertEquals(30f, out[0], 0f);
        assertEquals(40f, out[1], 0f);
        assertEquals(50f, out[2], 0f);
    }

    @Test
    public void history_shortOutputGetsNewest() {
        final PowerMenuStatsHistory history = new PowerMenuStatsHistory(1, 5);
        for (int i = 1; i <= 7; i++) {
            history.add(i);
        }
        final float[] out = new float[2];
        assertEquals(2, history.copy(0, out));
        assertEquals(6f, out[0], 0f);
        assertEquals(7f, out[1], 0f);
    }

    @Test
    public void history_missingSeriesIsNaN() {
        final PowerMenuStatsHistory history = new PowerMenuStatsHistory(3, 4);
        history.add(1);
        final float[] out = new float[4];
        assertEquals(1, history.copy(2, out));
        assertTrue(Float.isNaN(out[0]));
        history.clear();
        assertEquals(0, history.size());
    }

    @Test
    public void averager_ignoresNaN() {
        final PowerMenuStatsHistory.Averager averager = new PowerMenuStatsHistory.Averager(2);
        averager.add(10, Float.NaN);
        averager.add(20, Float.NaN);
        final float[] averages = averager.takeAverages();
        assertEquals(15f, averages[0], 0.001f);
        assertTrue(Float.isNaN(averages[1]));
        // Starts over
        averager.add(4, 8);
        final float[] next = averager.takeAverages();
        assertEquals(4f, next[0], 0.001f);
        assertEquals(8f, next[1], 0.001f);
    }

    @Test
    public void reader_cpuShareBetweenSamples() {
        final PowerMenuStatsReader reader = PowerMenuStatsReader.empty();
        reader.sampleCpu("cpu  100 0 100 800 0 0 0 0 0 0\ncpu0 1 2 3 4\n");
        assertEquals(-1, reader.cpuPercent);
        // 100 more busy (user) and 300 more idle: 25%
        reader.sampleCpu("cpu  200 0 100 1100 0 0 0 0 0 0\n");
        assertEquals(25, reader.cpuPercent);
        reader.sampleCpu("garbage");
        assertEquals(-1, reader.cpuPercent);
        reader.reset();
        reader.sampleCpu("cpu  300 0 100 1100 0 0 0 0 0 0\n");
        assertEquals(-1, reader.cpuPercent);
    }

    @Test
    public void reader_meminfo() {
        final PowerMenuStatsReader reader = PowerMenuStatsReader.empty();
        reader.sampleRam("MemTotal:        1000000 kB\nMemFree:          100000 kB\n"
                + "MemAvailable:     400000 kB\nBuffers: 1 kB\n");
        assertEquals(976, reader.ramTotalMb);
        assertEquals(585, reader.ramUsedMb);
        assertEquals(60, reader.ramPercent);
        reader.sampleRam("nothing here");
        assertEquals(-1, reader.ramPercent);
        assertEquals(-1, PowerMenuStatsReader.meminfoKb(null, "MemTotal:"));
    }

    @Test
    public void reader_fakeSysfs() throws IOException {
        write("proc/stat", "cpu  1 0 1 8 0 0 0 0 0 0\n");
        write("proc/meminfo", "MemTotal: 2097152 kB\nMemAvailable: 1048576 kB\n");
        write("sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq", "1296000\n");
        write("sys/class/devfreq/ff400000.gpu/cur_freq", "400000000\n");
        write("sys/class/devfreq/dmc/cur_freq", "786000000\n");
        write("sys/class/thermal/thermal_zone0/type", "soc-thermal\n");
        write("sys/class/thermal/thermal_zone0/temp", "52300\n");
        write("sys/class/thermal/thermal_zone1/type", "gpu-thermal\n");
        write("sys/class/thermal/thermal_zone1/temp", "99000\n");
        write("sys/class/power_supply/ac/type", "Mains\n");
        write("sys/class/power_supply/battery/type", "Battery\n");
        write("sys/class/power_supply/battery/capacity", "87\n");
        write("sys/class/power_supply/battery/status", "Discharging\n");
        write("sys/class/power_supply/battery/current_now", "-500000\n");
        write("sys/class/power_supply/battery/voltage_now", "3800000\n");

        final PowerMenuStatsReader reader = new PowerMenuStatsReader(mRoot.getPath());
        reader.sample();
        assertEquals(1296, reader.cpuMhz);
        assertEquals(400, reader.gpuMhz);
        assertEquals(52.3f, reader.tempC, 0.001f);
        assertEquals(87, reader.batteryPercent);
        assertFalse(reader.charging);
        assertEquals(3.8f, reader.batteryVolts, 0.001f);
        assertEquals(0.5f, reader.batteryAmps, 0.001f);
        assertEquals(1.9f, reader.batteryWatts, 0.001f);
        assertEquals(1024, reader.ramUsedMb);
        assertEquals(2048, reader.ramTotalMb);
        assertEquals(50, reader.ramPercent);

        write("proc/stat", "cpu  3 0 1 10 0 0 0 0 0 0\n");
        write("sys/class/power_supply/battery/status", "Charging\n");
        reader.sample();
        assertEquals(50, reader.cpuPercent);
        assertTrue(reader.charging);

        final PowerMenuStatsReader snapshot = reader.snapshot();
        assertEquals(50, snapshot.cpuPercent);
        assertEquals(1.9f, snapshot.batteryWatts, 0.001f);
    }

    @Test
    public void reader_nothingThere() {
        final PowerMenuStatsReader reader = new PowerMenuStatsReader(mRoot.getPath());
        reader.sample();
        assertEquals(-1, reader.cpuPercent);
        assertEquals(-1, reader.cpuMhz);
        assertEquals(-1, reader.gpuMhz);
        assertTrue(Float.isNaN(reader.tempC));
        assertEquals(-1, reader.batteryPercent);
        assertTrue(Float.isNaN(reader.batteryWatts));
        assertEquals(-1, reader.ramPercent);
    }

    private void write(String path, String text) throws IOException {
        final File file = new File(mRoot, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void delete(File file) {
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }
}
