/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.mms.service;

import com.android.mms.service.exception.MmsNetworkException;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallbackListener;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.util.Log;

/**
 * Manages the MMS network connectivity
 */
public class MmsNetworkManager {
    // Timeout used to call ConnectivityManager.requestNetwork
    private static final int NETWORK_REQUEST_TIMEOUT_SEC = 3 * 60;
    // Wait timeout for this class, a little bit longer than the above timeout
    // to make sure we don't bail prematurely
    private static final int NETWORK_ACQUIRE_TIMEOUT_MILLIS =
            (NETWORK_REQUEST_TIMEOUT_SEC + 15) * 1000;

    private Context mContext;
    // The current {@link android.net.NetworkRequest} we hold when we request MMS network
    // We need this for releasing the MMS network
    private NetworkRequest mRequest;
    // The requested MMS {@link android.net.Network} we are holding
    // We need this when we unbind from it. This is also used to indicate if the
    // MMS network is available.
    private Network mNetwork;
    // The current count of MMS requests that require the MMS network
    // If mMmsRequestCount is 0, we should release the MMS network.
    private int mMmsRequestCount;

    // Network callback listener for monitoring status of requested MMS network
    private NetworkCallbackListener mNetworkCallbackListener = new NetworkCallbackListener() {
        @Override
        public void onAvailable(NetworkRequest networkRequest, Network network) {
            super.onAvailable(networkRequest, network);
            Log.d(MmsService.TAG, "NetworkCallbackListener.onAvailable: request=" + networkRequest
                    + ", network=" + network + ", current request=" + mRequest);
            synchronized (MmsNetworkManager.this) {
                if (mRequest != null && mRequest.equals(networkRequest)) {
                    mNetwork = network;
                    ConnectivityManager.setProcessDefaultNetwork(mNetwork);
                    MmsNetworkManager.this.notifyAll();
                }
            }
        }

        @Override
        public void onLost(NetworkRequest networkRequest, Network network) {
            super.onLost(networkRequest, network);
            Log.d(MmsService.TAG, "NetworkCallbackListener.onLost: request=" + networkRequest
                    + ", network=" + network + ", current request=" + mRequest);
            synchronized (MmsNetworkManager.this) {
                if (mRequest != null && mRequest.equals(networkRequest)) {
                    ConnectivityManager.setProcessDefaultNetwork(null);
                    mNetwork = null;
                    releaseRequest();
                    MmsNetworkManager.this.notifyAll();
                }
            }
        }

        @Override
        public void onUnavailable(NetworkRequest networkRequest) {
            super.onUnavailable(networkRequest);
            Log.d(MmsService.TAG, "NetworkCallbackListener.onUnavailable: request="
                    + networkRequest + ", current request=" + mRequest);
            synchronized (MmsNetworkManager.this) {
                if (mRequest != null && mRequest.equals(networkRequest)) {
                    releaseRequest();
                    MmsNetworkManager.this.notifyAll();
                }
            }
        }
    };

    public MmsNetworkManager(Context context) {
        mContext = context;
        mRequest = null;
        mNetwork = null;
        mMmsRequestCount = 0;
    }

    private static final NetworkCapabilities MMS_NETWORK_CAPABILITIES = new NetworkCapabilities();
    static {
        MMS_NETWORK_CAPABILITIES.addNetworkCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
    }

    /**
     * Acquire the MMS network
     *
     * @throws com.android.mms.service.exception.MmsNetworkException if we fail to acquire it
     */
    public void acquireNetwork() throws MmsNetworkException {
        synchronized (this) {
            mMmsRequestCount += 1;
            if (mNetwork != null) {
                // Already available
                Log.d(MmsService.TAG, "MmsNetworkManager: already available");
                return;
            }
            Log.d(MmsService.TAG, "MmsNetworkManager: start new network request");
            // Not available, so start a new request
            newRequest();
            final long shouldEnd = SystemClock.elapsedRealtime() + NETWORK_ACQUIRE_TIMEOUT_MILLIS;
            long waitTime = NETWORK_ACQUIRE_TIMEOUT_MILLIS;
            while (waitTime > 0) {
                try {
                    this.wait(waitTime);
                } catch (InterruptedException e) {
                    Log.w(MmsService.TAG, "MmsNetworkManager: acquire network wait interrupted");
                }
                if (mNetwork != null) {
                    // Success
                    return;
                }
                // Calculate remaining waiting time to make sure we wait the full timeout period
                waitTime = shouldEnd - SystemClock.elapsedRealtime();
            }
            // Timed out, so release the request and fail
            Log.d(MmsService.TAG, "MmsNetworkManager: timed out");
            releaseRequest();
            throw new MmsNetworkException("Acquiring network timed out");
        }
    }

    /**
     * Release the MMS network when nobody is holding on to it.
     */
    public void releaseNetwork() {
        synchronized (this) {
            mMmsRequestCount -= 1;
            Log.d(MmsService.TAG, "MmsNetworkManager: release, count=" + mMmsRequestCount);
            if (mMmsRequestCount < 1) {
                if (mRequest != null) {
                    releaseRequest();
                    mNetwork = null;
                }
            }
        }
    }

    /**
     * Start a new {@link android.net.NetworkRequest} for MMS
     */
    private void newRequest() {
        final ConnectivityManager connectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mRequest = connectivityManager.requestNetwork(MMS_NETWORK_CAPABILITIES,
                mNetworkCallbackListener, NETWORK_REQUEST_TIMEOUT_SEC);
    }

    /**
     * Release the current {@link android.net.NetworkRequest} for MMS
     */
    private void releaseRequest() {
        if (mRequest != null) {
            final ConnectivityManager connectivityManager =
                    (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            connectivityManager.releaseNetworkRequest(mRequest);
            mRequest = null;
        }
    }
}
