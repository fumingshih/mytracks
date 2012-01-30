/*
 * Copyright 2009 Google Inc.
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

package com.google.android.apps.mytracks.services.tasks;

import static com.google.android.apps.mytracks.Constants.TAG;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.services.TrackRecordingService;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.apps.mytracks.util.StringUtils;
import com.google.android.apps.mytracks.util.UnitConversions;
import com.google.android.maps.mytracks.R;
import com.google.common.annotations.VisibleForTesting;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.Locale;

/**
 * This class will periodically announce the user's trip statistics.
 *
 * @author Sandor Dornbush
 */
public class StatusAnnouncerTask implements PeriodicTask {

  /**
   * The rate at which announcements are spoken.
   */
  // @VisibleForTesting
  static final float TTS_SPEECH_RATE = 0.9f;

  /**
   * A pointer to the service context.
   */
  private final Context context;

  /**
   * The interface to the text to speech engine.
   */
  protected TextToSpeech tts;

  /**
   * The response received from the TTS engine after initialization.
   */
  private int initStatus = TextToSpeech.ERROR;

  /**
   * Whether the TTS engine is ready.
   */
  private boolean ready = false;

  /**
   * Whether we're allowed to speak right now.
   */
  private boolean speechAllowed;

  /**
   * Listener which updates {@link #speechAllowed} when the phone state changes.
   */
  private final PhoneStateListener phoneListener = new PhoneStateListener() {
    @Override
    public void onCallStateChanged(int state, String incomingNumber) {
      speechAllowed = state == TelephonyManager.CALL_STATE_IDLE;

      if (!speechAllowed && tts != null && tts.isSpeaking()) {
        // If we're already speaking, stop it.
        tts.stop();
      }
    }
  };

  public StatusAnnouncerTask(Context context) {
    this.context = context;
  }

  /**
   * {@inheritDoc}
   *
   * Announces the trip status.
   */
  @Override
  public void run(TrackRecordingService service) {
    if (service == null) {
      Log.e(TAG, "StatusAnnouncer TrackRecordingService not initialized");
      return;
    }

    runWithStatistics(service.getTripStatistics());
  }

  /**
   * This method exists as a convenience for testing code, allowing said code
   * to avoid needing to instantiate an entire {@link TrackRecordingService}
   * just to test the announcer.
   */
  // @VisibleForTesting
  void runWithStatistics(TripStatistics statistics) {
    if (statistics == null) {
      Log.e(TAG, "StatusAnnouncer stats not initialized.");
      return;
    }

    synchronized (this) {
      checkReady();
      if (!ready) {
        Log.e(TAG, "StatusAnnouncer Tts not ready.");
        return;
      }
    }

    if (!speechAllowed) {
      Log.i(Constants.TAG,
          "Not making announcement - not allowed at this time");
      return;
    }

    String announcement = getAnnouncement(statistics);
    Log.d(Constants.TAG, "Announcement: " + announcement);
    speakAnnouncement(announcement);
  }

  protected void speakAnnouncement(String announcement) {
    tts.speak(announcement, TextToSpeech.QUEUE_FLUSH, null);
  }

  /**
   * Builds the announcement string.
   *
   * @return The string that will be read to the user
   */
  // @VisibleForTesting
  protected String getAnnouncement(TripStatistics stats) {
    boolean metricUnits = true;
    boolean reportSpeed = true;
    SharedPreferences preferences = context.getSharedPreferences(
        Constants.SETTINGS_NAME, Context.MODE_PRIVATE);
    if (preferences != null) {
      metricUnits = preferences.getBoolean(context.getString(R.string.metric_units_key), true);
      reportSpeed = preferences.getBoolean(context.getString(R.string.report_speed_key), true);
    }

    double d =  stats.getTotalDistance() / 1000; // d is in kilometers
    double s =  stats.getAverageMovingSpeed() * 3.6; // s is in kilometers per hour
    
    if (d == 0) {
      return context.getString(R.string.voice_total_distance_zero);
    }

    if (!metricUnits) {
      d *= UnitConversions.KM_TO_MI;
      s *= UnitConversions.KMH_TO_MPH;
    }

    if (!reportSpeed) {
      s = 3600000.0 / s; // converts from speed to pace
    }

    // Makes sure s is not NaN.
    if (Double.isNaN(s)) {
      s = 0;
    } 
    
    String speed;
    if (reportSpeed) {
      int speedId = metricUnits ? R.plurals.voiceSpeedKilometersPerHour
          : R.plurals.voiceSpeedMilesPerHour;
      speed = context.getResources().getQuantityString(speedId, getQuantityCount(s), s);
    } else {
      int paceId = metricUnits ? R.string.voice_pace_per_kilometer : R.string.voice_pace_per_mile;
      speed = String.format(context.getString(paceId), getAnnounceTime((long) s));
    }

    int totalDistanceId = metricUnits ? R.plurals.voiceTotalDistanceKilometers
        : R.plurals.voiceTotalDistanceMiles;
    String totalDistance = context.getResources().getQuantityString(
        totalDistanceId, getQuantityCount(d), d);

    return context.getString(
        R.string.voice_template, totalDistance, getAnnounceTime(stats.getMovingTime()), speed);
  }
  
  /**
   * Gets the plural count to be used by getQuantityString. getQuantityString
   * only supports integer quantities, not a double quantity like "2.2".
   * <p>
   * As a temporary workaround, we convert a double quantity to an integer
   * quantity. If the double quantity is exactly 0, 1, or 2, then we can return
   * these integer quantities. Otherwise, we cast the double quantity to an
   * integer quantity. However, we need to make sure that if the casted value is
   * 0, 1, or 2, we don't return those, instead, return the next biggest integer
   * 3.
   *
   * @param d the double value
   */
  private int getQuantityCount(double d) {
    if (d == 0) {
      return 0;
    } else if (d == 1) {
      return 1;
    } else if (d == 2) {
      return 2;
    } else {
      int count = (int) d;
      return count < 3 ? 3 : count;
    }
  }
  
  @Override
  public void start() {
    Log.i(Constants.TAG, "Starting TTS");
    if (tts == null) {
      // We can't have this class also be the listener, otherwise it's unsafe to
      // reference it in Cupcake (even if we don't instantiate it).
      tts = newTextToSpeech(context, new OnInitListener() {
        @Override
        public void onInit(int status) {
          onTtsInit(status);
        }
      });
    }
    speechAllowed = true;

    // Register ourselves as a listener so we won't speak during a call.
    listenToPhoneState(phoneListener, PhoneStateListener.LISTEN_CALL_STATE);
  }

  /**
   * Called when the TTS engine is initialized.
   */
  private void onTtsInit(int status) {
    Log.i(TAG, "TrackRecordingService.TTS init: " + status);
    synchronized (this) {
      // TTS should be valid here but NPE exceptions were reported to the market.
      initStatus = status;
      checkReady();
    }
  }

  /**
   * Ensures that the TTS is ready (finishing its initialization if needed).
   */
  private void checkReady() {
    synchronized (this) {
      if (ready) {
        // Already done;
        return;
      }

      ready = initStatus == TextToSpeech.SUCCESS && tts != null;
      Log.d(TAG, "Status announcer ready: " + ready);

      if (ready) {
        onTtsReady();
      }
    }
  }

  /**
   * Finishes the TTS engine initialization.
   * Called once (and only once) when the TTS engine is ready.
   */
  protected void onTtsReady() {
    // Force the language to be the same as the string we will be speaking,
    // if that's available.
    Locale speechLanguage = Locale.getDefault();
    int languageAvailability = tts.isLanguageAvailable(speechLanguage);
    if (languageAvailability == TextToSpeech.LANG_MISSING_DATA ||
        languageAvailability == TextToSpeech.LANG_NOT_SUPPORTED) {
      // English is probably supported.
      // TODO: Somehow use announcement strings from English too.
      Log.w(TAG, "Default language not available, using English.");
      speechLanguage = Locale.ENGLISH;
    }
    tts.setLanguage(speechLanguage);

    // Slow down the speed just a bit as it is hard to hear when exercising.
    tts.setSpeechRate(TTS_SPEECH_RATE);
  }

  @Override
  public void shutdown() {
    // Stop listening to phone state.
    listenToPhoneState(phoneListener, PhoneStateListener.LISTEN_NONE);

    if (tts != null) {
      tts.shutdown();
      tts = null;
    }

    Log.i(Constants.TAG, "TTS shut down");
  }

  /**
   * Wrapper for instantiating a {@link TextToSpeech} object, which causes
   * several issues during testing.
   */
  // @VisibleForTesting
  protected TextToSpeech newTextToSpeech(Context ctx, OnInitListener onInitListener) {
    return new TextToSpeech(ctx, onInitListener);
  }

  /**
   * Wrapper for calls to the 100%-unmockable {@link TelephonyManager#listen}.
   */
  // @VisibleForTesting
  protected void listenToPhoneState(PhoneStateListener listener, int events) {
    TelephonyManager telephony =
        (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    if (telephony != null) {
      telephony.listen(listener, events);
    }
  }

  /**
   * Gets a string to announce the time.
   * 
   * @param time the time
   */
  @VisibleForTesting
  String getAnnounceTime(long time) {
    int[] parts = StringUtils.getTimeParts(time);
    String seconds = context.getResources().getQuantityString(
        R.plurals.voiceSeconds, parts[0], parts[0]);
    String minutes = context.getResources().getQuantityString(
        R.plurals.voiceMinutes, parts[1], parts[1]);
    String hours = context.getResources().getQuantityString(
        R.plurals.voiceHours, parts[2], parts[2]);

    StringBuilder sb = new StringBuilder();
    if (parts[2] != 0) {
      sb.append(hours);
      sb.append(" ");
      sb.append(minutes);
    } else {
      sb.append(minutes);
      sb.append(" ");
      sb.append(seconds);
    }
    return sb.toString();
  }
}
