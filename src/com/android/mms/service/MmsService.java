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

import com.android.internal.telephony.IMms;

import android.Manifest;
import android.app.Activity;
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

import java.util.concurrent.ConcurrentHashMap;

/**
 * System service to process MMS API requests
 */
public class MmsService extends Service implements MmsRequest.RequestManager {
    public static final String TAG = "MmsService";

    public static final int QUEUE_INDEX_SEND = 0;
    public static final int QUEUE_INDEX_DOWNLOAD = 1;

    private static final String SERVICE_NAME = "imms";

    // Pending requests that are currently executed by carrier app
    // TODO: persist this in case MmsService crashes
    private final ConcurrentHashMap<Integer, MmsRequest> mPendingRequests =
            new ConcurrentHashMap<Integer, MmsRequest>();

    @Override
    public void addPending(int key, MmsRequest request) {
        mPendingRequests.put(key, request);
    }

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
        public void sendMessage(String callingPkg, byte[] pdu, String locationUrl,
                PendingIntent sentIntent) throws RemoteException {
            enforceCallingPermission(Manifest.permission.SEND_SMS, "Sending MMS message");
            Log.d(TAG, "sendMessage");
            final SendRequest request =
                    new SendRequest(MmsService.this, pdu, locationUrl, sentIntent);
            // Store the message in outbox first before sending
            request.storeInOutbox(MmsService.this);
            // Try sending via carrier app
            request.trySendingByCarrierApp(MmsService.this);
        }

        @Override
        public void downloadMessage(String callingPkg, String locationUrl,
                PendingIntent downloadedIntent) throws RemoteException {
            enforceCallingPermission(Manifest.permission.RECEIVE_MMS, "Downloading MMS message");
            Log.d(TAG, "downloadMessage: " + locationUrl);
            final DownloadRequest request =
                    new DownloadRequest(MmsService.this, locationUrl, downloadedIntent);
            // Try downloading via carrier app
            request.tryDownloadingByCarrierApp(MmsService.this);
        }

        @Override
        public void updateMmsSendStatus(int messageRef, boolean success) {
            Log.d(TAG, "updateMmsSendStatus: ref=" + messageRef + ", success=" + success);
            final MmsRequest request = mPendingRequests.get(messageRef);
            if (request != null) {
                if (success) {
                    // Sent successfully by carrier app, finalize the request
                    request.processResult(MmsService.this, Activity.RESULT_OK, null/*response*/);
                } else {
                    // Failed, try sending via carrier network
                    addRunning(request);
                }
            } else {
                // Really wrong here: can't find the request to update
                Log.e(TAG, "Failed to find the request to update send status");
            }
        }

        @Override
        public void updateMmsDownloadStatus(int messageRef, byte[] pdu) {
            Log.d(TAG, "updateMmsDownloadStatus: ref=" + messageRef
                    + ", pdu=" + (pdu == null ? null : pdu.length));
            final MmsRequest request = mPendingRequests.get(messageRef);
            if (request != null) {
                if (pdu != null) {
                    // Downloaded successfully by carrier app, finalize the request
                    request.processResult(MmsService.this, Activity.RESULT_OK, pdu);
                } else {
                    // Failed, try downloading via the carrier network
                    addRunning(request);
                }
            } else {
                // Really wrong here: can't find the request to update
                Log.e(TAG, "Failed to find the request to update download status");
            }
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

    @Override
    public void addRunning(MmsRequest request) {
        if (request == null) {
            return;
        }
        final int queue = request.getRunningQueue();
        startRequestQueueIfNeeded(queue);
        final Message message = Message.obtain();
        message.obj = request;
        mRequestQueues[queue].sendMessage(message);
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
}
