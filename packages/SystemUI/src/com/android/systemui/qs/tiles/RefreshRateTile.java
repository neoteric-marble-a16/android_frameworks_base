/*
 * Copyright (C) 2022-2023 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static android.provider.Settings.System.MIN_REFRESH_RATE;
import static android.provider.Settings.System.PEAK_REFRESH_RATE;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.BatteryController;

import org.neoteric.display.DisplayRefreshRateHelper;

import java.util.ArrayList;

import javax.inject.Inject;

public class RefreshRateTile extends QSTileImpl<State> implements BatteryController.BatteryStateChangeCallback {

    public static final String TILE_SPEC = "refresh_rate";

    private final DisplayRefreshRateHelper mHelper;
    private final ArrayList<Integer> mSupportedRates;
    private final ContentResolver mContentResolver;
    private final SettingsObserver mSettingsObserver;
    private final Icon mIcon;
    private final BatteryController mBatteryController;

    private int mMinRefreshRate;
    private int mPeakRefreshRate;
    private boolean isBatterySaverEnabled;

    @Inject
    public RefreshRateTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            BatteryController batteryController) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mHelper = DisplayRefreshRateHelper.getInstance(mContext);
        mSupportedRates = mHelper.getSupportedRefreshRateList();
        mContentResolver = mContext.getContentResolver();
        mSettingsObserver = new SettingsObserver(mainHandler);
        mBatteryController = batteryController;

        mIcon = ResourceIcon.get(R.drawable.ic_refresh_rate);

        if (mSupportedRates.size() > 1) {
            mSettingsObserver.observe();
        }

        mBatteryController.addCallback(this);

        updateRefreshRates();
    }

    private void updateRefreshRates() {
        mMinRefreshRate = mHelper.getMinimumRefreshRate();
        mPeakRefreshRate = mHelper.getPeakRefreshRate();
    }

    @Override
    public boolean isAvailable() {
        return mSupportedRates.size() > 1;
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        // Dynamically compute the next range or value
        int currentMinIndex = mSupportedRates.indexOf(mMinRefreshRate);
        int currentMaxIndex = mSupportedRates.indexOf(mPeakRefreshRate);

        if (mMinRefreshRate == 0 && mPeakRefreshRate == 0) {
            // Move to the first range (minimum refresh rate)
            mMinRefreshRate = mSupportedRates.get(0);
            mPeakRefreshRate = mMinRefreshRate;
        } else if (currentMaxIndex < mSupportedRates.size() - 1) {
            // Move to the next range
            mMinRefreshRate = mSupportedRates.get(currentMinIndex);
            mPeakRefreshRate = mSupportedRates.get(currentMaxIndex + 1);
        } else if (currentMinIndex < mSupportedRates.size() - 1) {
            // Start a new range from the next minimum refresh rate
            mMinRefreshRate = mSupportedRates.get(currentMinIndex + 1);
            mPeakRefreshRate = mMinRefreshRate;
        } else {
            // Switch to adaptive mode (0)
            mMinRefreshRate = 0;
            mPeakRefreshRate = 0;
        }

        // Apply the new refresh rate range
        mHelper.setRefreshRate(mMinRefreshRate, mPeakRefreshRate);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.refresh_rate_tile_label);
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = mIcon;
        state.label = mContext.getString(R.string.refresh_rate_tile_label);
        state.contentDescription = state.label;

        if (isBatterySaverEnabled) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = null;
        } else {
            state.state = Tile.STATE_ACTIVE;
            state.secondaryLabel = getRefreshRateLabel();
        }
    }

    private String getRefreshRateLabel() {
        if (mHelper.isVrrEnabled()) {
            return mContext.getString(R.string.refresh_rate_adaptive);
        } else if (mMinRefreshRate == mPeakRefreshRate) {
            return mPeakRefreshRate + " Hz";
        } else {
            return mMinRefreshRate + " ~ " + mPeakRefreshRate + " Hz";
        }
    }

    @Override
    public void destroy() {
        mSettingsObserver.unobserve();
        mBatteryController.removeCallback(this);
        super.destroy();
    }

    @Override
    public void onPowerSaveChanged(boolean isPowerSave) {
        isBatterySaverEnabled = isPowerSave;
        refreshState();
    }

    private class SettingsObserver extends ContentObserver {

        private boolean isObserving;

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            if (!isObserving) {
                mContentResolver.registerContentObserver(Settings.System.getUriFor(MIN_REFRESH_RATE),
                        false, this);
                mContentResolver.registerContentObserver(Settings.System.getUriFor(PEAK_REFRESH_RATE),
                        false, this);
                isObserving = true;
            }
        }

        void unobserve() {
            if (isObserving) {
                mContentResolver.unregisterContentObserver(this);
                isObserving = false;
            }
        }

        @Override
        public void onChange(boolean selfChange) {
            updateRefreshRates();
            refreshState();
        }
    }
}
