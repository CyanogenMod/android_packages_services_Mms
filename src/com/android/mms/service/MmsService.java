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

import com.android.internal.telephony.mms.IMms;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * System service to process MMS API requests
 */
public class MmsService extends Service {
    private static final String LOG_TAG = "MmsService";
    private static final String SERVICE_NAME = "com.android.internal.telephony.mms.IMms";

    private IMms.Stub mStub = new IMms.Stub() {
        @Override
        public void sendMessage(byte[] pdu, PendingIntent sentIntent, PendingIntent deliveryIntent,
                PendingIntent readIntent) throws RemoteException {
            Log.d(LOG_TAG, "sendMessage");
        }

        @Override
        public void downloadMessage(String location, String transactionId,
                PendingIntent downloadedIntent) throws RemoteException {
            Log.d(LOG_TAG, "downloadMessage: " + location);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mStub;
    }

    public final IBinder asBinder() {
        return mStub;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(LOG_TAG, "onCreate");
        // Register this service with ServiceManager so that the MMS API code
        // can reference this service by name
        ServiceManager.addService(SERVICE_NAME, this.asBinder());
    }
}
