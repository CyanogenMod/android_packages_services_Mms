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

import com.android.mms.service.exception.ApnException;
import com.android.mms.service.exception.MmsHttpException;
import com.android.mms.service.exception.MmsNetworkException;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.util.Log;

/**
 * Base class for MMS requests. This has the common logic of sending/downloading MMS.
 */
public abstract class MmsRequest {
    private static final int RETRY_TIMES = 3;

    protected static final String EXTRA_MESSAGE_REF = "messageref";

    /**
     * Interface for certain functionalities from MmsService
     */
    public static interface RequestManager {
        /**
         * Add a request to pending queue when it is executed by carrier app
         *
         * @param key The message ref key from carrier app
         * @param request The request in pending
         */
        public void addPending(int key, MmsRequest request);

        /**
         * Enqueue an MMS request for running
         *
         * @param request the request to enqueue
         */
        public void addRunning(MmsRequest request);
    }

    // The URI of persisted message
    protected Uri mMessageUri;
    // The reference to the pending requests manager (i.e. the MmsService)
    protected RequestManager mRequestManager;
    // The SIM id
    protected long mSubId;
    // The creator app
    protected String mCreator;

    // Intent result receiver for carrier app
    protected final BroadcastReceiver mCarrierAppResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(Telephony.Mms.Intents.MMS_SEND_ACTION) ||
                    action.equals(Telephony.Mms.Intents.MMS_DOWNLOAD_ACTION)) {
                Log.d(MmsService.TAG, "Carrier app result for " + action);
                final int rc = getResultCode();
                if (rc == Activity.RESULT_OK) {
                    // Handled by carrier app, waiting for result
                    Log.d(MmsService.TAG, "Sending/downloading MMS by IP pending.");
                    final Bundle resultExtras = getResultExtras(false);
                    if (resultExtras != null && resultExtras.containsKey(EXTRA_MESSAGE_REF)) {
                        final int ref = resultExtras.getInt(EXTRA_MESSAGE_REF);
                        Log.d(MmsService.TAG, "messageref = " + ref);
                        mRequestManager.addPending(ref, MmsRequest.this);
                    } else {
                        // Bad, no message ref provided
                        Log.e(MmsService.TAG, "Can't find messageref in result extras.");
                    }
                } else {
                    // No carrier app present, sending normally
                    Log.d(MmsService.TAG, "Sending/downloading MMS by IP failed.");
                    mRequestManager.addRunning(MmsRequest.this);
                }
            } else {
                Log.e(MmsService.TAG, "unexpected BroadcastReceiver action: " + action);
            }

        }
    };

    public MmsRequest(RequestManager requestManager, Uri messageUri, long subId, String creator) {
        mRequestManager = requestManager;
        mMessageUri = messageUri;
        mSubId = subId;
        mCreator = creator;
    }

    /**
     * Execute the request
     *
     * @param context The context
     * @param networkManager The network manager to use
     */
    public void execute(Context context, MmsNetworkManager networkManager) {
        int result = Activity.RESULT_OK;
        byte[] response = null;
        long retryDelay = 2;
        // Try multiple times of MMS HTTP request
        for (int i = 0; i < RETRY_TIMES; i++) {
            try {
                networkManager.acquireNetwork();
                try {
                    final ApnSettings apn = ApnSettings.load(context, null/*apnName*/, mSubId);
                    response = doHttp(context, apn);
                    result = Activity.RESULT_OK;
                    // Success
                    break;
                } finally {
                    networkManager.releaseNetwork();
                }
            } catch (ApnException e) {
                Log.e(MmsService.TAG, "MmsRequest: APN failure", e);
                result = SmsManager.MMS_ERROR_INVALID_APN;
                break;
            } catch (MmsNetworkException e) {
                Log.e(MmsService.TAG, "MmsRequest: MMS network acquiring failure", e);
                result = SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS;
                // Retry
            } catch (MmsHttpException e) {
                Log.e(MmsService.TAG, "MmsRequest: HTTP or network I/O failure", e);
                result = SmsManager.MMS_ERROR_HTTP_FAILURE;
                // Retry
            } catch (Exception e) {
                Log.e(MmsService.TAG, "MmsRequest: unexpected failure", e);
                result = SmsManager.MMS_ERROR_UNSPECIFIED;
                break;
            }
            try {
                Thread.sleep(retryDelay * 1000, 0/*nano*/);
            } catch (InterruptedException e) {}
            retryDelay <<= 1;
        }
        processResult(context, result, response);
    }

    /**
     * Process the result of the completed request, including updating the message status
     * in database and sending back the result via pending intents.
     *
     * @param context The context
     * @param result The result code of execution
     * @param response The response body
     */
    public void processResult(Context context, int result, byte[] response) {
        updateStatus(context, result, response);

        // Return MMS HTTP request result via PendingIntent
        final PendingIntent pendingIntent = getPendingIntent();
        if (pendingIntent != null) {
            // Extra information to send back with the pending intent
            Intent fillIn = new Intent();
            if (response != null) {
                fillIn.putExtra(SmsManager.MMS_EXTRA_DATA, response);
            }
            if (mMessageUri != null) {
                fillIn.putExtra("uri", mMessageUri.toString());
            }
            try {
                pendingIntent.send(context, result, fillIn);
            } catch (PendingIntent.CanceledException e) {
                Log.e(MmsService.TAG, "MmsRequest: sending pending intent canceled", e);
            }
        }
    }

    /**
     * Making the HTTP request to MMSC
     *
     * @param context The context
     * @param apn The APN setting
     * @return The HTTP response data
     * @throws MmsHttpException If any network error happens
     */
    protected abstract byte[] doHttp(Context context, ApnSettings apn) throws MmsHttpException;

    /**
     * @return The PendingIntent associate with the MMS sending invocation
     */
    protected abstract PendingIntent getPendingIntent();

    /**
     * @return The running queue should be used by this request
     */
    protected abstract int getRunningQueue();

    /**
     * Update database status of the message represented by this request
     *
     * @param context The context
     * @param result The result code of execution
     * @param response The response body
     */
    protected abstract void updateStatus(Context context, int result, byte[] response);
}
