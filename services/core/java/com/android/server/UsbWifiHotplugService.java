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

package com.android.server;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.provider.Settings;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Starts Wi-Fi when a USB Wi-Fi adapter is plugged in while Wi-Fi is switched
 * on but not running. That happens when Wi-Fi was switched on (or left on at
 * boot) with no adapter plugged in, or when the adapter was pulled out. The
 * Wi-Fi HAL loads the adapter's driver when Wi-Fi starts (libwifi_hal and
 * /vendor/etc/wifi_id_list.txt). Only runs with ro.wifi.usb_hotplug=true.
 */
public final class UsbWifiHotplugService extends SystemService {
    private static final String TAG = "UsbWifiHotplug";

    private static final String ID_LIST = "/vendor/etc/wifi_id_list.txt";
    // Lets the adapter finish enumerating (and mode switching) first.
    private static final long START_DELAY_MS = 2000;
    // Wi-Fi may still be stopping for the adapter that just went away (a replug, or the
    // adapter re-enumerating after sleep). Check again until it has settled.
    private static final long RETRY_DELAY_MS = 1000;
    private static final int MAX_RETRIES = 30;

    // Settings.Global.WIFI_ON values meaning the user switched Wi-Fi on
    private static final int WIFI_ENABLED = 1;
    private static final int WIFI_ENABLED_AIRPLANE_OVERRIDE = 2;

    // 1 while a Wi-Fi or Bluetooth adapter is plugged in, 0 while none is. SystemUI hides
    // the Wi-Fi and Bluetooth tiles at 0 (no adapter is built into these consoles).
    private static final String WIFI_ADAPTER = "andr36oid_wifi_adapter";
    private static final String BT_ADAPTER = "andr36oid_bt_adapter";
    // Lets sysfs settle after an adapter comes or goes before it is looked at again
    private static final long PRESENCE_DELAY_MS = 500;

    private final Handler mHandler;
    private final Runnable mStartWifi = this::startWifiIfWanted;
    private final Runnable mUpdatePresence = this::updatePresence;
    private volatile boolean mStartWifiOnPlug;
    private int mRetries;
    private Set<String> mWifiIds; // "vvvv:pppp", read on first use

    private final UEventObserver mObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            final String action = event.get("ACTION");
            final String subsystem = event.get("SUBSYSTEM");
            if ("add".equals(action) || "remove".equals(action)) {
                mHandler.removeCallbacks(mUpdatePresence);
                mHandler.postDelayed(mUpdatePresence, PRESENCE_DELAY_MS);
            }
            if (!"add".equals(action) || !mStartWifiOnPlug) {
                return;
            }
            if ("net".equals(subsystem)) {
                // Driver already loaded or built in
                final String iface = event.get("INTERFACE");
                if (iface != null && iface.startsWith("wlan")) {
                    mHandler.post(() -> scheduleStart("interface " + iface));
                }
            } else if ("usb".equals(subsystem) && "usb_device".equals(event.get("DEVTYPE"))) {
                final String product = event.get("PRODUCT");
                mHandler.post(() -> {
                    final String id = usbId(product);
                    if (id != null && isWifiAdapter(id)) {
                        scheduleStart("adapter " + id);
                    }
                });
            }
        }
    };

    public UsbWifiHotplugService(Context context) {
        super(context);
        mHandler = new Handler(IoThread.get().getLooper());
    }

    @Override
    public void onStart() {
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            // Before SystemUI starts, so its tiles never show for a missing adapter
            updatePresence();
            mObserver.startObserving("SUBSYSTEM=usb");
            mObserver.startObserving("SUBSYSTEM=net");
            mObserver.startObserving("SUBSYSTEM=bluetooth");
        } else if (phase == PHASE_BOOT_COMPLETED) {
            mStartWifiOnPlug = SystemProperties.getBoolean("ro.wifi.usb_hotplug", false);
        }
    }

    /** Publishes whether a Wi-Fi and a Bluetooth adapter are plugged in. */
    private void updatePresence() {
        boolean wifi = false;
        // A known Wi-Fi dongle: its driver is only loaded while Wi-Fi is on, so wlan0
        // alone doesn't say whether one is there
        final File[] usb = new File("/sys/bus/usb/devices").listFiles();
        if (usb != null) {
            for (File dev : usb) {
                final String vid = readLine(new File(dev, "idVendor"));
                final String pid = readLine(new File(dev, "idProduct"));
                if (vid != null && pid != null
                        && isWifiAdapter(vid.toLowerCase(Locale.US) + ":"
                                + pid.toLowerCase(Locale.US))) {
                    wifi = true;
                    break;
                }
            }
        }
        // Or a Wi-Fi interface from anything else (SDIO Wi-Fi on some clones)
        wifi = wifi || hasEntry("/sys/class/net", "wlan");
        // hciN:M entries are connections, not controllers
        final boolean bt = hasEntry("/sys/class/bluetooth", "hci");
        Slog.i(TAG, "Adapters: Wi-Fi " + (wifi ? "yes" : "no")
                + ", Bluetooth " + (bt ? "yes" : "no"));
        Settings.Global.putInt(getContext().getContentResolver(), WIFI_ADAPTER, wifi ? 1 : 0);
        Settings.Global.putInt(getContext().getContentResolver(), BT_ADAPTER, bt ? 1 : 0);
    }

    private static boolean hasEntry(String dir, String prefix) {
        final String[] names = new File(dir).list();
        if (names != null) {
            for (String name : names) {
                if (name.startsWith(prefix) && name.indexOf(':') < 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String readLine(File file) {
        if (!file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            final String line = reader.readLine();
            return line != null ? line.trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void scheduleStart(String reason) {
        Slog.d(TAG, "Wi-Fi hotplug check: " + reason);
        mHandler.removeCallbacks(mStartWifi);
        mRetries = 0;
        mHandler.postDelayed(mStartWifi, START_DELAY_MS);
    }

    /** "bda/8179/200" (PRODUCT of a USB device uevent) to "0bda:8179". */
    private static String usbId(String product) {
        if (product == null) {
            return null;
        }
        final String[] parts = product.split("/");
        if (parts.length < 2) {
            return null;
        }
        try {
            return String.format(Locale.US, "%04x:%04x",
                    Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1], 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private synchronized boolean isWifiAdapter(String id) {
        if (mWifiIds == null) {
            final Set<String> ids = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(ID_LIST))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String[] fields = line.trim().split("\\s+");
                    if (fields.length >= 2) {
                        ids.add(fields[0].toLowerCase(Locale.US) + ":"
                                + fields[1].toLowerCase(Locale.US));
                    }
                }
            } catch (IOException e) {
                Slog.w(TAG, "Couldn't read " + ID_LIST, e);
            }
            mWifiIds = ids;
        }
        return mWifiIds.contains(id);
    }

    private void startWifiIfWanted() {
        final int wifiOn = Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.WIFI_ON, 0);
        if (wifiOn != WIFI_ENABLED && wifiOn != WIFI_ENABLED_AIRPLANE_OVERRIDE) {
            return;
        }
        final WifiManager wifi = getContext().getSystemService(WifiManager.class);
        if (wifi == null) {
            return;
        }
        final int state = wifi.getWifiState();
        if (state == WifiManager.WIFI_STATE_DISABLING || state == WifiManager.WIFI_STATE_ENABLING) {
            if (mRetries++ < MAX_RETRIES) {
                mHandler.postDelayed(mStartWifi, RETRY_DELAY_MS);
            } else {
                Slog.w(TAG, "Wi-Fi did not settle, not starting it for the new adapter");
            }
            return;
        }
        if (state != WifiManager.WIFI_STATE_DISABLED && state != WifiManager.WIFI_STATE_UNKNOWN) {
            return;
        }
        Slog.i(TAG, "Wi-Fi is switched on but not running, starting it for the new adapter");
        try {
            wifi.setWifiEnabled(true);
        } catch (RuntimeException e) {
            Slog.e(TAG, "Couldn't start Wi-Fi", e);
        }
    }
}
