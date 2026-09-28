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

    // Settings.Global.WIFI_ON values meaning the user switched Wi-Fi on
    private static final int WIFI_ENABLED = 1;
    private static final int WIFI_ENABLED_AIRPLANE_OVERRIDE = 2;

    private final Handler mHandler;
    private final Runnable mStartWifi = this::startWifiIfWanted;
    private Set<String> mWifiIds; // "vvvv:pppp", read on first use

    private final UEventObserver mObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEventObserver.UEvent event) {
            if (!"add".equals(event.get("ACTION"))) {
                return;
            }
            final String subsystem = event.get("SUBSYSTEM");
            if ("net".equals(subsystem)) {
                // Driver already loaded or built in
                final String iface = event.get("INTERFACE");
                if (iface != null && iface.startsWith("wlan")) {
                    scheduleStart("interface " + iface);
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
        if (phase != PHASE_BOOT_COMPLETED
                || !SystemProperties.getBoolean("ro.wifi.usb_hotplug", false)) {
            return;
        }
        mObserver.startObserving("SUBSYSTEM=usb");
        mObserver.startObserving("SUBSYSTEM=net");
    }

    private void scheduleStart(String reason) {
        Slog.d(TAG, "Wi-Fi hotplug check: " + reason);
        mHandler.removeCallbacks(mStartWifi);
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

    private boolean isWifiAdapter(String id) {
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
