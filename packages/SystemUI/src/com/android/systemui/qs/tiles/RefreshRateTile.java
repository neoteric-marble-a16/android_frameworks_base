/*
 * Copyright (C) 2022-2023 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import static android.provider.Settings.System.MIN_REFRESH_RATE;
import static android.provider.Settings.System.PEAK_REFRESH_RATE;

import android.content.Intent;
import android.content.ContentResolver;
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
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SystemSettings;

import java.util.ArrayList;

import javax.inject.Inject;

import org.neoteric.display.DisplayRefreshRateHelper;

public class RefreshRateTile extends QSTileImpl<State> {

    public static final String TILE_SPEC = "refresh_rate";

    private final ArrayList<Integer> mSupportedList;
    private final DisplayRefreshRateHelper mHelper;
    private final SettingsObserver mObserver;
    private final ContentResolver mContentResolver;

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_refresh_rate);

    private int mMinRefreshRate;
    private int mPeakRefreshRate;

    private boolean mUpdateRefreshRate = true;

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
            QSLogger qsLogger) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        mHelper = DisplayRefreshRateHelper.getInstance(mContext);
        mSupportedList = mHelper.getSupportedRefreshRateList();

        mContentResolver = mContext.getContentResolver();
        mObserver = new SettingsObserver(mainHandler);

	if (mSupportedList.size() > 1)
	    mObserver.observe(mContentResolver);

        mMinRefreshRate = mHelper.getMinimumRefreshRate();
        mPeakRefreshRate = mHelper.getPeakRefreshRate();
    }

    @Override
    public boolean isAvailable() {
        return mSupportedList.size() > 1;
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (!isRefreshRateValid()) {
            mMinRefreshRate = mSupportedList.get(mSupportedList.size() - 1);
            mPeakRefreshRate = mMinRefreshRate;
        } else if (mSupportedList.indexOf(mPeakRefreshRate) == mSupportedList.size() - 1) {
            if (mMinRefreshRate == mPeakRefreshRate) {
                mMinRefreshRate = mSupportedList.get(0);
            } else {
                mMinRefreshRate = mSupportedList.get(mSupportedList.indexOf(mMinRefreshRate) + 1);
            }
            mPeakRefreshRate = mMinRefreshRate;
        } else {
            mPeakRefreshRate = mSupportedList.get(mSupportedList.indexOf(mPeakRefreshRate) + 1);
        }
        mUpdateRefreshRate = false;
        mHelper.setRefreshRate(mMinRefreshRate, mPeakRefreshRate);
        mUpdateRefreshRate = true;
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
        if (!isAvailable()) {
            return;
        }

        state.state = Tile.STATE_ACTIVE;
        state.icon = mIcon;
        state.label = mContext.getString(R.string.refresh_rate_tile_label);
        state.contentDescription = mContext.getString(R.string.refresh_rate_tile_label);
        state.secondaryLabel = getRefreshRateLabel();
    }

    @Override
    public void destroy() {
        mObserver.unobserve(mContentResolver);
        super.destroy();
    }

    private boolean isRefreshRateValid() {
        return mHelper.isRefreshRateValid(mMinRefreshRate) &&
                mHelper.isRefreshRateValid(mPeakRefreshRate) &&
                mMinRefreshRate <= mPeakRefreshRate;
    }

    private String getRefreshRateLabel() {
        if (!isRefreshRateValid()) {
            return mContext.getString(R.string.refresh_rate_unknown);
        }
        if (mMinRefreshRate == mPeakRefreshRate) {
            return String.valueOf(mPeakRefreshRate) + " Hz";
        }
        return String.valueOf(mMinRefreshRate) + " ~ " + String.valueOf(mPeakRefreshRate) + " Hz";
    }

    private final class SettingsObserver extends ContentObserver {

        private boolean isObserving = false;

        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe(ContentResolver cr) {
            if (isObserving) {
                return;
            }
            cr.registerContentObserver(Settings.System.getUriFor(Settings.System.MIN_REFRESH_RATE), false, this);
            cr.registerContentObserver(Settings.System.getUriFor(Settings.System.PEAK_REFRESH_RATE), false, this);
            isObserving = true;
        }

        void unobserve(ContentResolver cr) {
            if (!isObserving) {
                return;
            }
            cr.unregisterContentObserver(this);
            isObserving = false;
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mUpdateRefreshRate) {
                mMinRefreshRate = mHelper.getMinimumRefreshRate();
                mPeakRefreshRate = mHelper.getPeakRefreshRate();
            }
            handleRefreshState(null);
        }
    }
}
