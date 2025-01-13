/*
 * Copyright (C) 2025 Neoteric OS
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

package com.android.server.neoteric;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.PowerShareManager;
import android.hardware.IPowerShareManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import com.android.server.ServiceThread;
import com.android.server.SystemService;

import vendor.lineage.powershare.IPowerShare;

public class PowerShareService extends SystemService {

    private static final String TAG = "PowerShareService";
    private static final String POWERSHARE_SERVICE_NAME = "vendor.lineage.powershare.IPowerShare/default";
    private static final String CHANNEL_ID = "powershare_notification_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final IPowerShare mPowerShare;
    private final Context mContext;
    private final Handler mHandler;
    private final ServiceThread mHandlerThread;
    private final int mDefThresholdValue;

    private BatteryManager mBatteryManager;
    private PowerManager mPowerManager;
    private NotificationManager mNotificationManager;

    public PowerShareService(Context context) {
        super(context);
        mPowerShare = getPowerShare();
        mContext = context;
        mHandlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_DEFAULT, false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mDefThresholdValue = mContext.getResources().
                getInteger(com.android.internal.R.integer.config_defPowerShareThreshold);
    }

    private synchronized IPowerShare getPowerShare() {
        return IPowerShare.Stub.asInterface(ServiceManager.getService(POWERSHARE_SERVICE_NAME));
    }

    private final BroadcastReceiver mBatteryLevelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                if (shouldTurnOffBatteryShare()) {
                    disablePowerShare();
                }
            } else if (PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) {
                if (mPowerManager.isPowerSaveMode()) {
                    disablePowerShare();
                }
            }
        }
    };

    private boolean shouldTurnOffBatteryShare() {
        int currentBatteryLevel = mBatteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        int threshold = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.POWER_SHARE_THRESHOLD, mDefThresholdValue,
                UserHandle.USER_CURRENT);

        return currentBatteryLevel <= threshold;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                mContext.getResources().getString(com.android.internal.R.string.battery_share_label),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(mContext.getResources().getString(com.android.internal.R.string.battery_share_notification_summary));
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showNotification() {
        String contentText;
        if (mPowerManager.isPowerSaveMode()) {
            Log.d(TAG, "PowerShare disabled due to battery saver.");
            contentText = mContext.getResources().getString(com.android.internal.R.string.battery_share_disabled_power_saver);
        } else {
            Log.d(TAG, "PowerShare disabled as battery percentage reached minimum threshold.");
            contentText = mContext.getResources().getString(com.android.internal.R.string.battery_share_disabled_low_battery);
        }

        Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
            .setSmallIcon(com.android.internal.R.drawable.ic_qs_powershare)
            .setContentTitle(mContext.getResources().getString(com.android.internal.R.string.battery_share_disabled))
            .setContentText(contentText)
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build();
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void cancelNotification() {
        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    private void disablePowerShare() {
        try {
            if (mPowerShare != null && mPowerShare.isEnabled()) {
                mPowerShare.setEnabled(false);
                showNotification();
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private final IPowerShareManager.Stub mService = new IPowerShareManager.Stub() {
        @Override
        public boolean isEnabled() {
            try {
                return mPowerShare.isEnabled();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public boolean setEnabled(boolean enable) {
            try {
                boolean currentState = mPowerShare.isEnabled();
                if (currentState == enable) {
                    return true;
                }

                boolean result = mPowerShare.setEnabled(enable);
                if (result) {
                    if (enable) {
                        cancelNotification();
                    } else {
                        showNotification();
                    }
                }
                return result;
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return false;
        }

        @Override
        public int getMinBattery() {
            try {
                return mPowerShare.getMinBattery();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return 0;
        }

        @Override
        public int setMinBattery(int minBattery) {
            try {
                return mPowerShare.setMinBattery(minBattery);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            return 0;
        }
    };

    @Override
    public void onStart() {
        Log.v(TAG, "Starting PowerShareService");
        publishBinderService(Context.POWER_SHARE_SERVICE, mService);
        publishLocalService(PowerShareService.class, this);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            Log.d(TAG, "onBootPhase PHASE_SYSTEM_SERVICES_READY");
            mBatteryManager = mContext.getSystemService(BatteryManager.class);
            mPowerManager = mContext.getSystemService(PowerManager.class);
            mNotificationManager = mContext.getSystemService(NotificationManager.class);
            createNotificationChannel();
        } else if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            Log.d(TAG, "onBootPhase PHASE_BOOT_COMPLETED");
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
            mContext.registerReceiver(mBatteryLevelReceiver, filter);
        }
    }
}
