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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.telephony.SubInfoRecord;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.util.List;
import java.util.Map;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.TelephonyIntents;

/**
 * This class manages cached copies of all the MMS configuration for each subscription ID.
 * A subscription ID loosely corresponds to a particular SIM. See the
 * {@link android.telephony.SubscriptionManager} for more details.
 *
 */
public class MmsConfigManager {
    private static final String TAG = MmsService.TAG;

    private static volatile MmsConfigManager sInstance = new MmsConfigManager();

    public static MmsConfigManager getInstance() {
        return sInstance;
    }

    // Map the various subIds to their corresponding MmsConfigs.
    private final Map<Long, MmsConfig> mSubIdConfigMap = new ArrayMap<Long, MmsConfig>();
    private Context mContext;

    /**
     * This receiver listens for changes made to SubInfoRecords and for a broadcast telling us
     * the TelephonyManager has loaded the information needed in order to get the mcc/mnc's for
     * each subscription Id. When either of these broadcasts are received, we rebuild the
     * MmsConfig table.
     *
     */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "mReceiver action: " + action);
            if (action.equals(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED) ||
                    action.equals(IccCardConstants.INTENT_VALUE_ICC_LOADED)) {
                loadInBackground();
            }
        }
    };

    public void init(final Context context) {
        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_SUBINFO_RECORD_UPDATED);
        context.registerReceiver(mReceiver, intentFilter);
        IntentFilter intentFilterLoaded =
                new IntentFilter(IccCardConstants.INTENT_VALUE_ICC_LOADED);
        context.registerReceiver(mReceiver, intentFilterLoaded);

        mContext = context;
        loadInBackground();
    }

    private void loadInBackground() {
        // TODO (ywen) - AsyncTask to avoid creating a new thread?
        new Thread() {
            @Override
            public void run() {
                Configuration configuration = mContext.getResources().getConfiguration();
                // Always put the mnc/mcc in the log so we can tell which mms_config.xml
                // was loaded.
                Log.d(TAG, "MmsConfigManager.loadInBackground(): mnc/mcc: " +
                        configuration.mcc + "/" + configuration.mnc);
                load(mContext);
            }
        }.start();
    }

    /**
     * Find and return the MmsConfig for a particular subscription id.
     *
     * @param subId Subscription id of the desired MmsConfig
     * @return MmsConfig for the particular subscription id. This function can return null if
     *         the MmsConfig cannot be found or if this function is called before the
     *         TelephonyManager has setup the SIMs or if loadInBackground is still spawning a
     *         thread after a recent ACTION_SUBINFO_RECORD_UPDATED event.
     */
    public MmsConfig getMmsConfigBySubId(long subId) {
        synchronized(mSubIdConfigMap) {
            return mSubIdConfigMap.get(subId);
        }
    }

    /**
     * This function goes through all the activated subscription ids (the actual SIMs in the
     * device), builds a context with that SIM's mcc/mnc and loads the appropriate mms_config.xml
     * file via the ResourceManager. With single-SIM devices, there will be a single subId.
     *
     */
    private void load(Context context) {
        List<SubInfoRecord> subs = SubscriptionManager.getActivatedSubInfoList(context);
        if (subs == null) {
            Log.d(TAG, "MmsConfigManager.load -- empty getActivatedSubInfoList");
            return;
        }
        // Load all the mms_config.xml files in a separate map and then swap with the
        // real map at the end so we don't block anyone sync'd on the real map.
        final Map<Long, MmsConfig> newConfigMap = new ArrayMap<Long, MmsConfig>();
        for (SubInfoRecord sub : subs) {
            // FLAG: when CL https://googleplex-android-review.git.corp.google.com/#/c/525401/
            // is checked in, re-enable this code (and see comment below)
            //                if (TextUtils.isEmpty(sub.mMccMnc)) {
            //                    Log.d(TAG, "MmsConfigManager.load -- no mMccMnc for sub: " + sub +
            //                            " skipping it");
            //                    continue;
            //                }
            //                int mcc, mnc;
            //                try {
            //                    mcc = Integer.parseInt(sub.mMccMnc.substring(0,3));
            //                    mnc = Integer.parseInt(sub.mMccMnc.substring(3));
            //                } catch (NumberFormatException e) {
            //                    Log.d(TAG, "MmsConfigManager.load -- couldn't parse mMccMnc for sub: " + sub +
            //                            " skipping it");
            //                    continue;
            //                }
            // FLAG: and then remove these three lines.
            Configuration origConfig = context.getResources().getConfiguration();
            int mcc = origConfig.mcc;
            int mnc = origConfig.mnc;

            Configuration configuration = new Configuration();
            configuration.mcc = mcc;
            configuration.mnc = mnc;
            Context subContext = context.createConfigurationContext(configuration);

            newConfigMap.put(sub.mSubId, new MmsConfig(subContext, sub.mSubId));
        }
        synchronized(mSubIdConfigMap) {
            mSubIdConfigMap.clear();
            mSubIdConfigMap.putAll(newConfigMap);
        }
    }

}
