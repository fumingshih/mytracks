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

package com.google.android.apps.mytracks.services;

import static com.google.android.apps.mytracks.Constants.RESUME_TRACK_EXTRA_NAME;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.TrackDetailActivity;
import com.google.android.apps.mytracks.content.DescriptionGeneratorImpl;
import com.google.android.apps.mytracks.content.MyTracksLocation;
import com.google.android.apps.mytracks.content.MyTracksProvider;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Sensor;
import com.google.android.apps.mytracks.content.Sensor.SensorDataSet;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.content.WaypointCreationRequest;
import com.google.android.apps.mytracks.content.WaypointCreationRequest.WaypointType;
import com.google.android.apps.mytracks.services.sensors.SensorManager;
import com.google.android.apps.mytracks.services.sensors.SensorManagerFactory;
import com.google.android.apps.mytracks.services.tasks.AnnouncementPeriodicTaskFactory;
import com.google.android.apps.mytracks.services.tasks.PeriodicTaskExecutor;
import com.google.android.apps.mytracks.services.tasks.SplitPeriodicTaskFactory;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.apps.mytracks.stats.TripStatisticsUpdater;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.apps.mytracks.util.LocationUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.apps.mytracks.util.TrackNameUtils;
import com.google.android.maps.mytracks.R;
import com.google.common.annotations.VisibleForTesting;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.Process;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.TaskStackBuilder;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A background service that registers a location listener and records track
 * points. Track points are saved to the {@link MyTracksProvider}.
 * 
 * @author Leif Hendrik Wilden
 */
public class TrackRecordingService extends Service {

  private static final String TAG = TrackRecordingService.class.getSimpleName();
  public static final double PAUSE_LATITUDE = 100.0;
  public static final double RESUME_LATITUDE = 200.0;

  // One second in milliseconds
  private static final long ONE_SECOND = 1000;
  // One minute in milliseconds
  private static final long ONE_MINUTE = 60 * ONE_SECOND;
  @VisibleForTesting
  static final int MAX_AUTO_RESUME_TRACK_RETRY_ATTEMPTS = 3;

  // The following variables are set in onCreate:
  private Context context;
  private MyTracksProviderUtils myTracksProviderUtils;
  private MyTracksLocationManager myTracksLocationManager;
  private PeriodicTaskExecutor voiceExecutor;
  private PeriodicTaskExecutor splitExecutor;
  private ExecutorService executorService;
  private SharedPreferences sharedPreferences;
  private long recordingTrackId;
  private boolean recordingTrackPaused;
  private LocationListenerPolicy locationListenerPolicy;
  private int minRecordingDistance;
  private int maxRecordingDistance;
  private int minRequiredAccuracy;
  private int autoResumeTrackTimeout;
  private long currentRecordingInterval;

  // The following variables are set when recording:
  private TripStatisticsUpdater trackTripStatisticsUpdater;
  private TripStatisticsUpdater markerTripStatisticsUpdater;
  private WakeLock wakeLock;
  private SensorManager sensorManager;
  private Location lastLocation;
  private boolean currentSegmentHasLocation;

  // Timer to periodically invoke checkLocationListener
  private final Timer timer = new Timer();

  // Handler for the timer to post a runnable to the main thread
  private final Handler handler = new Handler();

  private ServiceBinder binder = new ServiceBinder(this);

  /*
   * Note that sharedPreferenceChangeListener cannot be an anonymous inner
   * class. Anonymous inner class will get garbage collected.
   */
  private final OnSharedPreferenceChangeListener
      sharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
          @Override
        public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
          if (key == null
              || key.equals(PreferencesUtils.getKey(context, R.string.recording_track_id_key))) {
            long trackId = PreferencesUtils.getLong(context, R.string.recording_track_id_key);
            /*
             * Only through the TrackRecordingService can one stop a recording
             * and set the recordingTrackId to -1L.
             */
            if (trackId != PreferencesUtils.RECORDING_TRACK_ID_DEFAULT) {
              recordingTrackId = trackId;
            }
          }
          if (key == null || key.equals(
              PreferencesUtils.getKey(context, R.string.recording_track_paused_key))) {
            recordingTrackPaused = PreferencesUtils.getBoolean(context,
                R.string.recording_track_paused_key,
                PreferencesUtils.RECORDING_TRACK_PAUSED_DEFAULT);
          }
          if (key == null
              || key.equals(PreferencesUtils.getKey(context, R.string.metric_units_key))) {
            boolean metricUnits = PreferencesUtils.getBoolean(
                context, R.string.metric_units_key, PreferencesUtils.METRIC_UNITS_DEFAULT);
            voiceExecutor.setMetricUnits(metricUnits);
            splitExecutor.setMetricUnits(metricUnits);
          }
          if (key == null
              || key.equals(PreferencesUtils.getKey(context, R.string.voice_frequency_key))) {
            voiceExecutor.setTaskFrequency(PreferencesUtils.getInt(
                context, R.string.voice_frequency_key, PreferencesUtils.VOICE_FREQUENCY_DEFAULT));
          }
          if (key == null
              || key.equals(PreferencesUtils.getKey(context, R.string.split_frequency_key))) {
            splitExecutor.setTaskFrequency(PreferencesUtils.getInt(
                context, R.string.split_frequency_key, PreferencesUtils.SPLIT_FREQUENCY_DEFAULT));
          }
          if (key == null || key.equals(
              PreferencesUtils.getKey(context, R.string.min_recording_interval_key))) {
            int minRecordingInterval = PreferencesUtils.getInt(context,
                R.string.min_recording_interval_key,
                PreferencesUtils.MIN_RECORDING_INTERVAL_DEFAULT);
            switch (minRecordingInterval) {
              case PreferencesUtils.MIN_RECORDING_INTERVAL_ADAPT_BATTERY_LIFE:
                // Choose battery life over moving time accuracy.
                locationListenerPolicy = new AdaptiveLocationListenerPolicy(
                    30 * ONE_SECOND, 5 * ONE_MINUTE, 5);
                break;
              case PreferencesUtils.MIN_RECORDING_INTERVAL_ADAPT_ACCURACY:
                // Get all the updates.
                locationListenerPolicy = new AdaptiveLocationListenerPolicy(
                    ONE_SECOND, 30 * ONE_SECOND, 0);
                break;
              default:
                locationListenerPolicy = new AbsoluteLocationListenerPolicy(
                    minRecordingInterval * ONE_SECOND);
            }
          }
          if (key == null || key.equals(
              PreferencesUtils.getKey(context, R.string.min_recording_distance_key))) {
            minRecordingDistance = PreferencesUtils.getInt(context,
                R.string.min_recording_distance_key,
                PreferencesUtils.MIN_RECORDING_DISTANCE_DEFAULT);
          }
          if (key == null || key.equals(
              PreferencesUtils.getKey(context, R.string.max_recording_distance_key))) {
            maxRecordingDistance = PreferencesUtils.getInt(context,
                R.string.max_recording_distance_key,
                PreferencesUtils.MAX_RECORDING_DISTANCE_DEFAULT);
          }
          if (key == null
              || key.equals(PreferencesUtils.getKey(context, R.string.min_required_accuracy_key))) {
            minRequiredAccuracy = PreferencesUtils.getInt(context,
                R.string.min_required_accuracy_key, PreferencesUtils.MIN_REQUIRED_ACCURACY_DEFAULT);
          }
          if (key == null || key.equals(
              PreferencesUtils.getKey(context, R.string.auto_resume_track_timeout_key))) {
            autoResumeTrackTimeout = PreferencesUtils.getInt(context,
                R.string.auto_resume_track_timeout_key,
                PreferencesUtils.AUTO_RESUME_TRACK_TIMEOUT_DEFAULT);
          }
        }
      };

  private LocationListener locationListener = new LocationListener() {
      @Override
    public void onProviderDisabled(String provider) {
      // Do nothing
    }

      @Override
    public void onProviderEnabled(String provider) {
      // Do nothing
    }

      @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
      // Do nothing
    }

      @Override
    public void onLocationChanged(final Location location) {
      if (myTracksLocationManager == null || executorService == null
          || !myTracksLocationManager.isAllowed() || executorService.isShutdown()
          || executorService.isTerminated()) {
        return;
      }
      executorService.submit(new Runnable() {
          @Override
        public void run() {
          onLocationChangedAsync(location);
        }
      });
    }
  };

  private TimerTask checkLocationListener = new TimerTask() {
      @Override
    public void run() {
      if (isRecording() && !isPaused()) {
        handler.post(new Runnable() {
          public void run() {
            registerLocationListener();
          }
        });
      }
    }
  };

  /*
   * Note that this service, through the AndroidManifest.xml, is configured to
   * allow both MyTracks and third party apps to invoke it. For the onCreate
   * callback, we cannot tell whether the caller is MyTracks or a third party
   * app, thus it cannot start/stop a recording or write/update MyTracks
   * database.
   */
  @Override
  public void onCreate() {
    super.onCreate();
    context = this;
    myTracksProviderUtils = MyTracksProviderUtils.Factory.get(this);
    myTracksLocationManager = new MyTracksLocationManager(this);
    voiceExecutor = new PeriodicTaskExecutor(this, new AnnouncementPeriodicTaskFactory());
    splitExecutor = new PeriodicTaskExecutor(this, new SplitPeriodicTaskFactory());
    executorService = Executors.newSingleThreadExecutor();
    sharedPreferences = getSharedPreferences(Constants.SETTINGS_NAME, Context.MODE_PRIVATE);
    sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);

    // onSharedPreferenceChanged might not set recordingTrackId.
    recordingTrackId = PreferencesUtils.RECORDING_TRACK_ID_DEFAULT;

    // Require announcementExecutor and splitExecutor to be created.
    sharedPreferenceChangeListener.onSharedPreferenceChanged(sharedPreferences, null);

    timer.schedule(checkLocationListener, 0, ONE_MINUTE);

    /*
     * Try to restart the previous recording track in case the service has been
     * restarted by the system, which can sometimes happen.
     */
    Track track = myTracksProviderUtils.getTrack(recordingTrackId);
    if (track != null) {
      restartTrack(track);
    } else {
      if (isRecording()) {
        Log.w(TAG, "track is null, but recordingTrackId not -1L. " + recordingTrackId);
        updateRecordingState(PreferencesUtils.RECORDING_TRACK_ID_DEFAULT, true);
      }
      showNotification();
    }
  }

  /*
   * Note that this service, through the AndroidManifest.xml, is configured to
   * allow both MyTracks and third party apps to invoke it. For the onStart
   * callback, we cannot tell whether the caller is MyTracks or a third party
   * app, thus it cannot start/stop a recording or write/update MyTracks
   * database.
   */
  @Override
  public void onStart(Intent intent, int startId) {
    handleStartCommand(intent, startId);
  }

  /*
   * Note that this service, through the AndroidManifest.xml, is configured to
   * allow both MyTracks and third party apps to invoke it. For the
   * onStartCommand callback, we cannot tell whether the caller is MyTracks or a
   * third party app, thus it cannot start/stop a recording or write/update
   * MyTracks database.
   */
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    handleStartCommand(intent, startId);
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onDestroy() {
    showNotification();

    sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener);
    checkLocationListener.cancel();
    checkLocationListener = null;
    timer.cancel();
    timer.purge();
    unregisterLocationListener();

    try {
      voiceExecutor.shutdown();
    } finally {
      voiceExecutor = null;
    }

    try {
      splitExecutor.shutdown();
    } finally {
      splitExecutor = null;
    }

    if (sensorManager != null) {
      SensorManagerFactory.releaseSystemSensorManager();
      sensorManager = null;
    }

    // Make sure we have no indirect references to this service.
    myTracksProviderUtils = null;
    myTracksLocationManager.close();
    myTracksLocationManager = null;
    binder.detachFromService();
    binder = null;

    // This should be the next to last operation
    releaseWakeLock();

    /*
     * Shutdown the executor service last to avoid sending events to a dead
     * executor.
     */
    executorService.shutdown();
    super.onDestroy();
  }

  /**
   * Returns true if the service is recording.
   */
  public boolean isRecording() {
    return recordingTrackId != PreferencesUtils.RECORDING_TRACK_ID_DEFAULT;
  }

  /**
   * Returns true if the current recording is paused.
   */
  public boolean isPaused() {
    return recordingTrackPaused;
  }

  /**
   * Gets the trip statistics.
   */
  public TripStatistics getTripStatistics() {
    if (trackTripStatisticsUpdater == null) {
      return null;
    }
    return trackTripStatisticsUpdater.getTripStatistics();
  }

  /**
   * Inserts a waypoint.
   * 
   * @param waypointCreationRequest the waypoint creation request
   * @return the waypoint id
   */
  public long insertWaypoint(WaypointCreationRequest waypointCreationRequest) {
    if (!isRecording() || isPaused()) {
      return -1L;
    }

    boolean isStatistics = waypointCreationRequest.getType() == WaypointType.STATISTICS;
    String name;
    if (waypointCreationRequest.getName() != null) {
      name = waypointCreationRequest.getName();
    } else {
      int nextWaypointNumber = myTracksProviderUtils.getNextWaypointNumber(
          recordingTrackId, isStatistics);
      if (nextWaypointNumber == -1) {
        nextWaypointNumber = 0;
      }
      name = getString(
          isStatistics ? R.string.marker_split_name_format : R.string.marker_name_format,
          nextWaypointNumber);
    }

    TripStatistics tripStatistics;
    String description;
    if (isStatistics) {
      long now = System.currentTimeMillis();
      markerTripStatisticsUpdater.updateTime(now);
      tripStatistics = markerTripStatisticsUpdater.getTripStatistics();
      markerTripStatisticsUpdater = new TripStatisticsUpdater(now);
      description = new DescriptionGeneratorImpl(this).generateWaypointDescription(tripStatistics);
    } else {
      tripStatistics = null;
      description = waypointCreationRequest.getDescription() != null ? waypointCreationRequest
          .getDescription()
          : "";
    }

    String category = waypointCreationRequest.getCategory() != null ? waypointCreationRequest
        .getCategory()
        : "";
    String icon = getString(
        isStatistics ? R.string.marker_statistics_icon_url : R.string.marker_waypoint_icon_url);
    int type = isStatistics ? Waypoint.TYPE_STATISTICS : Waypoint.TYPE_WAYPOINT;
    long duration;
    double length;
    Location location = getLastValidTrackPointInCurrentSegment(recordingTrackId);
    if (location != null && trackTripStatisticsUpdater != null) {
      TripStatistics stats = trackTripStatisticsUpdater.getTripStatistics();
      length = stats.getTotalDistance();
      duration = stats.getTotalTime();
    } else {
      if (!waypointCreationRequest.isTrackStatistics()) {
        return -1L;
      }
      // For track statistics, make it an impossible location
      location = new Location("");
      location.setLatitude(100);
      location.setLongitude(180);
      length = 0;
      duration = 0;
    }
    Waypoint waypoint = new Waypoint(name, description, category, icon, recordingTrackId, type,
        length, duration, -1L, -1L, location, tripStatistics);
    Uri uri = myTracksProviderUtils.insertWaypoint(waypoint);
    return Long.parseLong(uri.getLastPathSegment());
  }

  /**
   * Starts the service as a foreground service.
   * 
   * @param notification the notification for the foreground service
   */
  @VisibleForTesting
  protected void startForegroundService(Notification notification) {
    startForeground(1, notification);
  }

  /**
   * Stops the service as a foreground service.
   */
  @VisibleForTesting
  protected void stopForegroundService() {
    stopForeground(true);
  }

  /**
   * Handles start command.
   * 
   * @param intent the intent
   * @param startId the start id
   */
  private void handleStartCommand(Intent intent, int startId) {
    // Check if the service is called to resume track (from phone reboot)
    if (intent != null && intent.getBooleanExtra(RESUME_TRACK_EXTRA_NAME, false)) {
      if (!shouldResumeTrack()) {
        Log.i(TAG, "Stop resume track.");
        updateRecordingState(PreferencesUtils.RECORDING_TRACK_ID_DEFAULT, true);
        stopSelfResult(startId);
        return;
      }
    }
  }

  /**
   * Returns true if should resume.
   */
  private boolean shouldResumeTrack() {
    Track track = myTracksProviderUtils.getTrack(recordingTrackId);

    if (track == null) {
      Log.d(TAG, "Not resuming. Track is null.");
      return false;
    }
    int retries = PreferencesUtils.getInt(this, R.string.auto_resume_track_current_retry_key,
        PreferencesUtils.AUTO_RESUME_TRACK_CURRENT_RETRY_DEFAULT);
    if (retries >= MAX_AUTO_RESUME_TRACK_RETRY_ATTEMPTS) {
      Log.d(TAG, "Not resuming. Exceeded maximum retry attempts.");
      return false;
    }
    PreferencesUtils.setInt(this, R.string.auto_resume_track_current_retry_key, retries + 1);

    if (autoResumeTrackTimeout == PreferencesUtils.AUTO_RESUME_TRACK_TIMEOUT_NEVER) {
      Log.d(TAG, "Not resuming. Auto-resume track timeout set to never.");
      return false;
    } else if (autoResumeTrackTimeout == PreferencesUtils.AUTO_RESUME_TRACK_TIMEOUT_ALWAYS) {
      Log.d(TAG, "Resuming. Auto-resume track timeout set to always.");
      return true;
    }

    if (track.getTripStatistics() == null) {
      Log.d(TAG, "Not resuming. No trip statistics.");
      return false;
    }
    long stopTime = track.getTripStatistics().getStopTime();
    return stopTime > 0
        && (System.currentTimeMillis() - stopTime) <= autoResumeTrackTimeout * ONE_MINUTE;
  }

  /**
   * Starts a new track.
   * 
   * @return the track id
   */
  private long startNewTrack() {
    if (isRecording()) {
      Log.d(TAG, "Ignore startNewTrack. Already recording.");
      return -1L;
    }
    long now = System.currentTimeMillis();
    trackTripStatisticsUpdater = new TripStatisticsUpdater(now);
    markerTripStatisticsUpdater = new TripStatisticsUpdater(now);

    // Insert a track
    Track track = new Track();
    Uri uri = myTracksProviderUtils.insertTrack(track);
    long trackId = Long.parseLong(uri.getLastPathSegment());

    // Update shared preferences
    updateRecordingState(trackId, false);
    PreferencesUtils.setInt(this, R.string.auto_resume_track_current_retry_key, 0);

    // Update database
    track.setId(trackId);
    track.setName(TrackNameUtils.getTrackName(this, trackId, now, null));
    track.setCategory(PreferencesUtils.getString(
        this, R.string.default_activity_key, PreferencesUtils.DEFAULT_ACTIVITY_DEFAULT));
    track.setTripStatistics(trackTripStatisticsUpdater.getTripStatistics());
    myTracksProviderUtils.updateTrack(track);
    insertWaypoint(WaypointCreationRequest.DEFAULT_START_TRACK);

    startRecording(true);
    return trackId;
  }

  /**
   * Restart a track.
   * 
   * @param track the track
   */
  private void restartTrack(Track track) {
    Log.d(TAG, "Restarting track: " + track.getId());

    TripStatistics tripStatistics = track.getTripStatistics();
    trackTripStatisticsUpdater = new TripStatisticsUpdater(tripStatistics.getStartTime());

    long markerStartTime;
    Waypoint waypoint = myTracksProviderUtils.getLastStatisticsWaypoint(recordingTrackId);
    if (waypoint != null && waypoint.getTripStatistics() != null) {
      markerStartTime = waypoint.getTripStatistics().getStopTime();
    } else {
      markerStartTime = tripStatistics.getStartTime();
    }
    markerTripStatisticsUpdater = new TripStatisticsUpdater(markerStartTime);

    Cursor cursor = null;
    try {
      // TODO: how to handle very long track.
      cursor = myTracksProviderUtils.getTrackPointCursor(
          recordingTrackId, -1, Constants.MAX_LOADED_TRACK_POINTS, true);
      if (cursor == null) {
        Log.e(TAG, "Cursor is null.");
      } else {
        if (cursor.moveToLast()) {
          do {
            Location location = myTracksProviderUtils.createTrackPoint(cursor);
            trackTripStatisticsUpdater.addLocation(location, minRecordingDistance);
            if (location.getTime() > markerStartTime) {
              markerTripStatisticsUpdater.addLocation(location, minRecordingDistance);
            }
          } while (cursor.moveToPrevious());
        }
      }
    } catch (RuntimeException e) {
      Log.e(TAG, "RuntimeException", e);
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
    startRecording(true);
  }

  /**
   * Resumes current track.
   */
  private void resumeCurrentTrack() {
    if (!isRecording() || !isPaused()) {
      Log.d(TAG, "Ignore resumeCurrentTrack. Not recording or not paused.");
      return;
    }

    // Update shared preferences
    recordingTrackPaused = false;
    PreferencesUtils.setBoolean(this, R.string.recording_track_paused_key, false);

    // Update database
    Track track = myTracksProviderUtils.getTrack(recordingTrackId);
    if (track != null) {
      Location resume = new Location(LocationManager.GPS_PROVIDER);
      resume.setLongitude(0);
      resume.setLatitude(RESUME_LATITUDE);
      resume.setTime(System.currentTimeMillis());
      insertLocation(track, resume, null);
    }

    startRecording(false);
  }

  /**
   * Common code for starting a new track, resuming a track, or restarting after
   * phone reboot.
   * 
   * @param trackStarted true if track is started, false if track is resumed
   */
  private void startRecording(boolean trackStarted) {
    acquireWakeLock();

    // Update instance variables
    sensorManager = SensorManagerFactory.getSystemSensorManager(this);
    lastLocation = null;
    currentSegmentHasLocation = false;

    // Register notifications
    registerLocationListener();

    // Send notifications
    showNotification();
    sendTrackBroadcast(trackStarted ? R.string.track_started_broadcast_action
        : R.string.track_resumed_broadcast_action, recordingTrackId);

    // Restore periodic tasks
    voiceExecutor.restore();
    splitExecutor.restore();
  }

  /**
   * Ends the current track.
   */
  private void endCurrentTrack() {
    if (!isRecording()) {
      Log.d(TAG, "Ignore endCurrentTrack. Not recording.");
      return;
    }

    // Need to remember the recordingTrackId before setting it to -1L
    long trackId = recordingTrackId;
    boolean paused = recordingTrackPaused;

    // Update shared preferences
    updateRecordingState(PreferencesUtils.RECORDING_TRACK_ID_DEFAULT, true);

    // Update database
    Track track = myTracksProviderUtils.getTrack(trackId);
    if (track != null && !paused) {
      insertLocation(track, lastLocation, getLastValidTrackPointInCurrentSegment(trackId));
      updateRecordingTrack(track, myTracksProviderUtils.getLastTrackPointId(trackId), false);
    }

    endRecording(true, trackId);
    stopSelf();
  }

  /**
   * Gets the last valid track point in the current segment. Returns null if not available.
   * 
   * @param trackId the track id
   */
  private Location getLastValidTrackPointInCurrentSegment(long trackId) {
    if (!currentSegmentHasLocation) {
      return null;
    }
    return myTracksProviderUtils.getLastValidTrackPoint(trackId);
  }

  /**
   * Pauses the current track.
   */
  private void pauseCurrentTrack() {
    if (!isRecording() || isPaused()) {
      Log.d(TAG, "Ignore pauseCurrentTrack. Not recording or paused.");
      return;
    }

    // Update shared preferences
    recordingTrackPaused = true;
    PreferencesUtils.setBoolean(this, R.string.recording_track_paused_key, true);

    // Update database
    Track track = myTracksProviderUtils.getTrack(recordingTrackId);
    if (track != null) {
      insertLocation(track, lastLocation, getLastValidTrackPointInCurrentSegment(track.getId()));

      Location pause = new Location(LocationManager.GPS_PROVIDER);
      pause.setLongitude(0);
      pause.setLatitude(PAUSE_LATITUDE);
      pause.setTime(System.currentTimeMillis());
      insertLocation(track, pause, null);
    }

    endRecording(false, recordingTrackId);
  }

  /**
   * Common code for ending a track or pausing a track.
   * 
   * @param trackStopped true if track is stopped, false if track is paused
   * @param trackId the track id
   */
  private void endRecording(boolean trackStopped, long trackId) {

    // Shutdown periodic tasks
    voiceExecutor.shutdown();
    splitExecutor.shutdown();

    // Update instance variables
    if (sensorManager != null) {
      SensorManagerFactory.releaseSystemSensorManager();
      sensorManager = null;
    }
    lastLocation = null;

    // Unregister notifications
    unregisterLocationListener();

    // Send notifications
    showNotification();
    sendTrackBroadcast(trackStopped ? R.string.track_stopped_broadcast_action
        : R.string.track_paused_broadcast_action, trackId);

    releaseWakeLock();
  }

  /**
   * Updates the recording states.
   * 
   * @param trackId the recording track id
   * @param paused true if the recording is paused
   */
  private void updateRecordingState(long trackId, boolean paused) {
    recordingTrackId = trackId;
    PreferencesUtils.setLong(this, R.string.recording_track_id_key, trackId);
    recordingTrackPaused = paused;
    PreferencesUtils.setBoolean(this, R.string.recording_track_paused_key, recordingTrackPaused);
  }

  /**
   * Called when location changed.
   * 
   * @param location the location
   */
  private void onLocationChangedAsync(Location location) {
    try {
      if (!isRecording() || isPaused()) {
        Log.w(TAG, "Ignore onLocationChangedAsync. Not recording or paused.");
        return;
      }

      Track track = myTracksProviderUtils.getTrack(recordingTrackId);
      if (track == null) {
        Log.w(TAG, "Ignore onLocationChangedAsync. No track.");
        return;
      }

      if (!LocationUtils.isValidLocation(location)) {
        Log.w(TAG, "Ignore onLocationChangedAsync. location is invalid.");
        return;
      }

      if (location.getAccuracy() > minRequiredAccuracy) {
        Log.d(TAG, "Ignore onLocationChangedAsync. Poor accuracy.");
        return;
      }

      Location lastValidTrackPoint = getLastValidTrackPointInCurrentSegment(track.getId());
      long idleTime = lastValidTrackPoint != null ? location.getTime() - lastValidTrackPoint.getTime()
          : 0L;
      locationListenerPolicy.updateIdleTime(idleTime);
      if (currentRecordingInterval != locationListenerPolicy.getDesiredPollingInterval()) {
        registerLocationListener();
      }

      SensorDataSet sensorDataSet = getSensorDataSet();
      if (sensorDataSet != null) {
        location = new MyTracksLocation(location, sensorDataSet);
      }

      // Always insert the first segment location
      if (!currentSegmentHasLocation) {
        insertLocation(track, location, null);
        currentSegmentHasLocation = true;
        lastLocation = location;
        return;
      }

      if (!LocationUtils.isValidLocation(lastValidTrackPoint)) {
        /*
         * Should not happen. The current segment should have a location. Just
         * insert the current location.
         */
        insertLocation(track, location, null);
        lastLocation = location;
        return;
      }

      double distanceToLastTrackLocation = location.distanceTo(lastValidTrackPoint);
      if (distanceToLastTrackLocation < minRecordingDistance && sensorDataSet == null) {
        Log.d(TAG, "Not recording location due to min recording distance.");
      } else if (distanceToLastTrackLocation > maxRecordingDistance) {
        insertLocation(track, lastLocation, lastValidTrackPoint);
        Location pause = new Location(LocationManager.GPS_PROVIDER);
        pause.setLongitude(0);
        pause.setLatitude(PAUSE_LATITUDE);
        pause.setTime(lastLocation.getTime());
        insertLocation(track, pause, null);

        insertLocation(track, location, null);
      } else {
        /*
         * (distanceToLastTrackLocation >= minRecordingDistance ||
         * hasSensorData) && distanceToLastTrackLocation <= maxRecordingDistance
         */
        insertLocation(track, lastLocation, lastValidTrackPoint);
        insertLocation(track, location, null);
      }
      lastLocation = location;
    } catch (Error e) {
      Log.e(TAG, "Error in onLocationChangedAsync", e);
      throw e;
    } catch (RuntimeException e) {
      Log.e(TAG, "RuntimeException in onLocationChangedAsync", e);
      throw e;
    }
  }

  /**
   * Inserts a location.
   * 
   * @param track the track
   * @param location the location
   * @param lastValidTrackPoint the last valid track point, can be null
   */
  private void insertLocation(Track track, Location location, Location lastValidTrackPoint) {
    if (location == null) {
      Log.w(TAG, "Ignore insertLocation. loation is null.");
      return;
    }
    // Do not insert if inserted already
    if (lastValidTrackPoint != null && lastValidTrackPoint.getTime()  == location.getTime()) {
      Log.w(TAG, "Ignore insertLocation. location time same as last valid track point time.");
      return;
    }

    try {
      Uri uri = myTracksProviderUtils.insertTrackPoint(location, track.getId());
      long trackPointId = Long.parseLong(uri.getLastPathSegment());
      trackTripStatisticsUpdater.addLocation(location, minRecordingDistance);
      markerTripStatisticsUpdater.addLocation(location, minRecordingDistance);
      updateRecordingTrack(track, trackPointId, LocationUtils.isValidLocation(location));
    } catch (SQLiteException e) {
      /*
       * Insert failed, most likely because of SqlLite error code 5
       * (SQLite_BUSY). This is expected to happen extremely rarely (if our
       * listener gets invoked twice at about the same time).
       */
      Log.w(TAG, "SQLiteException", e);
    }
    voiceExecutor.update();
    splitExecutor.update();
    sendTrackBroadcast(R.string.track_update_broadcast_action, track.getId());
  }

  private void updateRecordingTrack(
      Track track, long trackPointId, boolean isTrackPointNewAndValid) {
    if (trackPointId >= 0) {
      if (track.getStartId() < 0) {
        track.setStartId(trackPointId);
      }
      track.setStopId(trackPointId);
    }
    if (isTrackPointNewAndValid) {
      track.setNumberOfPoints(track.getNumberOfPoints() + 1);
    }

    trackTripStatisticsUpdater.updateTime(System.currentTimeMillis());
    track.setTripStatistics(trackTripStatisticsUpdater.getTripStatistics());
    myTracksProviderUtils.updateTrack(track);
  }

  private SensorDataSet getSensorDataSet() {
    if (sensorManager == null || !sensorManager.isEnabled()
        || !sensorManager.isSensorDataSetValid()) {
      return null;
    }
    return sensorManager.getSensorDataSet();
  }

  /**
   * Registers the location listener.
   */
  private void registerLocationListener() {
    unregisterLocationListener();

    if (myTracksLocationManager == null) {
      Log.e(TAG, "locationManager is null.");
      return;
    }
    try {
      long interval = locationListenerPolicy.getDesiredPollingInterval();
      myTracksLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval,
          locationListenerPolicy.getMinDistance(), locationListener);
      currentRecordingInterval = interval;
    } catch (RuntimeException e) {
      Log.e(TAG, "Could not register location listener.", e);
    }
  }

  /**
   * Unregisters the location manager.
   */
  private void unregisterLocationListener() {
    if (myTracksLocationManager == null) {
      Log.e(TAG, "locationManager is null.");
      return;
    }
    myTracksLocationManager.removeUpdates(locationListener);
  }

  /**
   * Acquires the wake lock.
   */
  private void acquireWakeLock() {
    try {
      PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
      if (powerManager == null) {
        Log.e(TAG, "powerManager is null.");
        return;
      }
      if (wakeLock == null) {
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        if (wakeLock == null) {
          Log.e(TAG, "wakeLock is null.");
          return;
        }
      }
      if (!wakeLock.isHeld()) {
        wakeLock.acquire();
        if (!wakeLock.isHeld()) {
          Log.e(TAG, "Unable to hold wakeLock.");
        }
      }
    } catch (RuntimeException e) {
      Log.e(TAG, "Caught unexpected exception", e);
    }
  }

  /**
   * Releases the wake lock.
   */
  private void releaseWakeLock() {
    if (wakeLock != null && wakeLock.isHeld()) {
      wakeLock.release();
      wakeLock = null;
    }
  }

  /**
   * Shows the notification.
   */
  private void showNotification() {
    if (isRecording() && !isPaused()) {
      Intent intent = IntentUtils.newIntent(this, TrackDetailActivity.class)
          .putExtra(TrackDetailActivity.EXTRA_TRACK_ID, recordingTrackId);
      TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(this);
      taskStackBuilder.addNextIntent(intent);

      NotificationCompat.Builder builder = new NotificationCompat.Builder(this).setContentIntent(
          taskStackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT))
          .setContentText(getString(R.string.track_record_notification))
          .setContentTitle(getString(R.string.my_tracks_app_name)).setOngoing(true)
          .setSmallIcon(R.drawable.my_tracks_notification_icon).setWhen(System.currentTimeMillis());
      startForegroundService(builder.build());
    } else {
      stopForegroundService();
    }
  }

  /**
   * Sends track broadcast.
   * 
   * @param actionId the intent action id
   * @param trackId the track id
   */
  private void sendTrackBroadcast(int actionId, long trackId) {
    Intent intent = new Intent().setAction(getString(actionId))
        .putExtra(getString(R.string.track_id_broadcast_extra), trackId);
    sendBroadcast(intent, getString(R.string.permission_notification_value));
    if (PreferencesUtils.getBoolean(
        this, R.string.allow_access_key, PreferencesUtils.ALLOW_ACCESS_DEFAULT)) {
      sendBroadcast(intent, getString(R.string.broadcast_notifications_permission));
    }
  }

  /**
   * TODO: There is a bug in Android that leaks Binder instances. This bug is
   * especially visible if we have a non-static class, as there is no way to
   * nullify reference to the outer class (the service). A workaround is to use
   * a static class and explicitly clear service and detach it from the
   * underlying Binder. With this approach, we minimize the leak to 24 bytes per
   * each service instance. For more details, see the following bug:
   * http://code.google.com/p/android/issues/detail?id=6426.
   */
  private static class ServiceBinder extends ITrackRecordingService.Stub {
    private TrackRecordingService trackRecordingService;
    private DeathRecipient deathRecipient;

    public ServiceBinder(TrackRecordingService trackRecordingService) {
      this.trackRecordingService = trackRecordingService;
    }

    @Override
    public boolean isBinderAlive() {
      return trackRecordingService != null;
    }

    @Override
    public boolean pingBinder() {
      return isBinderAlive();
    }

    @Override
    public void linkToDeath(DeathRecipient recipient, int flags) {
      deathRecipient = recipient;
    }

    @Override
    public boolean unlinkToDeath(DeathRecipient recipient, int flags) {
      if (!isBinderAlive()) {
        return false;
      }
      deathRecipient = null;
      return true;
    }

    @Override
    public long startNewTrack() {
      if (!canAccess()) {
        return -1L;
      }
      return trackRecordingService.startNewTrack();
    }

    @Override
    public void pauseCurrentTrack() {
      if (!canAccess()) {
        return;
      }
      trackRecordingService.pauseCurrentTrack();
    }

    @Override
    public void resumeCurrentTrack() {
      if (!canAccess()) {
        return;
      }
      trackRecordingService.resumeCurrentTrack();
    }

    @Override
    public void endCurrentTrack() {
      if (!canAccess()) {
        return;
      }
      trackRecordingService.endCurrentTrack();
    }

    @Override
    public boolean isRecording() {
      if (!canAccess()) {
        return false;
      }
      return trackRecordingService.isRecording();
    }

    @Override
    public boolean isPaused() {
      if (!canAccess()) {
        return false;
      }
      return trackRecordingService.isPaused();
    }

    @Override
    public long getRecordingTrackId() {
      if (!canAccess()) {
        return -1L;
      }
      return trackRecordingService.recordingTrackId;
    }

    @Override
    public long getTotalTime() {
      if (!canAccess()) {
        return 0;
      }
      TripStatisticsUpdater updater = trackRecordingService.trackTripStatisticsUpdater;
      if (updater == null) {
        return 0;
      }
      if (!trackRecordingService.isPaused()) {
        updater.updateTime(System.currentTimeMillis());
      }
      return updater.getTripStatistics().getTotalTime();
    }

    @Override
    public long insertWaypoint(WaypointCreationRequest waypointCreationRequest) {
      if (!canAccess()) {
        return -1L;
      }
      return trackRecordingService.insertWaypoint(waypointCreationRequest);
    }

    @Override
    public void insertTrackPoint(Location location) {
      if (!canAccess()) {
        return;
      }
      trackRecordingService.locationListener.onLocationChanged(location);
    }

    @Override
    public byte[] getSensorData() {
      if (!canAccess()) {
        return null;
      }
      if (trackRecordingService.sensorManager == null) {
        Log.d(TAG, "sensorManager is null.");
        return null;
      }
      if (trackRecordingService.sensorManager.getSensorDataSet() == null) {
        Log.d(TAG, "Sensor data set is null.");
        return null;
      }
      return trackRecordingService.sensorManager.getSensorDataSet().toByteArray();
    }

    @Override
    public int getSensorState() {
      if (!canAccess()) {
        return Sensor.SensorState.NONE.getNumber();
      }
      if (trackRecordingService.sensorManager == null) {
        Log.d(TAG, "sensorManager is null.");
        return Sensor.SensorState.NONE.getNumber();
      }
      return trackRecordingService.sensorManager.getSensorState().getNumber();
    }

    /**
     * Returns true if the RPC caller is from the same application or if the
     * "Allow access" setting indicates that another app can invoke this
     * service's RPCs.
     */
    private boolean canAccess() {
      // As a precondition for access, must check if the service is available.
      if (trackRecordingService == null) {
        throw new IllegalStateException("The track recording service has been detached!");
      }
      if (Process.myPid() == Binder.getCallingPid()) {
        return true;
      } else {
        return PreferencesUtils.getBoolean(trackRecordingService, R.string.allow_access_key,
            PreferencesUtils.ALLOW_ACCESS_DEFAULT);
      }
    }

    /**
     * Detaches from the track recording service. Clears the reference to the
     * outer class to minimize the leak.
     */
    private void detachFromService() {
      trackRecordingService = null;
      attachInterface(null, null);

      if (deathRecipient != null) {
        deathRecipient.binderDied();
      }
    }
  }
}
