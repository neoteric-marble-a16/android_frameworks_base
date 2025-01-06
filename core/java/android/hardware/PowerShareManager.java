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

package android.hardware;

import android.content.Context;
import android.annotation.SystemService;
import android.os.RemoteException;

/**
 * Manages PowerShare
 * @hide
 */
@SystemService(Context.POWER_SHARE_SERVICE)
public class PowerShareManager {
    private static final String TAG = "PowerShareManager";

    private IPowerShareManager mService;

    public PowerShareManager (IPowerShareManager service) {
        mService = service;
    }

    /**
     * If PowerShare is enabled.
     * @hide
     */
    public boolean isEnabled() {
        if (mService != null) {
            try {
                return mService.isEnabled();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Enable/Disable PowerShare
     * @hide
     */
    public boolean setEnabled(boolean enable) {
        if (mService != null) {
            try {
                return mService.setEnabled(enable);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Get min battery percentage required to run PowerShare
     * @hide
     */
    public int getMinBattery() {
        if (mService != null) {
            try {
                return mService.getMinBattery();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }

    /**
     * Set min battery percentage required to run PowerShare
     * @hide
     */
    public int setMinBattery(int minBattery) {
        if (mService != null) {
            try {
                return mService.setMinBattery(minBattery);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return 0;
    }
}
