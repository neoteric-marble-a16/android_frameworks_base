/*
 * Copyright (C) 2022-2023 The Nameless-AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.sysprop.TelephonyProperties;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;

import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;

import java.util.List;

import javax.inject.Inject;

public class PreferredNetworkTile extends QSTileImpl<State> {

    public static final String TILE_SPEC = "preferred_network";

    private static final String TAG = "PreferredNetworkTile";

    private static final int TYPE_UNKNOWN = 0;
    private static final int TYPE_LTE = 1;
    private static final int TYPE_NR = 2;

    private final Icon mIcon = ResourceIcon.get(R.drawable.ic_preferred_network);

    private final TelephonyManager mTelephonyManager;

    private boolean mCanSwitch = true;
    private boolean mRegistered = false;

    private int mSimCount = 0;

    private final BroadcastReceiver mDefaultSubChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Default subcription changed");
            refreshState();
        }
    };

    private final BroadcastReceiver mSimReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Sim card changed");
            refreshState();
        }
    };

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onCallStateChanged(int state, String arg1) {
            mCanSwitch = mTelephonyManager.getCallState() == 0;
            refreshState();
        }
    };

    @Inject
    public PreferredNetworkTile(
            QSHost host,
	    QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mTelephonyManager = TelephonyManager.from(host.getContext());
    }

    @Override
    public boolean isAvailable() {
        List<Integer> list = TelephonyProperties.default_network();
        for (int type : list) {
            if (type > 22) {
                return true;
            }
        }
        return false;
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        if (!mCanSwitch) {
            Log.i(TAG, "Interrupted preferred network switching due to call state");
            return;
        }
        if (mSimCount == 0) {
            return;
        }
        final int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        final TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
        final int currentType = getCurrentType(tm);
        if (currentType == TYPE_UNKNOWN) {
            return;
        }
        long newType = tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
        if (currentType == TYPE_LTE) {
            newType |= TelephonyManager.NETWORK_TYPE_BITMASK_NR;
        } else {
            newType &= ~TelephonyManager.NETWORK_TYPE_BITMASK_NR;
        }
        tm.setAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER, newType);
        refreshState();
    }

    @Override
    public Intent getLongClickIntent() {
        if (mSimCount == 0) {
            return null;
        }
        Intent intent = new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
        int dataSub = SubscriptionManager.getDefaultDataSubscriptionId();
        if (dataSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            intent.putExtra(Settings.EXTRA_SUB_ID,
                    SubscriptionManager.getDefaultDataSubscriptionId());
        }
        return intent;
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = mIcon;
        state.label = mContext.getResources().getString(R.string.quick_settings_preferred_network_label);

        updateSimCount();
        if (mSimCount == 0) {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel =
                    mContext.getResources().getString(R.string.quick_settings_preferred_network_unsupported);
            return;
        }

        final int subId = SubscriptionManager.getDefaultDataSubscriptionId();
        final TelephonyManager tm = mTelephonyManager.createForSubscriptionId(subId);
        final int currentType = getCurrentType(tm);
        state.state = currentType == TYPE_UNKNOWN ?
                Tile.STATE_UNAVAILABLE : Tile.STATE_ACTIVE;
        state.secondaryLabel = currentType == TYPE_UNKNOWN ?
                mContext.getResources().getString(R.string.quick_settings_preferred_network_unsupported)
                : currentType == TYPE_NR ?
                mContext.getResources().getString(R.string.quick_settings_preferred_network_nr)
                : mContext.getResources().getString(R.string.quick_settings_preferred_network_lte);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_preferred_network_label);
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.NEOTERIC;
    }

    @Override
    public void handleSetListening(boolean listening) {
        if (listening) {
            if (!mRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED);
                mContext.registerReceiver(mDefaultSubChangeReceiver, filter);
                filter = new IntentFilter();
                filter.addAction(TelephonyIntents.ACTION_SIM_STATE_CHANGED);
                mContext.registerReceiver(mSimReceiver, filter);
                mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                mRegistered = true;
            }
            refreshState();
        } else if (mRegistered) {
            mContext.unregisterReceiver(mDefaultSubChangeReceiver);
            mContext.unregisterReceiver(mSimReceiver);
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mRegistered = false;
        }
    }

    private int getCurrentType(TelephonyManager tm) {
        final long allowedNetworkTypes =
                tm.getAllowedNetworkTypesForReason(TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER);
        if ((allowedNetworkTypes & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0) {
            return TYPE_NR;
        }
        if ((allowedNetworkTypes & TelephonyManager.NETWORK_TYPE_BITMASK_LTE) != 0) {
            return TYPE_LTE;
        }
        return TYPE_UNKNOWN;
    }

    private void updateSimCount() {
        String simState = SystemProperties.get("gsm.sim.state");
        Log.d(TAG, "updateSimCount, simState: " + simState);
        mSimCount = 0;
        try {
            String[] sims = TextUtils.split(simState, ",");
            for (String sim : sims) {
                if (!sim.isEmpty()
                        && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_ABSENT)
                        && !sim.equalsIgnoreCase(IccCardConstants.INTENT_VALUE_ICC_NOT_READY)) {
                    mSimCount++;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error to parse sim state");
        }
        Log.d(TAG, "updateSimCount, mSimCount: " + mSimCount);
    }

}
