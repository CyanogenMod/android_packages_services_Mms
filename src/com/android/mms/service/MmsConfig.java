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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.XmlResourceParser;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class manages a cached copy of current MMS configuration key values
 *
 * The steps to add a key
 * 1. Add a String constant for the key
 * 2. Add a default value for the key by putting a typed value to DEFAULTS
 *    (null means String type only)
 * 3. Add a getter for the key
 * 4. Add key/value for relevant mms_config.xml of a specific carrier (mcc/mnc)
 */
public class MmsConfig {
    private static final String TAG = MmsService.TAG;

    private static final String DEFAULT_HTTP_KEY_X_WAP_PROFILE = "x-wap-profile";

    private static final int MAX_IMAGE_HEIGHT = 480;
    private static final int MAX_IMAGE_WIDTH = 640;
    private static final int MAX_TEXT_LENGTH = 2000;

    /*
     * MmsConfig keys
     */
    public static final String CONFIG_ENABLED_MMS = "enabledMMS";
    // In case of single segment wap push message, this CONFIG_ENABLED_TRANS_ID indicates whether
    // TransactionID should be appended to URI or not.
    public static final String CONFIG_ENABLED_TRANS_ID = "enabledTransID";
    public static final String CONFIG_ENABLED_NOTIFY_WAP_MMSC = "enabledNotifyWapMMSC";
    public static final String CONFIG_ALIAS_ENABLED = "aliasEnabled";
    public static final String CONFIG_ALLOW_ATTACH_AUDIO = "allowAttachAudio";
    // If CONFIG_ENABLE_MULTIPART_SMS is true, long sms messages are always sent as multi-part sms
    // messages, with no checked limit on the number of segments.
    // If CONFIG_ENABLE_MULTIPART_SMS is false, then as soon as the user types a message longer
    // than a single segment (i.e. 140 chars), then the message will turn into and be sent
    // as an mms message or separate, independent SMS messages
    // (which is dependent on CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES flag).
    // This feature exists for carriers that don't support multi-part sms's.
    public static final String CONFIG_ENABLE_MULTIPART_SMS = "enableMultipartSMS";
    public static final String CONFIG_ENABLE_SMS_DELIVERY_REPORTS = "enableSMSDeliveryReports";
    // If CONFIG_ENABLE_GROUP_MMS is true, a message with multiple recipients,
    // regardless of contents, will be sent as a single MMS message with multiple "TO" fields set
    // for each recipient.
    // If CONFIG_ENABLE_GROUP_MMS is false, the group MMS setting/preference will be hidden
    // in the settings activity.
    public static final String CONFIG_ENABLE_GROUP_MMS = "enableGroupMms";
    // If this is true, then we should read the content_disposition field of an MMS part
    // Check wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21
    // Most carriers support it except Sprint.
    // There is a system resource defining it:
    // com.android.internal.R.bool.config_mms_content_disposition_support.
    // But Shem can not read it. Add here so that we can configure for different carriers.
    public static final String CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION =
            "supportMmsContentDisposition";
    // if true, show the cell broadcast (amber alert) in the SMS settings. Some carriers
    // don't want this shown.
    public static final String CONFIG_CELL_BROADCAST_APP_LINKS = "config_cellBroadcastAppLinks";
    // If this is true, we will send multipart SMS as separate SMS messages
    public static final String CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES =
            "sendMultipartSmsAsSeparateMessages";
    // FLAG(ywen): the following two is not supported yet.
    public static final String CONFIG_ENABLE_MMS_READ_REPORTS = "enableMMSReadReports";
    public static final String CONFIG_ENABLE_MMS_DELIVERY_REPORTS = "enableMMSDeliveryReports";
    public static final String CONFIG_MAX_MESSAGE_SIZE = "maxMessageSize"; // in bytes
    public static final String CONFIG_MAX_IMAGE_HEIGHT = "maxImageHeight"; // in pixels
    public static final String CONFIG_MAX_IMAGE_WIDTH = "maxImageWidth"; // in pixels
    public static final String CONFIG_RECIPIENT_LIMIT = "recipientLimit";
    public static final String CONFIG_HTTP_SOCKET_TIMEOUT = "httpSocketTimeout";
    public static final String CONFIG_ALIAS_MIN_CHARS = "aliasMinChars";
    public static final String CONFIG_ALIAS_MAX_CHARS = "aliasMaxChars";
    // If CONFIG_ENABLE_MULTIPART_SMS is true and CONFIG_SMS_TO_MMS_TEXT_THRESHOLD > 1,
    // then multi-part SMS messages will be converted into a single mms message.
    // For example, if the mms_config.xml file specifies <int name="smsToMmsTextThreshold">4</int>,
    // then on the 5th sms segment, the message will be converted to an mms.
    public static final String CONFIG_SMS_TO_MMS_TEXT_THRESHOLD = "smsToMmsTextThreshold";
    // LGU+ temporarily requires any SMS message longer than 80 bytes to be sent as MMS
    // see b/12122333
    public static final String CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD =
            "smsToMmsTextLengthThreshold";
    public static final String CONFIG_MAX_MESSAGE_TEXT_SIZE = "maxMessageTextSize";
    // maximum number of characters allowed for mms subject
    public static final String CONFIG_MAX_SUBJECT_LENGTH = "maxSubjectLength";
    public static final String CONFIG_UA_PROF_TAG_NAME = "uaProfTagName";
    public static final String CONFIG_USER_AGENT = "userAgent";
    public static final String CONFIG_UA_PROF_URL = "uaProfUrl";
    public static final String CONFIG_HTTP_PARAMS = "httpParams";
    // Email gateway alias support, including the master switch and different rules
    public static final String CONFIG_EMAIL_GATEWAY_NUMBER = "emailGatewayNumber";
    // String to append to the NAI header, e.g. ":pcs"
    public static final String CONFIG_NAI_SUFFIX = "naiSuffix";

    /*
     * Key types
     */
    public static final String KEY_TYPE_INT = "int";
    public static final String KEY_TYPE_BOOL = "bool";
    public static final String KEY_TYPE_STRING = "string";

    /*
     * Macro names
     */
    // The raw phone number from TelephonyManager.getLine1Number
    public static final String MACRO_LINE1 = "LINE1";
    // NAI (Network Access Identifier), used by Sprint for authentication
    public static final String MACRO_NAI = "NAI";

    // Default values. This is read-only. Don't write into it.
    // This provides the info on valid keys, their types and default values
    private static final Map<String, Object> DEFAULTS = new ConcurrentHashMap<String, Object>();
    // The current values
    private static final Map<String, Object> sKeyValues = new ConcurrentHashMap<String, Object>();
    static {
        DEFAULTS.put(CONFIG_ENABLED_MMS, Boolean.valueOf(true));
        DEFAULTS.put(CONFIG_ENABLED_TRANS_ID, Boolean.valueOf(false));
        DEFAULTS.put(CONFIG_ENABLED_NOTIFY_WAP_MMSC, Boolean.valueOf(false));
        DEFAULTS.put(CONFIG_ALIAS_ENABLED, Boolean.valueOf(false));
        DEFAULTS.put(CONFIG_ALLOW_ATTACH_AUDIO, Boolean.valueOf(true));
        DEFAULTS.put(CONFIG_ENABLE_MULTIPART_SMS, Boolean.valueOf(true));
        DEFAULTS.put(CONFIG_ENABLE_SMS_DELIVERY_REPORTS, Boolean.valueOf(true));
        DEFAULTS.put(CONFIG_ENABLE_GROUP_MMS, Boolean.valueOf(true));
        DEFAULTS.put(CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION, Boolean.valueOf(true));
        DEFAULTS.put(CONFIG_CELL_BROADCAST_APP_LINKS, Boolean.valueOf(true));
        DEFAULTS.put(CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES, Boolean.valueOf(false));
        DEFAULTS.put(CONFIG_ENABLE_MMS_READ_REPORTS, Boolean.valueOf(false));
        DEFAULTS.put(CONFIG_ENABLE_MMS_DELIVERY_REPORTS, Boolean.valueOf(false));
        DEFAULTS.put(CONFIG_MAX_MESSAGE_SIZE, Integer.valueOf(300 * 1024));
        DEFAULTS.put(CONFIG_MAX_IMAGE_HEIGHT, Integer.valueOf(MAX_IMAGE_HEIGHT));
        DEFAULTS.put(CONFIG_MAX_IMAGE_WIDTH, Integer.valueOf(MAX_IMAGE_WIDTH));
        DEFAULTS.put(CONFIG_RECIPIENT_LIMIT, Integer.valueOf(Integer.MAX_VALUE));
        DEFAULTS.put(CONFIG_HTTP_SOCKET_TIMEOUT, Integer.valueOf(60 * 1000));
        DEFAULTS.put(CONFIG_ALIAS_MIN_CHARS, Integer.valueOf(2));
        DEFAULTS.put(CONFIG_ALIAS_MAX_CHARS, Integer.valueOf(48));
        DEFAULTS.put(CONFIG_SMS_TO_MMS_TEXT_THRESHOLD, Integer.valueOf(-1));
        DEFAULTS.put(CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD, Integer.valueOf(-1));
        DEFAULTS.put(CONFIG_MAX_MESSAGE_TEXT_SIZE, Integer.valueOf(-1));
        DEFAULTS.put(CONFIG_MAX_SUBJECT_LENGTH, Integer.valueOf(40));
        DEFAULTS.put(CONFIG_UA_PROF_TAG_NAME, DEFAULT_HTTP_KEY_X_WAP_PROFILE);
        DEFAULTS.put(CONFIG_USER_AGENT, "");
        DEFAULTS.put(CONFIG_UA_PROF_URL, "");
        DEFAULTS.put(CONFIG_HTTP_PARAMS, "");
        DEFAULTS.put(CONFIG_EMAIL_GATEWAY_NUMBER, "");
        DEFAULTS.put(CONFIG_NAI_SUFFIX, "");
    }

    private static String mUserAgent = null;
    private static String mUaProfUrl = null;

    /**
     * Check a key and its type match the predefined keys and corresponding types
     *
     * @param key
     * @param type Including "int" "bool" and "string"
     * @return True if key and type both matches and false otherwise
     */
    public static boolean isValidKey(String key, String type) {
        if (!TextUtils.isEmpty(key) && DEFAULTS.containsKey(key)) {
            Object defVal = DEFAULTS.get(key);
            Class<?> valueType = defVal != null ? defVal.getClass() : String.class;
            if (KEY_TYPE_INT.equals(type)) {
                return valueType == Integer.class;
            } else if (KEY_TYPE_BOOL.equals(type)) {
                return valueType == Boolean.class;
            } else if (KEY_TYPE_STRING.equals(type)) {
                return valueType == String.class;
            }
        }
        return false;
    }

    /**
     * Check a key and its type match the predefined keys and corresponding types
     *
     * @param key The key of the config
     * @param value The value of the config
     * @return True if key and type both matches and false otherwise
     */
    public static boolean isValidValue(String key, Object value) {
        if (!TextUtils.isEmpty(key) && DEFAULTS.containsKey(key)) {
            Object defVal = DEFAULTS.get(key);
            Class<?> valueType = defVal != null ? defVal.getClass() : String.class;
            return value.getClass().equals(valueType);
        }
        return false;
    }

    /**
     * Get a config value by its type
     *
     * @param key The key of the config
     * @param type The type of the config value
     * @return The expected typed value or null if no match
     */
    public static Object getValueAsType(String key, String type) {
        if (isValidKey(key, type)) {
            return sKeyValues.get(key);
        }
        return null;
    }

    /**
     * Set a config value by its type (effected in memory, not persisted)
     *
     * @param key The key of the config
     * @param value The value of the config
     */
    public static void setValue(String key, Object value) {
        if (isValidValue(key, value)) {
            sKeyValues.put(key, value);
        }
    }

    public static void init(final Context context) {
        new Thread() {
            @Override
            public void run() {
                Configuration configuration = context.getResources().getConfiguration();
                // Always put the mnc/mcc in the log so we can tell which mms_config.xml was loaded.
                Log.d(TAG, "MmsConfig: mnc/mcc: " + configuration.mcc + "/" + configuration.mnc);
                loadMmsSettings(context);
            }
        }.start();
    }

    private static String getNullableStringValue(String key) {
        final Object value = sKeyValues.get(key);
        if (value != null) {
            return (String) value;
        }
        return null;
    }

    public static int getSmsToMmsTextThreshold() {
        return (Integer) sKeyValues.get(CONFIG_SMS_TO_MMS_TEXT_THRESHOLD);
    }

    public static int getSmsToMmsTextLengthThreshold() {
        return (Integer) sKeyValues.get(CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD);
    }

    public static boolean getMmsEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLED_MMS);
    }

    public static int getMaxMessageSize() {
        return (Integer) sKeyValues.get(CONFIG_MAX_MESSAGE_SIZE);
    }

    public static boolean getTransIdEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLED_TRANS_ID);
    }

    public static String getUserAgent() {
        return !TextUtils.isEmpty(mUserAgent) ?
                mUserAgent : getNullableStringValue(CONFIG_USER_AGENT);
    }

    public static String getUaProfTagName() {
        return getNullableStringValue(CONFIG_UA_PROF_TAG_NAME);
    }

    public static String getUaProfUrl() {
        return !TextUtils.isEmpty(mUaProfUrl) ?
                mUaProfUrl : getNullableStringValue(CONFIG_UA_PROF_URL);
    }

    public static String getHttpParams() {
        return getNullableStringValue(CONFIG_HTTP_PARAMS);
    }

    public static String getEmailGateway() {
        return getNullableStringValue(CONFIG_EMAIL_GATEWAY_NUMBER);
    }

    public static int getMaxImageHeight() {
        return (Integer) sKeyValues.get(CONFIG_MAX_IMAGE_HEIGHT);
    }

    public static int getMaxImageWidth() {
        return (Integer) sKeyValues.get(CONFIG_MAX_IMAGE_WIDTH);
    }

    public static int getRecipientLimit() {
        final int limit = (Integer) sKeyValues.get(CONFIG_RECIPIENT_LIMIT);
        return limit < 0 ? Integer.MAX_VALUE : limit;
    }

    public static int getMaxTextLimit() {
        final int max = (Integer) sKeyValues.get(CONFIG_MAX_MESSAGE_TEXT_SIZE);
        return max > -1 ? max : MAX_TEXT_LENGTH;
    }

    public static int getHttpSocketTimeout() {
        return (Integer) sKeyValues.get(CONFIG_HTTP_SOCKET_TIMEOUT);
    }

    public static boolean getMultipartSmsEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLE_MULTIPART_SMS);
    }

    public static boolean getSendMultipartSmsAsSeparateMessages() {
        return (Boolean) sKeyValues.get(CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES);
    }

    public static boolean getSMSDeliveryReportsEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLE_SMS_DELIVERY_REPORTS);
    }

    public static boolean getNotifyWapMMSC() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLED_NOTIFY_WAP_MMSC);
    }

    public static boolean isAliasEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ALIAS_ENABLED);
    }

    public static int getAliasMinChars() {
        return (Integer) sKeyValues.get(CONFIG_ALIAS_MIN_CHARS);
    }

    public static int getAliasMaxChars() {
        return (Integer) sKeyValues.get(CONFIG_ALIAS_MAX_CHARS);
    }

    public static boolean getAllowAttachAudio() {
        return (Boolean) sKeyValues.get(CONFIG_ALLOW_ATTACH_AUDIO);
    }

    public static int getMaxSubjectLength() {
        return (Integer) sKeyValues.get(CONFIG_MAX_SUBJECT_LENGTH);
    }

    public static boolean getGroupMmsEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLE_GROUP_MMS);
    }

    public static boolean getSupportMmsContentDisposition() {
        return (Boolean) sKeyValues.get(CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION);
    }

    public static boolean getShowCellBroadcast() {
        return (Boolean) sKeyValues.get(CONFIG_CELL_BROADCAST_APP_LINKS);
    }

    public static String getNaiSuffix() {
        return getNullableStringValue(CONFIG_NAI_SUFFIX);
    }

    public static boolean isMmsReadReportsEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLE_MMS_READ_REPORTS);
    }

    public static boolean isMmsDeliveryReportsEnabled() {
        return (Boolean) sKeyValues.get(CONFIG_ENABLE_MMS_DELIVERY_REPORTS);
    }

    public static void update(String key, String value, String type) {
        try {
            if (KEY_TYPE_INT.equals(type)) {
                sKeyValues.put(key, Integer.parseInt(value));
            } else if (KEY_TYPE_BOOL.equals(type)) {
                sKeyValues.put(key, Boolean.parseBoolean(value));
            } else if (KEY_TYPE_STRING.equals(type)){
                sKeyValues.put(key, value);
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "MmsConfig.update: invalid " + key + "," + value + "," + type);
        }
    }

    private static void loadDeviceUaSettings(Context context) {
        // load the MMS User agent and UaProfUrl from TelephonyManager APIs
        final TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mUserAgent = telephonyManager.getMmsUserAgent();
        mUaProfUrl = telephonyManager.getMmsUAProfUrl();
    }

    public static void loadMmsSettings(Context context) {
        // Load defaults
        sKeyValues.clear();
        sKeyValues.putAll(DEFAULTS);
        // Load User-Agent and UA profile URL settings
        loadDeviceUaSettings(context);
        Log.d(TAG, "MmsConfig: mUserAgent=" + mUserAgent + ", mUaProfUrl=" + mUaProfUrl);
        // Load mms_config.xml resource overlays
        loadFromResources(context);
        Log.v(TAG, "MmsConfig: all settings -- " + sKeyValues);
    }

    private static void loadFromResources(Context context) {
        Log.d(TAG, "MmsConfig.loadFromResources");
        final XmlResourceParser parser = context.getResources().getXml(R.xml.mms_config);
        final MmsConfigXmlProcessor processor = MmsConfigXmlProcessor.get(parser);
        processor.setMmsConfigHandler(new MmsConfigXmlProcessor.MmsConfigHandler() {
            @Override
            public void process(String key, String value, String type) {
                update(key, value, type);
            }
        });
        try {
            processor.process();
        } finally {
            parser.close();
        }
    }

    /**
     * Return the HTTP param macro value.
     * Example: LINE1 returns the phone number, etc.
     *
     * @param macro The macro name
     * @return The value of the defined macro
     */
    public static String getHttpParamMacro(Context context, String macro) {
        if (MACRO_LINE1.equals(macro)) {
            return getLine1(context);
        } else if (MACRO_NAI.equals(macro)) {
            return getNai();
        }
        return null;
    }

    /**
     * @return the phone number
     */
    private static String getLine1(Context context) {
        final TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        return telephonyManager.getLine1Number();
    }

    /**
     * @return the NAI (Network Access Identifier) from SystemProperties
     */
    private static String getNai() {
        String nai = SystemProperties.get("persist.radio.cdma.nai");
        if (!TextUtils.isEmpty(nai)) {
            String naiSuffix = MmsConfig.getNaiSuffix();
            if (!TextUtils.isEmpty(naiSuffix)) {
                nai = nai + naiSuffix;
            }
            byte[] encoded = null;
            try {
                encoded = Base64.encode(nai.getBytes("UTF-8"), Base64.NO_WRAP);
            } catch (UnsupportedEncodingException e) {
                encoded = Base64.encode(nai.getBytes(), Base64.NO_WRAP);
            }
            try {
                nai = new String(encoded, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                nai = new String(encoded);
            }
        }
        return nai;
    }
}
