/*
 * Copyright 2011 Google Inc.
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
package com.google.android.apps.mytracks.maps;

import static com.google.android.apps.mytracks.Constants.TAG;

import com.google.android.apps.mytracks.Constants;
import com.google.android.maps.mytracks.R;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.util.Log;


/**
 * A fixed speed path descriptor.
 *
 * @author Vangelis S.
 */
public class FixedSpeedTrackPathDescriptor implements TrackPathDescriptor, OnSharedPreferenceChangeListener {
  private int slowSpeed;
  private int normalSpeed;
  private final Context context;
  
  public FixedSpeedTrackPathDescriptor(Context context) {
	
    this.context = context;
    context.getSharedPreferences(Constants.SETTINGS_NAME, 0)
        .registerOnSharedPreferenceChangeListener(this);
	
    SharedPreferences prefs = context.getSharedPreferences(Constants.SETTINGS_NAME, 0);
    if (prefs == null) {
      slowSpeed = 9; 
      normalSpeed = 17;
      return;
    }
    try {
      slowSpeed = Integer.parseInt(prefs.getString(context.getString(
          R.string.track_color_mode_fixed_speed_slow_key), "9"));
    } catch (NumberFormatException e) {
      slowSpeed = 9;
    }
    
    try {
      normalSpeed = Integer.parseInt(prefs.getString(context.getString(
          R.string.track_color_mode_fixed_speed_medium_key), "17")); 
    } catch (NumberFormatException e) {
      normalSpeed = 17;
    }
  }
  
  /**
   * Gets the slow speed for reference.
   * @return The speed limit considered as slow.
   */
  public int getSlowSpeed() {
    return slowSpeed;
  }
  
  /**
   * Gets the normal speed for reference.
   * @return The speed limit considered as normal.
   */
  public int getNormalSpeed() {
    return normalSpeed;
  }
  
  @Override
  public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
    Log.d(TAG, "FixedSpeedTrackPathDescriptor: onSharedPreferences changed " + key);
    if (key == null 
    	|| (!key.equals(context.getString(R.string.track_color_mode_fixed_speed_slow_key))
    	    && !key.equals(context.getString(R.string.track_color_mode_fixed_speed_medium_key)))) {
      return;
    }
    SharedPreferences prefs = context.getSharedPreferences(Constants.SETTINGS_NAME, 0);
    if (prefs == null) {
      slowSpeed = 9; 
      normalSpeed = 17;
      return;
    }
    
    try {
      slowSpeed = Integer.parseInt(prefs.getString(context.getString(
	      R.string.track_color_mode_fixed_speed_slow_key), "9"));
    } catch (NumberFormatException e) {
      slowSpeed = 9;
    }
    try {
      normalSpeed = Integer.parseInt(prefs.getString(context.getString(
	      R.string.track_color_mode_fixed_speed_medium_key), "17"));
    } catch (NumberFormatException e) {
      normalSpeed = 17;
    }
  }

  @Override
  public boolean needsRedraw() {
    return false;
  }
}