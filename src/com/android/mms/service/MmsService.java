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

import android.Manifest;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * System service to process MMS API requests
 */
public class MmsService extends Service {
    public static final String TAG = "MmsService";
    private static final String SERVICE_NAME = "com.android.internal.telephony.mms.IMms";

    private static final int QUEUE_INDEX_SEND = 0;
    private static final int QUEUE_INDEX_DOWNLOAD = 1;

    /**
     * A thread-based request queue for executing the MMS requests in serial order
     */
    private class RequestQueue extends Handler {
        public RequestQueue(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            final MmsRequest request = (MmsRequest) msg.obj;
            if (request != null) {
                request.execute(MmsService.this, mMmsNetworkManager);
            }
        }
    }

    private IMms.Stub mStub = new IMms.Stub() {
        @Override
        public void sendMessage(byte[] pdu, String locationUrl, PendingIntent sentIntent)
                throws RemoteException {
            enforceCallingPermission(Manifest.permission.SEND_SMS, "Sending MMS message");
            Log.d(TAG, "sendMessage");
            enqueueRequest(QUEUE_INDEX_SEND, new SendRequest(pdu, locationUrl, sentIntent));
        }

        @Override
        public void downloadMessage(String locationUrl, PendingIntent downloadedIntent)
                throws RemoteException {
            enforceCallingPermission(Manifest.permission.SEND_SMS, "Downloading MMS message");
            Log.d(TAG, "downloadMessage: " + locationUrl);
            enqueueRequest(QUEUE_INDEX_DOWNLOAD,
                    new DownloadRequest(locationUrl, downloadedIntent));
        }
    };

    // Request queue threads
    // 0: send queue
    // 1: download queue
    private final RequestQueue[] mRequestQueues = new RequestQueue[2];

    // Manages MMS connectivity related stuff
    private final MmsNetworkManager mMmsNetworkManager = new MmsNetworkManager(this);

    /**
     * Lazy start the request queue threads
     *
     * @param queueIndex index of the queue to start
     */
    private void startRequestQueueIfNeeded(int queueIndex) {
        if (queueIndex < 0 || queueIndex >= mRequestQueues.length) {
            return;
        }
        synchronized (this) {
            if (mRequestQueues[queueIndex] == null) {
                final HandlerThread thread =
                        new HandlerThread("MmsService RequestQueue " + queueIndex);
                thread.start();
                mRequestQueues[queueIndex] = new RequestQueue(thread.getLooper());
            }
        }
    }

    /**
     * Enqueue an MMS request
     *
     * @param queueIndex the index of the queue
     * @param request the request to enqueue
     */
    private void enqueueRequest(int queueIndex, MmsRequest request) {
        startRequestQueueIfNeeded(queueIndex);
        final Message message = Message.obtain();
        message.obj = request;
        mRequestQueues[queueIndex].sendMessage(message);
    }

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
        Log.d(TAG, "onCreate");
        // Register this service with ServiceManager so that the MMS API code
        // can reference this service by name
        ServiceManager.addService(SERVICE_NAME, this.asBinder());
        // Load mms_config
        // TODO (ywen): make sure we start request queues after mms_config is loaded
        MmsConfig.init(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }
}
