/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SELinux;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.SearchIndexableResource;
import android.provider.Settings;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceGroup;
import android.telephony.CarrierConfigManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.settings.dashboard.SummaryLoader;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.search.Index;
import com.android.settings.search.Indexable;
import com.android.settingslib.DeviceInfoUtils;
import com.android.settingslib.RestrictedLockUtils;
import com.android.internal.os.RegionalizationEnvironment;
import com.android.internal.os.IRegionalizationService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

public class DeviceInfoSettings extends SettingsPreferenceFragment implements Indexable {

    private static final String LOG_TAG = "DeviceInfoSettings";

    private static final String KEY_MANUAL = "manual";
    private static final String KEY_REGULATORY_INFO = "regulatory_info";
    private static final String PROPERTY_URL_SAFETYLEGAL = "ro.url.safetylegal";
    private static final String PROPERTY_SELINUX_STATUS = "ro.build.selinux";
    private static final String KEY_KERNEL_VERSION = "kernel_version";
    private static final String KEY_BUILD_NUMBER = "build_number";
    private static final String KEY_DEVICE_MODEL = "device_model";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_SELINUX_STATUS = "selinux_status";
    private static final String KEY_BASEBAND_VERSION = "baseband_version";
    private static final String KEY_FIRMWARE_VERSION = "firmware_version";
    private static final String KEY_SECURITY_PATCH = "security_patch";
    private static final String KEY_EQUIPMENT_ID = "fcc_equipment_id";
    private static final String PROPERTY_EQUIPMENT_ID = "ro.ril.fccid";
    private static final String KEY_DEVICE_FEEDBACK = "device_feedback";
    private static final String KEY_SAFETY_LEGAL = "safetylegal";
    private static final String KEY_MBN_VERSION = "mbn_version";
    private static final String PROPERTY_MBN_VERSION = "persist.mbn.version";
    private static final String KEY_QGP_VERSION = "qgp_version";
    private static final String PROPERTY_QGP_VERSION = "persist.qgp.version";
    private static final String MBN_VERSION_PATH = "/persist/speccfg/mbnversion";
    private static final String QGP_VERSION_PATH = "/persist/speccfg/qgpversion";
    private static final String KEY_MOD_VERSION = "mod_version";
    private static final String KEY_MOD_BUILD_DATE = "build_date";
    private static final String KEY_MOD_API_LEVEL = "mod_api_level";

    static final int TAPS_TO_BE_A_DEVELOPER = 7;

    long[] mHits = new long[3];
    int mDevHitCountdown;
    Toast mDevHitToast;

    private UserManager mUm;

    private EnforcedAdmin mFunDisallowedAdmin;
    private boolean mFunDisallowedBySystem;
    private EnforcedAdmin mDebuggingFeaturesDisallowedAdmin;
    private boolean mDebuggingFeaturesDisallowedBySystem;
    private IRegionalizationService mRegionalizationService = null;

    @Override
    protected int getMetricsCategory() {
        return MetricsEvent.DEVICEINFO;
    }

    @Override
    protected int getHelpResource() {
        return R.string.help_uri_about;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mUm = UserManager.get(getActivity());

        addPreferencesFromResource(R.xml.device_info_settings);

        setStringSummary(KEY_FIRMWARE_VERSION, Build.VERSION.RELEASE);
        findPreference(KEY_FIRMWARE_VERSION).setEnabled(true);

        final String patch = DeviceInfoUtils.getSecurityPatch();
        if (!TextUtils.isEmpty(patch)) {
            setStringSummary(KEY_SECURITY_PATCH, patch);
        } else {
            getPreferenceScreen().removePreference(findPreference(KEY_SECURITY_PATCH));
        }

        setValueSummary(KEY_BASEBAND_VERSION, "gsm.version.baseband");
        setValueSummary(KEY_EQUIPMENT_ID, PROPERTY_EQUIPMENT_ID);
        setStringSummary(KEY_DEVICE_MODEL, Build.MODEL);
        setStringSummary(KEY_BUILD_NUMBER, Build.DISPLAY);
        findPreference(KEY_BUILD_NUMBER).setEnabled(true);
        //setValueSummary(KEY_QGP_VERSION, PROPERTY_QGP_VERSION);
        // Remove QGP Version if property is not present
        //removePreferenceIfPropertyMissing(getPreferenceScreen(), KEY_QGP_VERSION,
        //        PROPERTY_QGP_VERSION);
        String mQGPVersion = getQGPVersionValue();
        setStringSummary(KEY_QGP_VERSION, mQGPVersion);
        if(mQGPVersion == null){
            getPreferenceScreen().removePreference(findPreference(KEY_QGP_VERSION));
        }
        findPreference(KEY_KERNEL_VERSION).setSummary(DeviceInfoUtils.customizeFormatKernelVersion(
                getResources().getBoolean(R.bool.def_hide_kernel_version_name)));
        String mMbnVersion = getMBNVersionValue();
        setStringSummary(KEY_MBN_VERSION, mMbnVersion);
        if(mMbnVersion == null){
            getPreferenceScreen().removePreference(findPreference(KEY_MBN_VERSION));
        }
        findPreference(KEY_MOD_VERSION).setSummary(
                cyanogenmod.os.Build.CYANOGENMOD_DISPLAY_VERSION);
        findPreference(KEY_MOD_VERSION).setEnabled(true);
        setValueSummary(KEY_MOD_BUILD_DATE, "ro.build.date");
        setExplicitValueSummary(KEY_MOD_API_LEVEL, constructApiLevelString());
        findPreference(KEY_MOD_API_LEVEL).setEnabled(true);

        if (!SELinux.isSELinuxEnabled()) {
            String status = getResources().getString(R.string.selinux_status_disabled);
            setStringSummary(KEY_SELINUX_STATUS, status);
        } else if (!SELinux.isSELinuxEnforced()) {
            String status = getResources().getString(R.string.selinux_status_permissive);
            setStringSummary(KEY_SELINUX_STATUS, status);
        }

        setStringSummary(KEY_DEVICE_NAME, Build.PRODUCT);
        removePreferenceIfBoolFalse(KEY_DEVICE_NAME, R.bool.config_displayDeviceName);

        // Remove selinux information if property is not present
        removePreferenceIfPropertyMissing(getPreferenceScreen(), KEY_SELINUX_STATUS,
                PROPERTY_SELINUX_STATUS);

        // Remove Safety information preference if PROPERTY_URL_SAFETYLEGAL is not set
        removePreferenceIfPropertyMissing(getPreferenceScreen(), KEY_SAFETY_LEGAL,
                PROPERTY_URL_SAFETYLEGAL);

        // Remove Equipment id preference if FCC ID is not set by RIL
        removePreferenceIfPropertyMissing(getPreferenceScreen(), KEY_EQUIPMENT_ID,
                PROPERTY_EQUIPMENT_ID);

        // Remove Baseband version if wifi-only device
        if (Utils.isWifiOnly(getActivity())) {
            getPreferenceScreen().removePreference(findPreference(KEY_BASEBAND_VERSION));
        }

        // Dont show feedback option if there is no reporter.
        if (TextUtils.isEmpty(DeviceInfoUtils.getFeedbackReporterPackage(getActivity()))) {
            getPreferenceScreen().removePreference(findPreference(KEY_DEVICE_FEEDBACK));
        }

        /*
         * Settings is a generic app and should not contain any device-specific
         * info.
         */
        final Activity act = getActivity();

        // Remove manual entry if none present.
        removePreferenceIfBoolFalse(KEY_MANUAL, R.bool.config_show_manual);

        // Remove regulatory information if none present or config_show_regulatory_info is disabled
        final Intent intent = new Intent(Settings.ACTION_SHOW_REGULATORY_INFO);
        if (getPackageManager().queryIntentActivities(intent, 0).isEmpty()
                || !getResources().getBoolean(R.bool.config_show_regulatory_info)) {
            Preference pref = findPreference(KEY_REGULATORY_INFO);
            if (pref != null) {
                getPreferenceScreen().removePreference(pref);
            }
        }
        // Remove regulatory labels if no activity present to handle intent.
        removePreferenceIfActivityMissing(
                KEY_REGULATORY_INFO, Settings.ACTION_SHOW_REGULATORY_INFO);

        removePreferenceIfActivityMissing(
                "safety_info", "android.settings.SHOW_SAFETY_AND_REGULATORY_INFO");
    }

    @Override
    public void onResume() {
        super.onResume();
        mDevHitCountdown = getActivity().getSharedPreferences(DevelopmentSettings.PREF_FILE,
                Context.MODE_PRIVATE).getBoolean(DevelopmentSettings.PREF_SHOW,
                        android.os.Build.TYPE.equals("eng")) ? -1 : TAPS_TO_BE_A_DEVELOPER;
        mDevHitToast = null;
        mFunDisallowedAdmin = RestrictedLockUtils.checkIfRestrictionEnforced(
                getActivity(), UserManager.DISALLOW_FUN, UserHandle.myUserId());
        mFunDisallowedBySystem = RestrictedLockUtils.hasBaseUserRestriction(
                getActivity(), UserManager.DISALLOW_FUN, UserHandle.myUserId());
        mDebuggingFeaturesDisallowedAdmin = RestrictedLockUtils.checkIfRestrictionEnforced(
                getActivity(), UserManager.DISALLOW_DEBUGGING_FEATURES, UserHandle.myUserId());
        mDebuggingFeaturesDisallowedBySystem = RestrictedLockUtils.hasBaseUserRestriction(
                getActivity(), UserManager.DISALLOW_DEBUGGING_FEATURES, UserHandle.myUserId());
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference.getKey().equals(KEY_FIRMWARE_VERSION)
                || preference.getKey().equals(KEY_MOD_VERSION)) {
            System.arraycopy(mHits, 1, mHits, 0, mHits.length-1);
            mHits[mHits.length-1] = SystemClock.uptimeMillis();
            if (mHits[0] >= (SystemClock.uptimeMillis()-500)) {
                if (mUm.hasUserRestriction(UserManager.DISALLOW_FUN)) {
                    if (mFunDisallowedAdmin != null && !mFunDisallowedBySystem) {
                        RestrictedLockUtils.sendShowAdminSupportDetailsIntent(getActivity(),
                                mFunDisallowedAdmin);
                    }
                    Log.d(LOG_TAG, "Sorry, no fun for you!");
                    return false;
                }

                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.putExtra("is_lineage", preference.getKey().equals(KEY_MOD_VERSION));
                intent.setClassName("android",
                        com.android.internal.app.PlatLogoActivity.class.getName());
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Unable to start activity " + intent.toString());
                }
            }
        } else if (preference.getKey().equals(KEY_BUILD_NUMBER)) {
            // Don't enable developer options for secondary users.
            if (!mUm.isAdminUser()) return true;

            // Don't enable developer options until device has been provisioned
            if (!Utils.isDeviceProvisioned(getActivity())) {
                return true;
            }

            if (mUm.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)) {
                if (mDebuggingFeaturesDisallowedAdmin != null &&
                        !mDebuggingFeaturesDisallowedBySystem) {
                    RestrictedLockUtils.sendShowAdminSupportDetailsIntent(getActivity(),
                            mDebuggingFeaturesDisallowedAdmin);
                }
                return true;
            }

            if (mDevHitCountdown > 0) {
                mDevHitCountdown--;
                if (mDevHitCountdown == 0) {
                    getActivity().getSharedPreferences(DevelopmentSettings.PREF_FILE,
                            Context.MODE_PRIVATE).edit().putBoolean(
                                    DevelopmentSettings.PREF_SHOW, true).apply();
                    if (mDevHitToast != null) {
                        mDevHitToast.cancel();
                    }
                    mDevHitToast = Toast.makeText(getActivity(), R.string.show_dev_on_cm,
                            Toast.LENGTH_LONG);
                    mDevHitToast.show();
                    // This is good time to index the Developer Options
                    Index.getInstance(
                            getActivity().getApplicationContext()).updateFromClassNameResource(
                                    DevelopmentSettings.class.getName(), true, true);

                } else if (mDevHitCountdown > 0
                        && mDevHitCountdown < (TAPS_TO_BE_A_DEVELOPER-2)) {
                    if (mDevHitToast != null) {
                        mDevHitToast.cancel();
                    }
                    mDevHitToast = Toast.makeText(getActivity(), getResources().getQuantityString(
                            R.plurals.show_dev_countdown_cm, mDevHitCountdown, mDevHitCountdown),
                            Toast.LENGTH_SHORT);
                    mDevHitToast.show();
                }
            } else if (mDevHitCountdown < 0) {
                if (mDevHitToast != null) {
                    mDevHitToast.cancel();
                }
                mDevHitToast = Toast.makeText(getActivity(), R.string.show_dev_already_cm,
                        Toast.LENGTH_LONG);
                mDevHitToast.show();
            }
        } else if (preference.getKey().equals(KEY_SECURITY_PATCH)) {
            if (getPackageManager().queryIntentActivities(preference.getIntent(), 0).isEmpty()) {
                // Don't send out the intent to stop crash
                Log.w(LOG_TAG, "Stop click action on " + KEY_SECURITY_PATCH + ": "
                        + "queryIntentActivities() returns empty" );
                return true;
            }
        } else if (preference.getKey().equals(KEY_DEVICE_FEEDBACK)) {
            sendFeedback();
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void removePreferenceIfPropertyMissing(PreferenceGroup preferenceGroup,
            String preference, String property ) {
        if (SystemProperties.get(property).equals("")) {
            // Property is missing so remove preference from group
            try {
                preferenceGroup.removePreference(findPreference(preference));
            } catch (RuntimeException e) {
                Log.d(LOG_TAG, "Property '" + property + "' missing and no '"
                        + preference + "' preference");
            }
        }
    }

    private void removePreferenceIfActivityMissing(String preferenceKey, String action) {
        final Intent intent = new Intent(action);
        if (getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
            Preference pref = findPreference(preferenceKey);
            if (pref != null) {
                getPreferenceScreen().removePreference(pref);
            }
        }
    }

    private void removePreferenceIfBoolFalse(String preference, int resId) {
        if (!getResources().getBoolean(resId)) {
            Preference pref = findPreference(preference);
            if (pref != null) {
                getPreferenceScreen().removePreference(pref);
            }
        }
    }

    private void setStringSummary(String preference, String value) {
        try {
            findPreference(preference).setSummary(value);
        } catch (RuntimeException e) {
            findPreference(preference).setSummary(
                getResources().getString(R.string.device_info_default));
        }
    }

    private String getMBNVersionValue() {
        String mVersion = null;

        if (RegionalizationEnvironment.isSupported()) {
            mRegionalizationService = RegionalizationEnvironment.getRegionalizationService();
        }
        if(mRegionalizationService != null){
            try{
                if(!mRegionalizationService.checkFileExists(MBN_VERSION_PATH))
                    return null;
                if(mRegionalizationService.readFile(MBN_VERSION_PATH, "").size() > 0){
                    mVersion = mRegionalizationService.readFile(MBN_VERSION_PATH, "").get(0);
                }
                Log.d(LOG_TAG,"read MBNVersion="+mVersion);
            }catch (Exception e) {
                Log.e(LOG_TAG, "IOException:"+ e.getMessage());
            }
        }
        return mVersion;
    }

    private String getQGPVersionValue() {
        String mVersion = null;

        if (RegionalizationEnvironment.isSupported()) {
            mRegionalizationService = RegionalizationEnvironment.getRegionalizationService();
        }
        if(mRegionalizationService != null){
            try{
                if(!mRegionalizationService.checkFileExists(QGP_VERSION_PATH))
                    return null;
                if(mRegionalizationService.readFile(QGP_VERSION_PATH, "").size() > 0){
                    mVersion = mRegionalizationService.readFile(QGP_VERSION_PATH, "").get(0);
                }
                Log.d(LOG_TAG,"read QGPVersion="+mVersion);
            }catch (Exception e) {
                Log.e(LOG_TAG, "IOException:"+ e.getMessage());
            }
        }
        return mVersion;
    }
    private void setValueSummary(String preference, String property) {
        try {
            findPreference(preference).setSummary(
                    SystemProperties.get(property,
                            getResources().getString(R.string.device_info_default)));
        } catch (RuntimeException e) {
            // No recovery
        }
    }

    private void setExplicitValueSummary(String preference, String value) {
        try {
            findPreference(preference).setSummary(value);
        } catch (RuntimeException e) {
            // No recovery
        }
    }

    private void sendFeedback() {
        String reporterPackage = DeviceInfoUtils.getFeedbackReporterPackage(getActivity());
        if (TextUtils.isEmpty(reporterPackage)) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_BUG_REPORT);
        intent.setPackage(reporterPackage);
        startActivityForResult(intent, 0);
    }

    private static String constructApiLevelString() {
        int sdkInt = cyanogenmod.os.Build.CM_VERSION.SDK_INT;
        StringBuilder builder = new StringBuilder();
        builder.append(cyanogenmod.os.Build.getNameForSDKInt(sdkInt))
                .append(" (" + sdkInt + ")");
        return builder.toString();
    }

    private static class SummaryProvider implements SummaryLoader.SummaryProvider {

        private final Context mContext;
        private final SummaryLoader mSummaryLoader;

        public SummaryProvider(Context context, SummaryLoader summaryLoader) {
            mContext = context;
            mSummaryLoader = summaryLoader;
        }

        @Override
        public void setListening(boolean listening) {
            if (listening) {
                mSummaryLoader.setSummary(this, mContext.getString(R.string.about_summary,
                        Build.VERSION.RELEASE));
            }
        }
    }

    public static final SummaryLoader.SummaryProviderFactory SUMMARY_PROVIDER_FACTORY
            = new SummaryLoader.SummaryProviderFactory() {
        @Override
        public SummaryLoader.SummaryProvider createSummaryProvider(Activity activity,
                                                                   SummaryLoader summaryLoader) {
            return new SummaryProvider(activity, summaryLoader);
        }
    };

    /**
     * For Search.
     */
    public static final SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider() {

            @Override
            public List<SearchIndexableResource> getXmlResourcesToIndex(
                    Context context, boolean enabled) {
                final SearchIndexableResource sir = new SearchIndexableResource(context);
                sir.xmlResId = R.xml.device_info_settings;
                return Arrays.asList(sir);
            }

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                final List<String> keys = new ArrayList<String>();
                if (isPropertyMissing(PROPERTY_SELINUX_STATUS)) {
                    keys.add(KEY_SELINUX_STATUS);
                }
                if (isPropertyMissing(PROPERTY_URL_SAFETYLEGAL)) {
                    keys.add(KEY_SAFETY_LEGAL);
                }
                if (isPropertyMissing(PROPERTY_EQUIPMENT_ID)) {
                    keys.add(KEY_EQUIPMENT_ID);
                }
                // Remove Baseband version if wifi-only device
                if (Utils.isWifiOnly(context)) {
                    keys.add((KEY_BASEBAND_VERSION));
                }
                // Dont show feedback option if there is no reporter.
                if (TextUtils.isEmpty(DeviceInfoUtils.getFeedbackReporterPackage(context))) {
                    keys.add(KEY_DEVICE_FEEDBACK);
                }
                return keys;
            }

            private boolean isPropertyMissing(String property) {
                return SystemProperties.get(property).equals("");
            }
        };

}
