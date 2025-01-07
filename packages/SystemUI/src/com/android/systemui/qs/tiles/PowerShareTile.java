/*
 * Copyright (C) 2020 The LineageOS Project
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

package com.android.systemui.qs.tiles;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.PowerShareManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceManager;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import javax.inject.Inject;

public class PowerShareTile extends QSTileImpl<BooleanState>
        implements BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "powershare";

    private BatteryController mBatteryController;
    private NotificationManager mNotificationManager;
    private Notification mNotification;
    private static final String CHANNEL_ID = TILE_SPEC;
    private static final int NOTIFICATION_ID = 273298;

    private final PowerShareManager mPowerShareManager;

    @Inject
    public PowerShareTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BatteryController batteryController
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mPowerShareManager = (PowerShareManager) mContext.getSystemService(Context.POWER_SHARE_SERVICE);
        if (mPowerShareManager == null) return;
        mBatteryController = batteryController;
        batteryController.addCallback(this);
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        refreshState();
    }

    @Override
    public void refreshState() {
        updatePowerShareState();

        super.refreshState();
    }

    private void updatePowerShareState() {
        if (!isAvailable()) {
            return;
        }

        if (mBatteryController.isPowerSave()) {
            mPowerShareManager.setEnabled(false);
        }

        mPowerShareManager.isEnabled();
    }

    @Override
    public boolean isAvailable() {
        return mPowerShareManager != null;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        return state;
    }

    @Override
    public void handleClick(@Nullable Expandable expandable) {
        boolean powerShareEnabled = mPowerShareManager.isEnabled();

        if (mPowerShareManager.setEnabled(!powerShareEnabled) != powerShareEnabled) {
            refreshState();
        }
    }

    @Override
    public Intent getLongClickIntent() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.android.settings",
                "com.android.settings.Settings$BatteryShareSettingsActivity"
        ));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    @Override
    public CharSequence getTileLabel() {
        if (mBatteryController.isPowerSave()) {
            return mContext.getString(R.string.quick_settings_powershare_off_powersave_label);
        } else {
            if (getBatteryLevel() < getMinBatteryLevel()) {
                return mContext.getString(R.string.quick_settings_powershare_off_low_battery_label);
            }
        }

        return mContext.getString(R.string.quick_settings_powershare_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (!isAvailable()) {
            return;
        }

        state.icon = ResourceIcon.get(R.drawable.ic_qs_powershare);
        state.value = mPowerShareManager.isEnabled();
        state.label = mContext.getString(R.string.quick_settings_powershare_label);

        if (mBatteryController.isPowerSave() || getBatteryLevel() < getMinBatteryLevel()) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else if (!state.value) {
            state.state = Tile.STATE_INACTIVE;
        } else {
            state.state = Tile.STATE_ACTIVE;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.NEOTERIC;
    }

    @Override
    public void handleSetListening(boolean listening) {
    }

    private int getMinBatteryLevel() {
        return mPowerShareManager.getMinBattery();
    }

    private int getBatteryLevel() {
        BatteryManager bm = mContext.getSystemService(BatteryManager.class);
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
}
