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

import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.GenericPdu;
import com.google.android.mms.pdu.PduParser;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.RetrieveConf;
import com.google.android.mms.util.SqliteWrapper;

import com.android.mms.service.exception.MmsHttpException;

import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.os.Binder;
import android.provider.Telephony;
import android.util.Log;

/**
 * Request to download an MMS
 */
public class DownloadRequest extends MmsRequest {
    private final String mLocationUrl;
    private final PendingIntent mDownloadedIntent;

    public DownloadRequest(String locationUrl, PendingIntent downloadedIntent) {
        mLocationUrl = locationUrl;
        mDownloadedIntent = downloadedIntent;
    }

    @Override
    protected byte[] doHttp(Context context, ApnSettings apn) throws MmsHttpException {
        return HttpUtils.httpConnection(
                context,
                mLocationUrl,
                null/*pdu*/,
                HttpUtils.HTTP_GET_METHOD,
                apn.isProxySet(),
                apn.getProxyAddress(),
                apn.getProxyPort());
    }

    @Override
    protected PendingIntent getPendingIntent() {
        return mDownloadedIntent;
    }

    @Override
    protected void preExecute(Context context) {
        // Do nothing
    }

    @Override
    protected void postExecute(Context context, int result, byte[] response) {
        if (response == null || response.length < 1) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final GenericPdu pdu = (new PduParser(response)).parse();
            if (pdu == null || !(pdu instanceof RetrieveConf)) {
                Log.e(MmsService.TAG, "DownloadRequest.postExecute: invalid parsed PDU");
                return;
            }
            // Store the downloaded message
            final PduPersister persister = PduPersister.getPduPersister(context);
            mMessageUri = persister.persist(
                    pdu,
                    Telephony.Mms.Inbox.CONTENT_URI,
                    true/*createThreadId*/,
                    true/*groupMmsEnabled*/,
                    null/*preOpenedFiles*/);
            if (mMessageUri == null) {
                Log.e(MmsService.TAG, "DownloadRequest.postExecute: can not persist message");
                return;
            }
            // Update some of the properties of the message
            ContentValues values = new ContentValues(3);
            values.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L);
            values.put(Telephony.Mms.READ, 0);
            values.put(Telephony.Mms.SEEN, 0);
            SqliteWrapper.update(context, context.getContentResolver(), mMessageUri, values,
                    null/*where*/, null/*selectionArg*/);
        } catch (MmsException e) {
            Log.e(MmsService.TAG, "DownloadRequest.postExecute: can not persist message", e);
        } catch (SQLiteException e) {
            Log.e(MmsService.TAG, "DownloadRequest.postExecute: can not update message", e);
        } catch (RuntimeException e) {
            Log.e(MmsService.TAG, "DownloadRequest.postExecute: can not parse response", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
