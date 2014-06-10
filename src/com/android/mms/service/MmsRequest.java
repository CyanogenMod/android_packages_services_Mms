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
import android.content.Context;
import android.content.Intent;
import android.telephony.MmsManager;
import android.util.Log;


/**
 * Base class for MMS requests. This has the common logic of sending/downloading MMS.
 */
public abstract class MmsRequest {
    private static final int RETRY_TIMES = 3;

    public void execute(Context context, MmsNetworkManager networkManager) {
        int result = Activity.RESULT_OK;
        byte[] response = null;
        long retryDelay = 2;
        // Try multiple times of MMS HTTP request
        for (int i = 0; i < RETRY_TIMES; i++) {
            try {
                networkManager.acquireNetwork();
                try {
                    final ApnSettings apn = ApnSettings.load(context, null/*apnName*/);
                    response = doHttp(context, apn);
                    result = Activity.RESULT_OK;
                    // Success
                    break;
                } finally {
                    networkManager.releaseNetwork();
                }
            } catch (ApnException e) {
                Log.e(MmsService.TAG, "MmsRequest: APN failure", e);
                result = MmsManager.RESULT_ERROR_INVALID_APN;
                break;
            } catch (MmsNetworkException e) {
                Log.e(MmsService.TAG, "MmsRequest: MMS network acquiring failure", e);
                result = MmsManager.RESULT_ERROR_UNABLE_CONNECT_MMS;
                // Retry
            } catch (MmsHttpException e) {
                Log.e(MmsService.TAG, "MmsRequest: HTTP or network I/O failure", e);
                result = MmsManager.RESULT_ERROR_HTTP_FAILURE;
                // Retry
            } catch (Exception e) {
                Log.e(MmsService.TAG, "MmsRequest: unexpected failure", e);
                result = MmsManager.RESULT_ERROR_UNSPECIFIED;
                break;
            }
            try {
                Thread.sleep(retryDelay * 1000, 0/*nano*/);
            } catch (InterruptedException e) {}
            retryDelay <<= 1;
        }
        // Return MMS HTTP request result via PendingIntent
        final PendingIntent pendingIntent = getPendingIntent();
        if (pendingIntent != null) {
            Intent extra = null;
            if (response != null) {
                extra = new Intent();
                extra.putExtra(MmsManager.EXTRA_DATA, response);
            }
            try {
                pendingIntent.send(context, result, extra);
            } catch (PendingIntent.CanceledException e) {
                Log.e(MmsService.TAG, "MmsRequest: sending pending intent canceled", e);
            }
        }
    }

    protected abstract byte[] doHttp(Context context, ApnSettings apn) throws MmsHttpException;

    protected abstract PendingIntent getPendingIntent();
}
