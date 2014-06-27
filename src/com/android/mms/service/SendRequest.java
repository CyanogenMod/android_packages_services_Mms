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
import com.google.android.mms.pdu.SendConf;
import com.google.android.mms.pdu.SendReq;
import com.google.android.mms.util.SqliteWrapper;

import com.android.mms.service.exception.MmsHttpException;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteException;
import android.os.Binder;
import android.provider.Telephony;
import android.util.Log;

/**
 * Request to send an MMS
 */
public class SendRequest extends MmsRequest {
    private final byte[] mPdu;
    private final String mLocationUrl;
    private final PendingIntent mSentIntent;

    public SendRequest(byte[] pdu, String locationUrl, PendingIntent sentIntent) {
        mPdu = pdu;
        mLocationUrl = locationUrl;
        mSentIntent = sentIntent;
    }

    @Override
    protected byte[] doHttp(Context context, ApnSettings apn) throws MmsHttpException {
        return HttpUtils.httpConnection(
                context,
                MmsConfig.getNotifyWapMMSC() ? mLocationUrl : apn.getMmscUrl(),
                mPdu,
                HttpUtils.HTTP_POST_METHOD,
                apn.isProxySet(),
                apn.getProxyAddress(),
                apn.getProxyPort());
    }

    @Override
    protected PendingIntent getPendingIntent() {
        return mSentIntent;
    }

    @Override
    protected void preExecute(Context context) {
        if (mPdu == null) {
            Log.e(MmsService.TAG, "SendRequest.preExecute: empty PDU");
            return;
        }
        try {
            final GenericPdu pdu = (new PduParser(mPdu)).parse();
            if (pdu == null) {
                Log.e(MmsService.TAG, "SendRequest.preExecute: can't parse input PDU");
                return;
            }
            if (!(pdu instanceof SendReq)) {
                Log.d(MmsService.TAG, "SendRequest.preExecute: not SendReq");
                return;
            }
            final PduPersister persister = PduPersister.getPduPersister(context);
            mMessageUri = persister.persist(
                    pdu,
                    Telephony.Mms.Outbox.CONTENT_URI,
                    true/*createThreadId*/,
                    true/*groupMmsEnabled*/,
                    null/*preOpenedFiles*/);
        } catch (MmsException e) {
            Log.e(MmsService.TAG, "SendRequest.preExecute: can not persist message", e);
        } catch (RuntimeException e) {
            Log.e(MmsService.TAG, "SendRequest.preExecute: unexpected parsing failure", e);
        }
    }

    @Override
    protected void postExecute(Context context, int result, byte[] response) {
        if (mMessageUri == null) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final int messageStatus = result == Activity.RESULT_OK ?
                    Telephony.Mms.MESSAGE_BOX_SENT : Telephony.Mms.MESSAGE_BOX_FAILED;
            SendConf sendConf = null;
            if (response != null && response.length > 0) {
                final GenericPdu pdu = (new PduParser(response)).parse();
                if (pdu != null && pdu instanceof SendConf) {
                    sendConf = (SendConf) pdu;
                }
            }
            final ContentValues values = new ContentValues(5);
            values.put(Telephony.Mms.MESSAGE_BOX, messageStatus);
            values.put(Telephony.Mms.READ, 1);
            values.put(Telephony.Mms.SEEN, 1);
            if (sendConf != null) {
                values.put(Telephony.Mms.RESPONSE_STATUS, sendConf.getResponseStatus());
                values.put(Telephony.Mms.MESSAGE_ID,
                        PduPersister.toIsoString(sendConf.getMessageId()));
            }
            SqliteWrapper.update(context, context.getContentResolver(), mMessageUri, values,
                    null/*where*/, null/*selectionArg*/);
        } catch (SQLiteException e) {
            Log.e(MmsService.TAG, "SendRequest.postExecute: can not update message", e);
        } catch (RuntimeException e) {
            Log.e(MmsService.TAG, "SendRequest.postExecute: can not parse response", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
