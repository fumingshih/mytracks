/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.android.apps.mytracks;

import static com.google.android.apps.mytracks.Constants.TAG;

import com.google.android.apps.mytracks.io.backup.BackupActivityHelper;
import com.google.android.apps.mytracks.io.backup.BackupPreferencesListener;
import com.google.android.apps.mytracks.services.sensors.ant.AntUtils;
import com.google.android.apps.mytracks.services.tasks.StatusAnnouncerFactory;
import com.google.android.apps.mytracks.util.ApiFeatures;
import com.google.android.apps.mytracks.util.BluetoothDeviceUtils;
import com.google.android.apps.mytracks.util.UnitConversions;
import com.google.android.maps.mytracks.R;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An activity that let's the user see and edit the settings.
 * 
 * This activity has two entry points, "root" and "display" preference screen.
 * If bundle.getString("open_settings_screen") is set to "display_settings_screen_key", then
 * the "display" preference screen is shown, otherwise, the "root" display preference is shown.
 * 
 * @author Leif Hendrik Wilden
 * @author Rodrigo Damazio
 */
public class SettingsActivity extends PreferenceActivity {

  private BackupPreferencesListener backupListener;
  private SharedPreferences preferences;
  
  /** Called when the activity is first created. */
  @Override
  protected void onCreate(Bundle icicle) {
    super.onCreate(icicle);

    initActivityCommons();
   
    // If we only need the display setting screen nothing else needs to load.
    if (processIntent())
       return;
      
    initActivitySpecifics();
  }
  
  private void initActivityCommons() {
    // The volume we want to control is the Text-To-Speech volume
    ApiFeatures apiFeatures = ApiFeatures.getInstance();
    int volumeStream =
        new StatusAnnouncerFactory(apiFeatures).getVolumeStream();
    setVolumeControlStream(volumeStream);

    // Tell it where to read/write preferences
    PreferenceManager preferenceManager = getPreferenceManager();
    preferenceManager.setSharedPreferencesName(Constants.SETTINGS_NAME);
    preferenceManager.setSharedPreferencesMode(0);

    // Set up automatic preferences backup
    backupListener = apiFeatures.getApiAdapter().getBackupPreferencesListener(this);
    preferences = preferenceManager.getSharedPreferences();
    preferences.registerOnSharedPreferenceChangeListener(backupListener);

    // Load the preferences to be displayed
    addPreferencesFromResource(R.xml.preferences);

    // Disable voice announcement if not available
    if (!apiFeatures.hasTextToSpeech()) {
      IntegerListPreference announcementFrequency =
          (IntegerListPreference) findPreference(
              getString(R.string.announcement_frequency_key));
      announcementFrequency.setEnabled(false);
      announcementFrequency.setValue("-1");
      announcementFrequency.setSummary(
          R.string.settings_not_available_summary);
    }
    
    setMinRecordingIntervalOptions();
    setAutoResumeTimeoutOptions();
  }
  
  /**
   * Sets the display options for the min recording interval option.
   */
  private void setMinRecordingIntervalOptions() {
    String[] values = getResources().getStringArray(R.array.min_recording_interval_values);
    String[] options = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      if (values[i].equals("-2")) {
        options[i] = getString(R.string.min_recording_interval_adaptive_battery);
      } else if (values[i].equals("-1")) {
        options[i] = getString(R.string.min_recording_interval_adaptive_accuracy);
      } else if (values[i].equals("0")) {
        options[i] = getString(R.string.min_recording_interval_highest) + " ("
            + getString(R.string.settings_recommended) + ")";
      } else {
        int value = Integer.parseInt(values[i]);
        if (value < 60) {
          options[i] = value + " " + getString(R.string.second);
        } else {
          value = value / 60;
          options[i] = value + " " + getString(R.string.min);
        }
      }
    }
    ListPreference list = (ListPreference) findPreference(
        getString(R.string.min_recording_interval_key));
    list.setEntries(options);
  }
  
  /**
   * Sets the display options for the auto resume timeout option.
   */
  private void setAutoResumeTimeoutOptions() {
    String[] values = getResources().getStringArray(R.array.auto_resume_track_timeout_values);
    String[] options = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      if (values[i].equals("0")) {
        options[i] = getString(R.string.auto_resume_track_timeout_never);
      } else if (values[i].equals("-1")) {
        options[i] = getString(R.string.auto_resume_track_timeout_always);
      } else {
        options[i] = values[i] + " " + getString(R.string.min);
      }
    }
    ListPreference list = (ListPreference) findPreference(
        getString(R.string.auto_resume_track_timeout_key));
    list.setEntries(options);
  }
  
  private void initActivitySpecifics() {
    // Hook up switching of displayed list entries between metric and imperial
    // units
    CheckBoxPreference metricUnitsPreference =
        (CheckBoxPreference) findPreference(
            getString(R.string.metric_units_key));
    metricUnitsPreference.setOnPreferenceChangeListener(
        new OnPreferenceChangeListener() {
          @Override
          public boolean onPreferenceChange(Preference preference,
              Object newValue) {
            boolean isMetric = (Boolean) newValue;
            updateDisplayOptions(isMetric);
            return true;
          }
        });
    updateDisplayOptions(metricUnitsPreference.isChecked());

    customizeSensorOptionsPreferences();
    customizeTrackColorModePreferences();
    
    // Hook up action for resetting all settings
    Preference resetPreference = findPreference(getString(R.string.reset_key));
    resetPreference.setOnPreferenceClickListener(new OnPreferenceClickListener() {
      @Override
      public boolean onPreferenceClick(Preference arg0) {
        onResetPreferencesClick();
        return true;
      }
    });
    
    // Add a confirmation dialog for the "Allow access" preference.
    final CheckBoxPreference allowAccessPreference = (CheckBoxPreference) findPreference(
        getString(R.string.allow_access_key));
    allowAccessPreference.setOnPreferenceChangeListener(new OnPreferenceChangeListener() {
      @Override
      public boolean onPreferenceChange(Preference preference, Object newValue) {
        if ((Boolean) newValue) {
          AlertDialog dialog = new AlertDialog.Builder(SettingsActivity.this)
              .setCancelable(true)
              .setTitle(getString(R.string.settings_allow_access))
              .setMessage(getString(R.string.settings_allow_access_dialog_message))
              .setPositiveButton(android.R.string.ok, new OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int button) {
                  allowAccessPreference.setChecked(true);
                }
              })
              .setNegativeButton(android.R.string.cancel, null)
              .create();
          dialog.show();
          return false;
        } else {
          return true;
        }
      }
    });
  }
  
  private boolean processIntent() {
    boolean showDisplaySettings = false;
    Bundle bundle = getIntent().getExtras();
    PreferenceScreen preferenceScreen;
    String intentString = getString(R.string.open_settings_screen);
    
    if (bundle != null) {
      preferenceScreen = (PreferenceScreen) findPreference(bundle.getString(intentString));
      if (preferenceScreen != null) {
         showDisplaySettings = true;
         setPreferenceScreen(preferenceScreen);
      }
    }
 
    return showDisplaySettings;
  }

  private void customizeSensorOptionsPreferences() {
    ListPreference sensorTypePreference =
        (ListPreference) findPreference(getString(R.string.sensor_type_key));
    sensorTypePreference.setOnPreferenceChangeListener(
        new OnPreferenceChangeListener() {
          @Override
          public boolean onPreferenceChange(Preference preference,
              Object newValue) {
            updateSensorSettings((String) newValue);
            return true;
          }
        });
    updateSensorSettings(sensorTypePreference.getValue());

    if (!AntUtils.hasAntSupport(this)) {
      // The sensor options screen has a few ANT-specific options which we
      // need to remove.  First, we need to remove the ANT sensor types.
      // Second, we need to remove the ANT unpairing options.

      Set<Integer> toRemove = new HashSet<Integer>();

      String[] antValues = getResources().getStringArray(R.array.ant_sensor_type_values);
      for (String antValue : antValues) {
        toRemove.add(sensorTypePreference.findIndexOfValue(antValue));
      }

      CharSequence[] entries = sensorTypePreference.getEntries();
      CharSequence[] entryValues = sensorTypePreference.getEntryValues();

      CharSequence[] filteredEntries = new CharSequence[entries.length - toRemove.size()];
      CharSequence[] filteredEntryValues = new CharSequence[filteredEntries.length];
      for (int i = 0, last = 0; i < entries.length; i++) {
        if (!toRemove.contains(i)) {
          filteredEntries[last] = entries[i];
          filteredEntryValues[last++] = entryValues[i];
        }
      }

      sensorTypePreference.setEntries(filteredEntries);
      sensorTypePreference.setEntryValues(filteredEntryValues);

      PreferenceScreen sensorOptionsScreen =
          (PreferenceScreen) findPreference(getString(R.string.sensor_options_key));
      sensorOptionsScreen.removePreference(findPreference(getString(R.string.ant_options_key)));
    }
  }
  
  private void customizeTrackColorModePreferences() {
    ListPreference trackColorModePreference =
        (ListPreference) findPreference(getString(R.string.track_color_mode_key));
    trackColorModePreference.setOnPreferenceChangeListener(
        new OnPreferenceChangeListener() {
          @Override
          public boolean onPreferenceChange(Preference preference,
              Object newValue) {
            updateTrackColorModeSettings((String) newValue);
            return true;
          }
        });
    updateTrackColorModeSettings(trackColorModePreference.getValue());
    
    setTrackColorModePreferenceListeners();
    
    PreferenceCategory speedOptionsCategory =
        (PreferenceCategory) findPreference(getString(R.string.track_color_mode_fixed_speed_options_key));

    speedOptionsCategory.removePreference(findPreference(getString(R.string.track_color_mode_fixed_speed_slow_key)));
    speedOptionsCategory.removePreference(findPreference(getString(R.string.track_color_mode_fixed_speed_medium_key)));
  }

  @Override
  protected void onResume() {
    super.onResume();
    
    // If we only need the display setting screen nothing else needs to load.
    if (processIntent())
      return;

    configureBluetoothPreferences();
    Preference backupNowPreference =
        findPreference(getString(R.string.backup_to_sd_key));
    Preference restoreNowPreference =
        findPreference(getString(R.string.restore_from_sd_key));
    Preference resetPreference = findPreference(getString(R.string.reset_key));

    // If recording, disable backup/restore/reset
    // (we don't want to get to inconsistent states)
    boolean recording =
        preferences.getLong(getString(R.string.recording_track_key), -1) != -1;
    backupNowPreference.setEnabled(!recording);
    restoreNowPreference.setEnabled(!recording);
    resetPreference.setEnabled(!recording);
    backupNowPreference.setSummary(
        recording ? R.string.settings_not_while_recording
                  : R.string.settings_backup_to_sd_summary);
    restoreNowPreference.setSummary(
        recording ? R.string.settings_not_while_recording
                  : R.string.settings_restore_from_sd_summary);
    resetPreference.setSummary(
        recording ? R.string.settings_not_while_recording
                  : R.string.settings_reset_summary);

    // Add actions to the backup preferences
    backupNowPreference.setOnPreferenceClickListener(
        new OnPreferenceClickListener() {
          @Override
          public boolean onPreferenceClick(Preference preference) {
            BackupActivityHelper backupHelper =
                new BackupActivityHelper(SettingsActivity.this);
            backupHelper.writeBackup();
            return true;
          }
        });
    restoreNowPreference.setOnPreferenceClickListener(
        new OnPreferenceClickListener() {
          @Override
          public boolean onPreferenceClick(Preference preference) {
            BackupActivityHelper backupHelper =
                new BackupActivityHelper(SettingsActivity.this);
            backupHelper.restoreBackup();
            return true;
          }
        });
  }

  @Override
  protected void onDestroy() {
    getPreferenceManager().getSharedPreferences()
        .unregisterOnSharedPreferenceChangeListener(backupListener);

    super.onPause();
  }

  private void updateSensorSettings(String sensorType) {
    boolean usesBluetooth =
        getString(R.string.sensor_type_value_zephyr).equals(sensorType)
        || getString(R.string.sensor_type_value_polar).equals(sensorType);
    findPreference(
        getString(R.string.bluetooth_sensor_key)).setEnabled(usesBluetooth);
    findPreference(
        getString(R.string.bluetooth_pairing_key)).setEnabled(usesBluetooth);

    // Update the ANT+ sensors.
    // TODO: Only enable on phones that have ANT+.
    Preference antHrm = findPreference(getString(R.string.ant_heart_rate_sensor_id_key));
    Preference antSrm = findPreference(getString(R.string.ant_srm_bridge_sensor_id_key));
    if (antHrm != null && antSrm != null) {
      antHrm
          .setEnabled(getString(R.string.sensor_type_value_ant).equals(sensorType));
      antSrm
          .setEnabled(getString(R.string.sensor_type_value_srm_ant_bridge).equals(sensorType));
    }
  }

  private void updateTrackColorModeSettings(String trackColorMode) {
    boolean usesFixedSpeed = trackColorMode.equals(getString(R.string.track_color_mode_value_fixed));
    boolean usesDynamicSpeed = trackColorMode.equals(getString(R.string.track_color_mode_value_dynamic));
    
    findPreference(
        getString(R.string.track_color_mode_fixed_speed_slow_display_key)).setEnabled(usesFixedSpeed);
    findPreference(
        getString(R.string.track_color_mode_fixed_speed_medium_display_key)).setEnabled(usesFixedSpeed);
    findPreference(
        getString(R.string.track_color_mode_dynamic_speed_variation_key)).setEnabled(usesDynamicSpeed);
  }
  
  /**
   * Updates display options that depends on the preferred distance units, metric or imperial.
   *
   * @param isMetric true to use metric units, false to use imperial
   */
  private void updateDisplayOptions(boolean isMetric) {
    setTaskOptions(isMetric, R.string.announcement_frequency_key);
    setTaskOptions(isMetric, R.string.split_frequency_key);
    setMinDistanceOptions(isMetric, R.string.min_recording_distance_key);
    setMaxDistanceOptions(isMetric, R.string.max_recording_distance_key);
    setMinAccuracyOptions(isMetric, R.string.min_required_accuracy_key);
  }

  /**
   * Sets the display options for a periodic task.
   */
  private void setTaskOptions(boolean isMetric, int listId) {
    String distanceUnit = isMetric ? getString(R.string.kilometer) : getString(R.string.mile);
    String[] values = getResources().getStringArray(R.array.task_frequency_values);
    String[] options = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      if (values[i].equals("0")) {
        options[i] = getString(R.string.task_frequency_off);
      } else if (values[i].startsWith("-")) {
        options[i] = values[i].substring(1) + " " + distanceUnit;
      } else {
        options[i] = values[i] + " " + getString(R.string.minute);
      }
    }

    ListPreference list = (ListPreference) findPreference(getString(listId));
    list.setEntries(options);
  }
  
  /**
   * Sets the display options for min distance between points.
   */
  private void setMinDistanceOptions(boolean isMetric, int listId) {
    String unit = isMetric ? getString(R.string.meter) : getString(R.string.feet);
    String[] values = getResources().getStringArray(R.array.min_recording_distance_values);
    String[] options = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      int value = Integer.parseInt(values[i]);
      if (!isMetric) {
        value = (int) (value * UnitConversions.M_TO_FT);
      }
      options[i] = value + " " + unit;
      if (values[i].equals("5")) {
        options[i] += " (" + getString(R.string.settings_recommended) + ")";
      }
    }

    ListPreference list = (ListPreference) findPreference(getString(listId));
    list.setEntries(options);
  }
  
  /**
   * Sets the display options for max distance between points.
   */
  private void setMaxDistanceOptions(boolean isMetric, int listId) {
    String[] values = getResources().getStringArray(R.array.max_recording_distance_values);
    String[] options = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      int value = Integer.parseInt(values[i]);
      if (isMetric) {
        options[i] = value + " " + getString(R.string.meter);
      } else {
        value = (int) (value * UnitConversions.M_TO_FT);
        if (value < 2000) {
          options[i] = value + " " + getString(R.string.feet);
        } else {
          double mileValue = value / UnitConversions.MI_TO_FEET;
          mileValue = (int) (mileValue * 10) / 10.0;
          options[i] = mileValue + " " + getString(R.string.mile);
        }
      }
      if (values[i].equals("200")) {
        options[i] += " (" + getString(R.string.settings_recommended) + ")";
      }
    }

    ListPreference list = (ListPreference) findPreference(getString(listId));
    list.setEntries(options);
  }
  
  /**
   * Sets the display options for min accuracy.
   */
  private void setMinAccuracyOptions(boolean isMetric, int listId) {
    String[] values = getResources().getStringArray(R.array.min_required_accuracy_values);
    String[] options = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      int value = Integer.parseInt(values[i]);
      if (isMetric) {
        options[i] = value + " " + getString(R.string.meter);
      } else {
        value = (int) (value * UnitConversions.M_TO_FT);
        if (value < 2000) {
          options[i] = value + " " + getString(R.string.feet);
        } else {
          double mileValue = value / UnitConversions.MI_TO_FEET;
          mileValue = (int) (mileValue * 10) / 10.0;
          options[i] = mileValue + " " + getString(R.string.mile);
        }
      }
      if (values[i].equals("200")) {
        options[i] += " (" + getString(R.string.settings_recommended) + ")";
      } else if (values[i].equals("10")) {
        options[i] += " (" + getString(R.string.min_required_accuracy_excellent_gps) + ")";
      } else if (values[i].equals("5000")) {
        options[i] += " (" + getString(R.string.min_required_accuracy_poor_gps) + ")";
      }
    }

    ListPreference list = (ListPreference) findPreference(getString(listId));
    list.setEntries(options);
  }

  /**
   * Configures preference actions related to bluetooth.
   */
  private void configureBluetoothPreferences() {
    if (BluetoothDeviceUtils.isBluetoothMethodSupported()) {
      // Populate the list of bluetooth devices
      populateBluetoothDeviceList();

      // Make the pair devices preference go to the system preferences
      findPreference(getString(R.string.bluetooth_pairing_key))
          .setOnPreferenceClickListener(new OnPreferenceClickListener() {
            public boolean onPreferenceClick(Preference preference) {
              Intent settingsIntent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
              startActivity(settingsIntent);
              return false;
            }
          });
    }
  }

  /**
   * Populates the list preference with all available bluetooth devices.
   */
  private void populateBluetoothDeviceList() {
    // Build the list of entries and their values
    List<String> entries = new ArrayList<String>();
    List<String> entryValues = new ArrayList<String>();

    // The actual devices
    BluetoothDeviceUtils.getInstance().populateDeviceLists(entries, entryValues);

    CharSequence[] entriesArray = entries.toArray(new CharSequence[entries.size()]);
    CharSequence[] entryValuesArray = entryValues.toArray(new CharSequence[entryValues.size()]);
    ListPreference devicesPreference =
        (ListPreference) findPreference(getString(R.string.bluetooth_sensor_key));
    devicesPreference.setEntryValues(entryValuesArray);
    devicesPreference.setEntries(entriesArray);
  }

  /** Callback for when user asks to reset all settings. */
  private void onResetPreferencesClick() {
    AlertDialog dialog = new AlertDialog.Builder(this)
        .setCancelable(true)
        .setTitle(R.string.settings_reset)
        .setMessage(R.string.settings_reset_dialog_message)
        .setPositiveButton(android.R.string.ok,
            new OnClickListener() {
              @Override
              public void onClick(DialogInterface dialogInterface, int button) {
                onResetPreferencesConfirmed();
              }
            })
        .setNegativeButton(android.R.string.cancel, null)
        .create();
    dialog.show();
  }

  /** Callback for when user confirms resetting all settings. */
  private void onResetPreferencesConfirmed() {
    // Change preferences in a separate thread.
    new Thread() {
      @Override
      public void run() {
        Log.i(TAG, "Resetting all settings");

        // Actually wipe preferences (and save synchronously).
        preferences.edit().clear().commit();

        // Give UI feedback in the UI thread.
        runOnUiThread(new Runnable() {
          @Override
          public void run() {
            // Give feedback to the user.
            Toast.makeText(
                SettingsActivity.this,
                R.string.settings_reset_done,
                Toast.LENGTH_SHORT).show();

            // Restart the settings activity so all changes are loaded.
            Intent intent = getIntent();
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
          }
        });
      }
    }.start();
  }
  
  /** 
   * Set the given edit text preference text.
   * If the units are not metric convert the value before displaying.  
   */
  private void viewTrackColorModeSettings(EditTextPreference preference, int id) {
    CheckBoxPreference metricUnitsPreference = (CheckBoxPreference) findPreference(
        getString(R.string.metric_units_key));
    if(metricUnitsPreference.isChecked()) {
      return;
    }
    // Convert miles/h to km/h
    SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
    String metricspeed = prefs.getString(getString(id), null);
    int englishspeed;
    try {
      englishspeed = (int) (Double.parseDouble(metricspeed) * UnitConversions.KMH_TO_MPH);
    } catch (NumberFormatException e) {
      englishspeed = 0;
    }
    preference.getEditText().setText(String.valueOf(englishspeed));
  }
  
  /** 
   * Saves the given edit text preference value.
   * If the units are not metric convert the value before saving.  
   */
  private void validateTrackColorModeSettings(String newValue, int id) {
    CheckBoxPreference metricUnitsPreference = (CheckBoxPreference) findPreference(
        getString(R.string.metric_units_key));
    String metricspeed;
    if(!metricUnitsPreference.isChecked()) {
      // Convert miles/h to km/h
      try {
        metricspeed = String.valueOf((int) (Double.parseDouble(newValue) * UnitConversions.MPH_TO_KMH) + 1);
      } catch (NumberFormatException e) {
        metricspeed = "0";
      }
    } else {
      metricspeed = newValue;
    }
    SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
    prefs.edit().putString(getString(id), metricspeed).commit();
  }
  
  /** 
   * Sets the TrackColorMode preference listeners.
   */
  private void setTrackColorModePreferenceListeners() {
    setTrackColorModePreferenceListener(R.string.track_color_mode_fixed_speed_slow_display_key,
        R.string.track_color_mode_fixed_speed_slow_key);
    setTrackColorModePreferenceListener(R.string.track_color_mode_fixed_speed_medium_display_key,
        R.string.track_color_mode_fixed_speed_medium_key);
  }
  
  /** 
   * Sets a TrackColorMode preference listener.
   */
  private void setTrackColorModePreferenceListener(int displayKey, final int metricKey) {
    EditTextPreference trackColorModePreference =
        (EditTextPreference) findPreference(getString(displayKey));
    trackColorModePreference.setOnPreferenceChangeListener(
        new OnPreferenceChangeListener() {
          @Override
          public boolean onPreferenceChange(Preference preference,
              Object newValue) {
            validateTrackColorModeSettings((String) newValue, metricKey);
            return true;
          }
        });
    trackColorModePreference.setOnPreferenceClickListener(
        new OnPreferenceClickListener() {
          @Override
          public boolean onPreferenceClick(Preference preference) {
            viewTrackColorModeSettings((EditTextPreference) preference, metricKey);
            return true;
          }
        });
  }
}
