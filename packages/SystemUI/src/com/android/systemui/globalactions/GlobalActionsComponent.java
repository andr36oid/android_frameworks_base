/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SystemUI;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.plugins.GlobalActions.GlobalActionsManager;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.ExtensionController.Extension;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

/**
 * Manages power menu plugins and communicates power menu actions to the StatusBar.
 */
@Singleton
public class GlobalActionsComponent extends SystemUI implements Callbacks, GlobalActionsManager {

    /**
     * FN + B (PhoneWindowManager): the power menu right on its App switcher page. Only the
     * system may send it.
     */
    private static final String ACTION_SHOW_APP_SWITCHER =
            "com.android.systemui.action.SHOW_APP_SWITCHER";

    private final CommandQueue mCommandQueue;
    private final ExtensionController mExtensionController;
    private final Provider<GlobalActions> mGlobalActionsProvider;
    private GlobalActions mPlugin;
    private Extension<GlobalActions> mExtension;
    private IStatusBarService mBarService;
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Inject
    public GlobalActionsComponent(Context context, CommandQueue commandQueue,
            ExtensionController extensionController,
            Provider<GlobalActions> globalActionsProvider,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager) {
        super(context);
        mCommandQueue = commandQueue;
        mExtensionController = extensionController;
        mGlobalActionsProvider = globalActionsProvider;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
    }

    @Override
    public void start() {
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mExtension = mExtensionController.newExtension(GlobalActions.class)
                .withPlugin(GlobalActions.class)
                .withDefault(mGlobalActionsProvider::get)
                .withCallback(this::onExtensionCallback)
                .build();
        mPlugin = mExtension.get();
        mCommandQueue.addCallback(this);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                showAppSwitcher();
            }
        }, new IntentFilter(ACTION_SHOW_APP_SWITCHER), Manifest.permission.STATUS_BAR_SERVICE,
                null);
        // History for the graphs of the power menu stats panel
        PowerMenuStatsPanel.startSampling(mContext);
    }

    private void onExtensionCallback(GlobalActions newPlugin) {
        if (mPlugin != null) {
            mPlugin.destroy();
        }
        mPlugin = newPlugin;
    }

    @Override
    public void handleShowShutdownUi(boolean isReboot, String reason, boolean rebootCustom) {
        mExtension.get().showShutdownUi(isReboot, reason, rebootCustom);
    }

    @Override
    public void handleShowGlobalActionsMenu() {
        mStatusBarKeyguardViewManager.setGlobalActionsVisible(true);
        mExtension.get().showGlobalActions(this);
    }

    /**
     * The power menu right on its App switcher page. FN + B, and the Recents key when there is
     * no quickstep launcher (OverviewProxyRecentsImpl).
     */
    public void showAppSwitcher() {
        if (mExtension == null) return; // not started yet
        final GlobalActions plugin = mExtension.get();
        if (!(plugin instanceof GlobalActionsImpl)) {
            // A power menu plugin has no App switcher page: open it as usual
            handleShowGlobalActionsMenu();
            return;
        }
        // Like handleShowGlobalActionsMenu, but only when the menu really opens: with no
        // other app open, or with a recents provider, it stays closed
        if (((GlobalActionsImpl) plugin).showAppSwitcher()) {
            mStatusBarKeyguardViewManager.setGlobalActionsVisible(true);
        }
    }

    @Override
    public void onGlobalActionsShown() {
        try {
            mBarService.onGlobalActionsShown();
        } catch (RemoteException e) {
        }
    }

    @Override
    public void onGlobalActionsHidden() {
        try {
            mStatusBarKeyguardViewManager.setGlobalActionsVisible(false);
            mBarService.onGlobalActionsHidden();
        } catch (RemoteException e) {
        }
    }

    @Override
    public void shutdown() {
        try {
            mBarService.shutdown();
        } catch (RemoteException e) {
        }
    }

    @Override
    public void reboot(boolean safeMode, String reason) {
        try {
            mBarService.reboot(safeMode, reason);
        } catch (RemoteException e) {
        }
    }
}
