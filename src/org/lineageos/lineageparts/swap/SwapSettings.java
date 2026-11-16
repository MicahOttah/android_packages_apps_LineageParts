/*
 * Copyright (C) 2026 The LineageOS Project
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

package org.lineageos.lineageparts.swap;

import android.os.Bundle;
import android.os.Handler;
import android.os.SystemProperties;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.DropDownPreference;
import android.support.v7.preference.Preference;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import org.lineageos.lineageparts.R;
import org.lineageos.lineageparts.SettingsPreferenceFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Swap settings for the LG V521.
 *
 * This page holds no state of its own: every control reads and writes a
 * persist.v521.swap.* system property, and the v521swap daemon
 * (device/lge/v521/swap) applies them within a few seconds.  The daemon
 * reports back in v521.swap.status and v521.swap.usage.  See the "Swap"
 * section of device/lge/v521/README.md for the property contract.
 */
public class SwapSettings extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "SwapSettings";

    private static final String PROP_ENABLED = "persist.v521.swap.enabled";
    private static final String PROP_BACKEND = "persist.v521.swap.backend";
    private static final String PROP_SIZE = "persist.v521.swap.size";
    private static final String PROP_ZRAM_PRIO = "persist.v521.swap.zram_priority";
    private static final String PROP_SD_PRIO = "persist.v521.swap.sdcard_priority";
    private static final String PROP_RECLAIM = "persist.v521.swap.reclaim";
    private static final String PROP_SWAPPINESS = "persist.v521.swap.swappiness";
    private static final String PROP_STATUS = "v521.swap.status";
    private static final String PROP_USAGE = "v521.swap.usage";

    private static final String KEY_ENABLED = "swap_enabled";
    private static final String KEY_MODE = "swap_mode";
    private static final String KEY_ORDER = "swap_order";
    private static final String KEY_ZRAM_SIZE = "swap_zram_size";
    private static final String KEY_RECLAIM = "swap_reclaim";
    private static final String KEY_SWAPPINESS = "swap_swappiness";
    private static final String KEY_STATUS = "swap_status";
    private static final String KEY_SD_WARNING = "swap_sdcard_warning";

    private static final String MODE_ZRAM = "zram";
    private static final String MODE_SDCARD = "sdcard";
    private static final String MODE_BOTH = "both";

    private static final String ORDER_ZRAM_FIRST = "zram_first";
    private static final String ORDER_SDCARD_FIRST = "sdcard_first";
    private static final String ORDER_SPREAD = "spread";

    // The daemon's defaults (device/lge/v521/swap/v521swap.c)
    private static final int PRIO_HIGH = 1000;
    private static final int PRIO_LOW = 100;
    private static final long DEFAULT_ZRAM_SIZE = 512L * 1024 * 1024;

    private static final long STATUS_REFRESH_MS = 2000;

    private SwitchPreference mEnabled;
    private DropDownPreference mMode;
    private DropDownPreference mOrder;
    private DropDownPreference mZramSize;
    private DropDownPreference mReclaim;
    private DropDownPreference mSwappiness;
    private Preference mStatus;
    private Preference mSdWarning;

    private final Handler mHandler = new Handler();
    private final Runnable mUpdateEnabledStates = new Runnable() {
        @Override
        public void run() {
            updateEnabledStates();
        }
    };
    private final Runnable mRefreshStatus = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            mHandler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.swap_settings);

        mEnabled = (SwitchPreference) findPreference(KEY_ENABLED);
        mMode = (DropDownPreference) findPreference(KEY_MODE);
        mOrder = (DropDownPreference) findPreference(KEY_ORDER);
        mZramSize = (DropDownPreference) findPreference(KEY_ZRAM_SIZE);
        mReclaim = (DropDownPreference) findPreference(KEY_RECLAIM);
        mSwappiness = (DropDownPreference) findPreference(KEY_SWAPPINESS);
        mStatus = findPreference(KEY_STATUS);
        mSdWarning = findPreference(KEY_SD_WARNING);

        mEnabled.setOnPreferenceChangeListener(this);
        mMode.setOnPreferenceChangeListener(this);
        mOrder.setOnPreferenceChangeListener(this);
        mZramSize.setOnPreferenceChangeListener(this);
        mReclaim.setOnPreferenceChangeListener(this);
        mSwappiness.setOnPreferenceChangeListener(this);

        loadValues();
    }

    @Override
    public void onResume() {
        super.onResume();
        mHandler.post(mRefreshStatus);
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mRefreshStatus);
    }

    private void loadValues() {
        mEnabled.setChecked(SystemProperties.getBoolean(PROP_ENABLED, false));

        setChoice(mMode, SystemProperties.get(PROP_BACKEND, MODE_ZRAM), MODE_ZRAM);

        int zramPrio = SystemProperties.getInt(PROP_ZRAM_PRIO, PRIO_HIGH);
        int sdPrio = SystemProperties.getInt(PROP_SD_PRIO, PRIO_LOW);
        String order = zramPrio > sdPrio ? ORDER_ZRAM_FIRST
                : zramPrio < sdPrio ? ORDER_SDCARD_FIRST : ORDER_SPREAD;
        setChoice(mOrder, order, ORDER_ZRAM_FIRST);

        setChoice(mZramSize, String.valueOf(SystemProperties.getLong(PROP_SIZE,
                DEFAULT_ZRAM_SIZE)), String.valueOf(DEFAULT_ZRAM_SIZE));
        setChoice(mReclaim, SystemProperties.get(PROP_RECLAIM, "auto"), "auto");
        setChoice(mSwappiness, SystemProperties.get(PROP_SWAPPINESS, "100"), "100");

        updateEnabledStates();
    }

    /** Select value if it is one of the choices, otherwise the fallback. */
    private static void setChoice(DropDownPreference pref, String value, String fallback) {
        if (pref.findIndexOfValue(value) >= 0) {
            pref.setValue(value);
        } else {
            pref.setValue(fallback);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        boolean ok;
        if (preference == mEnabled) {
            ok = setProp(PROP_ENABLED, (Boolean) newValue ? "1" : "0");
        } else if (preference == mMode) {
            ok = setProp(PROP_BACKEND, (String) newValue);
        } else if (preference == mOrder) {
            String order = (String) newValue;
            int zramPrio = ORDER_SDCARD_FIRST.equals(order) ? PRIO_LOW : PRIO_HIGH;
            int sdPrio = ORDER_ZRAM_FIRST.equals(order) ? PRIO_LOW : PRIO_HIGH;
            if (ORDER_SPREAD.equals(order)) {
                zramPrio = sdPrio = PRIO_LOW;
            }
            ok = setProp(PROP_ZRAM_PRIO, String.valueOf(zramPrio))
                    && setProp(PROP_SD_PRIO, String.valueOf(sdPrio));
        } else if (preference == mZramSize) {
            ok = setProp(PROP_SIZE, (String) newValue);
        } else if (preference == mReclaim) {
            ok = setProp(PROP_RECLAIM, (String) newValue);
        } else if (preference == mSwappiness) {
            ok = setProp(PROP_SWAPPINESS, (String) newValue);
        } else {
            return false;
        }

        if (ok) {
            // The listener runs before the preference stores the new value.
            mHandler.post(mUpdateEnabledStates);
        }
        return ok;
    }

    private boolean setProp(String key, String value) {
        try {
            SystemProperties.set(key, value);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not set " + key + "=" + value, e);
            Toast.makeText(getActivity(), R.string.swap_status_failed,
                    Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void updateEnabledStates() {
        boolean enabled = mEnabled.isChecked();
        String mode = mMode.getValue();
        boolean usesZram = MODE_ZRAM.equals(mode) || MODE_BOTH.equals(mode);
        boolean usesSd = MODE_SDCARD.equals(mode) || MODE_BOTH.equals(mode);

        mMode.setEnabled(enabled);
        mOrder.setEnabled(enabled && MODE_BOTH.equals(mode));
        mOrder.setVisible(MODE_BOTH.equals(mode));
        mZramSize.setEnabled(enabled && usesZram);
        mReclaim.setEnabled(enabled);
        mSwappiness.setEnabled(enabled);
        mSdWarning.setVisible(usesSd);
    }

    /**
     * Turn the daemon's status ("off", "error:<reason>",
     * "on:zram=<bytes>,sdcard=<bytes>|missing|failed") and usage
     * ("zram=<used KiB>/<size KiB>,...") into one line per swap area.
     */
    private void updateStatus() {
        if (getActivity() == null) {
            return;
        }
        String status = SystemProperties.get(PROP_STATUS, "");
        String summary;

        if (status.isEmpty()) {
            summary = getString(R.string.swap_status_unavailable);
        } else if ("off".equals(status)) {
            summary = getString(R.string.swap_status_off);
        } else if ("error:no-sd-swap-partition".equals(status)) {
            summary = getString(R.string.swap_status_no_partition);
        } else if (status.startsWith("error:")) {
            summary = getString(R.string.swap_status_failed);
        } else {
            List<String> lines = new ArrayList<>();
            String usage = SystemProperties.get(PROP_USAGE, "");
            for (String item : status.substring(status.indexOf(':') + 1).split(",")) {
                String[] kv = item.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                String name = MODE_SDCARD.equals(kv[0])
                        ? getString(R.string.swap_name_sdcard)
                        : getString(R.string.swap_name_zram);
                long[] usedSize = parseUsage(usage, kv[0]);
                if (usedSize != null) {
                    lines.add(getString(R.string.swap_status_area, name,
                            Formatter.formatShortFileSize(getActivity(), usedSize[0] * 1024),
                            Formatter.formatShortFileSize(getActivity(), usedSize[1] * 1024)));
                } else {
                    lines.add(getString(R.string.swap_status_missing, name));
                }
            }
            summary = lines.isEmpty() ? status : TextUtils.join("\n", lines);
        }
        mStatus.setSummary(summary);
    }

    /** {used KiB, size KiB} of one backend from v521.swap.usage, or null. */
    private static long[] parseUsage(String usage, String backend) {
        for (String item : usage.split(",")) {
            String[] kv = item.split("=", 2);
            if (kv.length != 2 || !kv[0].equals(backend)) {
                continue;
            }
            String[] parts = kv[1].split("/", 2);
            if (parts.length != 2) {
                return null;
            }
            try {
                return new long[] { Long.parseLong(parts[0]), Long.parseLong(parts[1]) };
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
