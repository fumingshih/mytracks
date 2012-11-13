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

package com.google.android.apps.mytracks.content;

import static com.google.android.apps.mytracks.Constants.MAX_DISPLAYED_WAYPOINTS_POINTS;
import static com.google.android.apps.mytracks.Constants.MAX_LOCATION_AGE_MS;
import static com.google.android.apps.mytracks.Constants.MAX_NETWORK_AGE_MS;
import static com.google.android.apps.mytracks.Constants.TARGET_DISPLAYED_TRACK_POINTS;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils.LocationIterator;
import com.google.android.apps.mytracks.content.TrackDataListener.LocationState;
import com.google.android.apps.mytracks.util.LocationUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.maps.mytracks.R;
import com.google.common.annotations.VisibleForTesting;

import android.content.Context;
import android.database.Cursor;
import android.hardware.GeomagneticField;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Track data hub. Receives data from {@link DataSource} and distributes it to
 * {@link TrackDataListener} after some processing.
 * 
 * @author Rodrigo Damazio
 */
public class TrackDataHub implements DataSourceListener {

  private static final String TAG = TrackDataHub.class.getSimpleName();

  // One hour in milliseconds
  private static final int ONE_HOUR = 60 * 60 * 1000;

  private final Context context;
  private final TrackDataManager trackDataManager;
  private final MyTracksProviderUtils myTracksProviderUtils;
  private final int targetNumPoints;

  private boolean started;
  private HandlerThread handlerThread;
  private Handler handler;
  private DataSource dataSource;
  private DataSourceManager dataSourceManager;

  // Preference values
  private long selectedTrackId;
  private long recordingTrackId;
  private boolean recordingTrackPaused;
  private boolean metricUnits;
  private boolean reportSpeed;
  private int minRequiredAccuracy;
  private int minRecordingDistance;

  // Heading values
  private float lastHeading = 0;
  private float lastDeclination = 0;
  private long lastDeclinationUpdate = 0;

  // Location values
  private Location lastSeenLocation = null;
  private boolean hasProviderEnabled = true;
  private boolean hasFix = false;
  private boolean hasGoodFix = false;

  // Track points sampling state
  private int numLoadedPoints;
  private long firstSeenLocationId;
  private long lastSeenLocationId;

  /**
   * Creates a new instance.
   */
  public synchronized static TrackDataHub newInstance(Context context) {
    return new TrackDataHub(context, new TrackDataManager(), MyTracksProviderUtils.Factory.get(
        context), TARGET_DISPLAYED_TRACK_POINTS);
  }

  /**
   * Constructor.
   * 
   * @param context the context
   * @param trackDataManager the track data manager
   * @param myTracksProviderUtils the my tracks provider utils
   * @param targetNumPoints the target number of points
   */
  @VisibleForTesting
  TrackDataHub(Context context, TrackDataManager trackDataManager,
      MyTracksProviderUtils myTracksProviderUtils, int targetNumPoints) {
    this.context = context;
    this.trackDataManager = trackDataManager;
    this.myTracksProviderUtils = myTracksProviderUtils;
    this.targetNumPoints = targetNumPoints;
    resetSamplingState();
  }

  /**
   * Starts.
   */
  public void start() {
    if (started) {
      Log.i(TAG, "TrackDataHub already started, ignoring start.");
      return;
    }
    started = true;
    handlerThread = new HandlerThread("TrackDataHubHandlerThread");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());
    dataSource = newDataSource();
    dataSourceManager = new DataSourceManager(dataSource, this);

    notifyPreferenceChanged(null);
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        dataSourceManager.updateListeners(trackDataManager.getRegisteredTrackDataTypes());
        loadDataForAll();
      }
    });
  }

  /**
   * Stops.
   */
  public void stop() {
    if (!started) {
      Log.i(TAG, "TrackDataHub not started, ignoring stop.");
      return;
    }
    started = false;

    dataSourceManager.unregisterAllListeners();
    handlerThread.getLooper().quit();

    handlerThread = null;
    handler = null;
    dataSource.close();
    dataSource = null;
    dataSourceManager = null;
  }

  /**
   * Loads a track.
   * 
   * @param trackId the track id
   */
  public void loadTrack(final long trackId) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        if (trackId == selectedTrackId) {
          Log.i(TAG, "Not reloading track " + trackId);
          return;
        }
        selectedTrackId = trackId;
        PreferencesUtils.setLong(context, R.string.selected_track_id_key, selectedTrackId);
        loadDataForAll();
      }
    });
  }

  /**
   * Registers a {@link TrackDataListener}.
   * 
   * @param trackDataListener the track data listener
   * @param trackDataTypes the track data types
   */
  public void registerTrackDataListener(
      final TrackDataListener trackDataListener, final EnumSet<TrackDataType> trackDataTypes) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        trackDataManager.registerListener(trackDataListener, trackDataTypes);
        dataSourceManager.updateListeners(trackDataManager.getRegisteredTrackDataTypes());
        loadDataForListener(trackDataListener);
      }
    });
  }

  /**
   * Unregisters a {@link TrackDataListener}.
   * 
   * @param trackDataListener the track data listener
   */
  public void unregisterTrackDataListener(final TrackDataListener trackDataListener) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        trackDataManager.unregisterListener(trackDataListener);
        dataSourceManager.updateListeners(trackDataManager.getRegisteredTrackDataTypes());
      }
    });
  }

  /**
   * Reloads data for a {@link TrackDataListener}.
   */
  public void reloadDataForListener(final TrackDataListener trackDataListener) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        loadDataForListener(trackDataListener);
      }
    });
  }

  /**
   * Forces update location and reports to all listeners.
   */
  public void forceUpdateLocation() {
    final Location location = dataSource.getLastKnownLocation();
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        notifyLocationChanged(
            location, false, trackDataManager.getListeners(TrackDataType.LOCATION));
      }
    });
  }

  public boolean isGpsProviderEnabled() {
    return dataSource.isGpsProviderEnabled();
  }

  /**
   * Returns true if the selected track is recording.
   */
  public boolean isSelectedTrackRecording() {
    return selectedTrackId == recordingTrackId
        && recordingTrackId != PreferencesUtils.RECORDING_TRACK_ID_DEFAULT;
  }

  /**
   * Returns true if the selected track is paused.
   */
  public boolean isSelectedTrackPaused() {
    return selectedTrackId == recordingTrackId && recordingTrackPaused;
  }

  @Override
  public void notifyTracksTableUpdated() {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        notifyTracksTableUpdate(trackDataManager.getListeners(TrackDataType.TRACKS_TABLE));
      }
    });
  }

  @Override
  public void notifyWaypointsTableUpdated() {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        notifyWaypointsTableUpdate(trackDataManager.getListeners(TrackDataType.WAYPOINTS_TABLE));
      }
    });
  }

  @Override
  public void notifyTrackPointsTableUpdated() {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        notifyTrackPointsTableUpdate(
            true, trackDataManager.getListeners(TrackDataType.SAMPLED_IN_TRACK_POINTS_TABLE),
            trackDataManager.getListeners(TrackDataType.SAMPLED_OUT_TRACK_POINTS_TABLE));
      }
    });
  }

  @Override
  public void notifyPreferenceChanged(final String key) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        if (key == null
            || key.equals(PreferencesUtils.getKey(context, R.string.selected_track_id_key))) {
          long trackId = PreferencesUtils.getLong(context, R.string.selected_track_id_key);
          boolean hasChanged = trackId != selectedTrackId;
          selectedTrackId = trackId;
          if (key != null) {
            if (hasChanged) {
              loadDataForAll();
            }
          }
        }
        if (key == null
            || key.equals(PreferencesUtils.getKey(context, R.string.recording_track_id_key))) {
          recordingTrackId = PreferencesUtils.getLong(context, R.string.recording_track_id_key);
        }
        if (key == null
            || key.equals(PreferencesUtils.getKey(context, R.string.recording_track_paused_key))) {
          recordingTrackPaused = PreferencesUtils.getBoolean(
              context, R.string.recording_track_paused_key,
              PreferencesUtils.RECORDING_TRACK_PAUSED_DEFAULT);
        }
        if (key == null
            || key.equals(PreferencesUtils.getKey(context, R.string.metric_units_key))) {
          metricUnits = PreferencesUtils.getBoolean(
              context, R.string.metric_units_key, PreferencesUtils.METRIC_UNITS_DEFAULT);
          if (key != null) {
            for (TrackDataListener trackDataListener :
                trackDataManager.getListeners(TrackDataType.PREFERENCE)) {
              if (trackDataListener.onMetricUnitsChanged(metricUnits)) {
                loadDataForListener(trackDataListener);
              }
            }
          }
        }
        if (key == null
            || key.equals(PreferencesUtils.getKey(context, R.string.report_speed_key))) {
          reportSpeed = PreferencesUtils.getBoolean(
              context, R.string.report_speed_key, PreferencesUtils.REPORT_SPEED_DEFAULT);
          if (key != null) {
            for (TrackDataListener trackDataListener :
                trackDataManager.getListeners(TrackDataType.PREFERENCE)) {
              if (trackDataListener.onReportSpeedChanged(reportSpeed)) {
                loadDataForListener(trackDataListener);
              }
            }
          }
        }
        if (key == null
            || key.equals(PreferencesUtils.getKey(context, R.string.min_required_accuracy_key))) {
          minRequiredAccuracy = PreferencesUtils.getInt(context, R.string.min_required_accuracy_key,
              PreferencesUtils.MIN_REQUIRED_ACCURACY_DEFAULT);
        }
        if (key == null
            || key.equals(PreferencesUtils.getKey(context, R.string.min_recording_distance_key))) {
          minRecordingDistance = PreferencesUtils.getInt(
              context, R.string.min_recording_distance_key,
              PreferencesUtils.MIN_RECORDING_DISTANCE_DEFAULT);
          if (key != null) {
            for (TrackDataListener trackDataListener :
                trackDataManager.getListeners(TrackDataType.PREFERENCE)) {
              if (trackDataListener.onMinRecordingDistanceChanged(minRecordingDistance)) {
                loadDataForListener(trackDataListener);
              }
            }
          }
        }
      }
    });
  }

  @Override
  public void notifyLocationProviderEnabled(final boolean enabled) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        hasProviderEnabled = enabled;
        notifyLocationStateChanged(trackDataManager.getListeners(TrackDataType.LOCATION));
      }
    });
  }

  @Override
  public void notifyLocationProviderAvailable(final boolean available) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        hasFix = available;
        notifyLocationStateChanged(trackDataManager.getListeners(TrackDataType.LOCATION));
      }
    });
  }

  @Override
  public void notifyLocationChanged(final Location location) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        notifyLocationChanged(
            location, false, trackDataManager.getListeners(TrackDataType.LOCATION));
      }
    });
  }

  @Override
  public void notifyHeadingChanged(final float heading) {
    runInHanderThread(new Runnable() {
        @Override
      public void run() {
        lastHeading = heading;

        if (lastSeenLocation != null) {
          // Update the declination at most once an hour
          long now = System.currentTimeMillis();
          if (now - lastDeclinationUpdate > ONE_HOUR) {
            lastDeclinationUpdate = now;
            long timestamp = lastSeenLocation.getTime();
            if (timestamp == 0) {
              // Hack for Samsung phones which don't populate the time field
              timestamp = now;
            }
            lastDeclination = getDeclination(lastSeenLocation, timestamp);
          }
        }
        notifyHeadingChange(trackDataManager.getListeners(TrackDataType.HEADING));
      }
    });
  }

  /**
   * Loads data for all listeners. To be run in the {@link #handler} thread.
   */
  private void loadDataForAll() {
    resetSamplingState();
    if (trackDataManager.getNumberOfListeners() == 0) {
      return;
    }

    for (TrackDataListener trackDataListener :
        trackDataManager.getListeners(TrackDataType.PREFERENCE)) {
      trackDataListener.onMetricUnitsChanged(metricUnits);
      trackDataListener.onReportSpeedChanged(reportSpeed);
      trackDataListener.onMinRecordingDistanceChanged(minRecordingDistance);
    }

    notifySelectedTrackChanged(trackDataManager.getListeners(TrackDataType.SELECTED_TRACK));
    notifyTracksTableUpdate(trackDataManager.getListeners(TrackDataType.TRACKS_TABLE));

    for (TrackDataListener listener :
        trackDataManager.getListeners(TrackDataType.SAMPLED_IN_TRACK_POINTS_TABLE)) {
      listener.clearTrackPoints();
    }
    notifyTrackPointsTableUpdate(true,
        trackDataManager.getListeners(TrackDataType.SAMPLED_IN_TRACK_POINTS_TABLE),
        trackDataManager.getListeners(TrackDataType.SAMPLED_OUT_TRACK_POINTS_TABLE));
    notifyWaypointsTableUpdate(trackDataManager.getListeners(TrackDataType.WAYPOINTS_TABLE));

    if (lastSeenLocation != null) {
      notifyLocationChanged(
          lastSeenLocation, true, trackDataManager.getListeners(TrackDataType.LOCATION));
    } else {
      notifyLocationStateChanged(trackDataManager.getListeners(TrackDataType.LOCATION));
    }
    notifyHeadingChange(trackDataManager.getListeners(TrackDataType.HEADING));
  }

  /**
   * Loads data for a listener. To be run in the {@link #handler} thread.
   * 
   * @param trackDataListener the track data listener.
   */
  private void loadDataForListener(TrackDataListener trackDataListener) {
    Set<TrackDataListener> trackDataListeners = Collections.singleton(trackDataListener);
    EnumSet<TrackDataType> trackDataTypes = trackDataManager.getTrackDataTypes(trackDataListener);

    if (trackDataTypes.contains(TrackDataType.PREFERENCE)) {
      trackDataListener.onMetricUnitsChanged(metricUnits);
      trackDataListener.onReportSpeedChanged(reportSpeed);
      trackDataListener.onMinRecordingDistanceChanged(minRecordingDistance);
    }

    if (trackDataTypes.contains(TrackDataType.SELECTED_TRACK)) {
      notifySelectedTrackChanged(trackDataListeners);
    }

    if (trackDataTypes.contains(TrackDataType.TRACKS_TABLE)) {
      notifyTracksTableUpdate(trackDataListeners);
    }

    boolean hasSampledIn = trackDataTypes.contains(TrackDataType.SAMPLED_IN_TRACK_POINTS_TABLE);
    boolean hasSampledOut = trackDataTypes.contains(TrackDataType.SAMPLED_OUT_TRACK_POINTS_TABLE);
    if (hasSampledIn || hasSampledOut) {
      trackDataListener.clearTrackPoints();
      boolean isOnlyListener = trackDataManager.getNumberOfListeners() == 1;
      if (isOnlyListener) {
        resetSamplingState();
      }
      Set<TrackDataListener> sampledInListeners = trackDataListeners;
      Set<TrackDataListener> sampledOutListeners = hasSampledOut ? trackDataListeners
          : Collections.<TrackDataListener> emptySet();
      notifyTrackPointsTableUpdate(isOnlyListener, sampledInListeners, sampledOutListeners);
    }

    if (trackDataTypes.contains(TrackDataType.WAYPOINTS_TABLE)) {
      notifyWaypointsTableUpdate(trackDataListeners);
    }

    if (trackDataTypes.contains(TrackDataType.LOCATION)) {
      if (lastSeenLocation != null) {
        notifyLocationChanged(lastSeenLocation, true, trackDataListeners);
      } else {
        notifyLocationStateChanged(trackDataListeners);
      }
    }

    if (trackDataTypes.contains(TrackDataType.HEADING)) {
      notifyHeadingChange(trackDataListeners);
    }
  }

  /**
   * Notifies selected track changed. To be run in the {@link #handler} thread.
   * 
   * @param trackDataListeners the track data listeners to notify
   */
  private void notifySelectedTrackChanged(Set<TrackDataListener> trackDataListeners) {
    if (trackDataListeners.isEmpty()) {
      return;
    }
    Track track = myTracksProviderUtils.getTrack(selectedTrackId);
    for (TrackDataListener trackDataListener : trackDataListeners) {
      trackDataListener.onSelectedTrackChanged(track);
    }
  }

  /**
   * Notifies track table update. To be run in the {@link #handler} thread.
   * 
   * @param trackDataListeners the track data listeners to notify
   */
  private void notifyTracksTableUpdate(Set<TrackDataListener> trackDataListeners) {
    if (trackDataListeners.isEmpty()) {
      return;
    }
    Track track = myTracksProviderUtils.getTrack(selectedTrackId);
    for (TrackDataListener trackDataListener : trackDataListeners) {
      trackDataListener.onTrackUpdated(track);
    }
  }

  /**
   * Notifies waypoint table update. Currently, reloads all the waypoints up to
   * {@link Constants#MAX_DISPLAYED_WAYPOINTS_POINTS}. To be run in the
   * {@link #handler} thread.
   * 
   * @param trackDataListeners the track data listeners to notify
   */
  private void notifyWaypointsTableUpdate(Set<TrackDataListener> trackDataListeners) {
    if (trackDataListeners.isEmpty()) {
      return;
    }

    for (TrackDataListener trackDataListener : trackDataListeners) {
      trackDataListener.clearWaypoints();
    }

    Cursor cursor = null;
    try {
      cursor = myTracksProviderUtils.getWaypointCursor(
          selectedTrackId, 0L, MAX_DISPLAYED_WAYPOINTS_POINTS);
      if (cursor != null && cursor.moveToFirst()) {
        do {
          Waypoint waypoint = myTracksProviderUtils.createWaypoint(cursor);
          if (!LocationUtils.isValidLocation(waypoint.getLocation())) {
            continue;
          }
          for (TrackDataListener trackDataListener : trackDataListeners) {
            trackDataListener.onNewWaypoint(waypoint);
          }
        } while (cursor.moveToNext());
      }
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }

    for (TrackDataListener trackDataListener : trackDataListeners) {
      trackDataListener.onNewWaypointsDone();
    }
  }

  /**
   * Notifies track points table update. To be run in the {@link #handler}
   * thread.
   * 
   * @param updateSamplingState true to update the sampling state
   * @param sampledInListeners the sampled-in listeners
   * @param sampledOutListeners the sampled-out listeners
   */
  private void notifyTrackPointsTableUpdate(boolean updateSamplingState,
      Set<TrackDataListener> sampledInListeners, Set<TrackDataListener> sampledOutListeners) {
    if (sampledInListeners.isEmpty() && sampledOutListeners.isEmpty()) {
      return;
    }
    if (updateSamplingState && numLoadedPoints >= targetNumPoints) {
      // Reload and resample the track at a lower frequency.
      Log.i(TAG, "Resampling track after " + numLoadedPoints + " points.");
      resetSamplingState();
      for (TrackDataListener listener : sampledInListeners) {
        listener.clearTrackPoints();
      }
    }

    int localNumLoadedPoints = updateSamplingState ? numLoadedPoints : 0;
    long localFirstSeenLocationId = updateSamplingState ? firstSeenLocationId : -1L;
    long localLastSeenLocationId = updateSamplingState ? lastSeenLocationId : -1L;
    long maxPointId = updateSamplingState ? -1L : lastSeenLocationId;

    long lastTrackPointId = myTracksProviderUtils.getLastTrackPointId(selectedTrackId);
    int samplingFrequency = -1;
    LocationIterator iterator = myTracksProviderUtils.getTrackPointLocationIterator(selectedTrackId,
        localLastSeenLocationId + 1, false, MyTracksProviderUtils.DEFAULT_LOCATION_FACTORY);
    boolean includeNextPoint = false;
    while (iterator.hasNext()) {
      Location location = iterator.next();
      long locationId = iterator.getLocationId();

      // Stop if past the last wanted point
      if (maxPointId != -1L && locationId > maxPointId) {
        break;
      }

      if (localFirstSeenLocationId == -1) {
        localFirstSeenLocationId = locationId;
      }

      if (samplingFrequency == -1) {
        long numTotalPoints = Math.max(0L, lastTrackPointId - localFirstSeenLocationId);
        samplingFrequency = 1 + (int) (numTotalPoints / targetNumPoints);
      }

      if (!LocationUtils.isValidLocation(location)) {
        // TODO: also include the last valid point before a split
        for (TrackDataListener trackDataListener : sampledInListeners) {
          trackDataListener.onSegmentSplit(location);
          includeNextPoint = true;
        }
      } else {
        // Also include the last point if the selected track is not recording.
        if (includeNextPoint || (localNumLoadedPoints % samplingFrequency == 0)
            || (locationId == lastTrackPointId && !isSelectedTrackRecording())) {
          includeNextPoint = false;
          for (TrackDataListener trackDataListener : sampledInListeners) {
            trackDataListener.onSampledInTrackPoint(location);
          }
        } else {
          for (TrackDataListener trackDataListener : sampledOutListeners) {
            trackDataListener.onSampledOutTrackPoint(location);
          }
        }
      }

      localNumLoadedPoints++;
      localLastSeenLocationId = locationId;
    }
    iterator.close();

    if (updateSamplingState) {
      numLoadedPoints = localNumLoadedPoints;
      firstSeenLocationId = localFirstSeenLocationId;
      lastSeenLocationId = localLastSeenLocationId;
    }

    for (TrackDataListener listener : sampledInListeners) {
      listener.onNewTrackPointsDone();
    }
  }

  /**
   * Notifies location state changed. To be run in the {@link #handler} thread.
   * 
   * @param trackDataListeners the track data listeners to notify
   */
  private void notifyLocationStateChanged(Set<TrackDataListener> trackDataListeners) {
    if (trackDataListeners.isEmpty()) {
      return;
    }
    TrackDataListener.LocationState locationState;
    if (!hasProviderEnabled) {
      locationState = LocationState.DISABLED;
      lastSeenLocation = null;
    } else if (!hasFix) {
      locationState = LocationState.NO_FIX;
    } else if (!hasGoodFix) {
      locationState = LocationState.BAD_FIX;
    } else {
      locationState = LocationState.GOOD_FIX;
    }
    for (TrackDataListener trackDataListener : trackDataListeners) {
      trackDataListener.onLocationStateChanged(locationState);
    }
  }

  /**
   * Notifies location changed. To be run in the {@link #handler} thread.
   * 
   * @param location the location
   * @param notifyLocationStateChange true to always notify location state
   *          change
   * @param trackDataListeners the track data listeners to notify
   */
  private void notifyLocationChanged(Location location, boolean notifyLocationStateChange,
      Set<TrackDataListener> trackDataListeners) {
    if (location == null) {
      return;
    }
    boolean oldHasFix = hasFix;
    boolean oldHasGoodFix = hasGoodFix;
    long now = System.currentTimeMillis();
    if (location.getProvider().equals(LocationManager.GPS_PROVIDER)) {
      hasFix = !isLocationOld(location, now, MAX_LOCATION_AGE_MS);
      hasGoodFix = location.getAccuracy() <= minRequiredAccuracy;
      lastSeenLocation = location;
    } else {
      // A network location.

      // If has a recent GPS location, ignore the network location.
      if (!isLocationOld(lastSeenLocation, now, MAX_LOCATION_AGE_MS)) {
        return;
      }

      hasFix = false;
      hasGoodFix = false;
      lastSeenLocation = isLocationOld(location, now, MAX_NETWORK_AGE_MS) ? null : location;
    }

    if (trackDataListeners.isEmpty()) {
      return;
    }

    if (notifyLocationStateChange || hasFix != oldHasFix || hasGoodFix != oldHasGoodFix) {
      notifyLocationStateChanged(trackDataListeners);
    }

    for (TrackDataListener trackDataListener : trackDataListeners) {
      trackDataListener.onLocationChanged(lastSeenLocation);
    }
  }

  /**
   * Notifies heading change. To be run in the {@link #handler} thread.
   * 
   * @param trackDataListeners the track data listeners to notify
   */
  private void notifyHeadingChange(Set<TrackDataListener> trackDataListeners) {
    if (trackDataListeners.isEmpty()) {
      return;
    }
    float value = lastHeading + lastDeclination;
    for (TrackDataListener trackDataListener : trackDataListeners) {
      trackDataListener.onHeadingChanged(value);
    }
  }

  /**
   * Resets the track points sampling states.
   */
  private void resetSamplingState() {
    numLoadedPoints = 0;
    firstSeenLocationId = -1L;
    lastSeenLocationId = -1L;
  }

  /**
   * Returns true if a location is invalid or too old.
   * 
   * @param location the location
   * @param now the current time
   * @param maxAge the maximum age
   */
  private boolean isLocationOld(Location location, long now, long maxAge) {
    return !LocationUtils.isValidLocation(location) || (now - location.getTime() > maxAge);
  }

  /**
   * Creates a {@link DataSource}.
   */
  @VisibleForTesting
  protected DataSource newDataSource() {
    return new DataSource(context);
  }

  /**
   * Gets a declination.
   * 
   * @param location the location
   * @param time the time
   */
  @VisibleForTesting
  protected float getDeclination(Location location, long time) {
    GeomagneticField field = new GeomagneticField((float) location.getLatitude(), (float) location
        .getLongitude(), (float) location.getAltitude(), time);
    return field.getDeclination();
  }

  /**
   * Run in the handler thread.
   * 
   * @param runnable the runnable
   */
  @VisibleForTesting
  protected void runInHanderThread(Runnable runnable) {
    if (handler == null) {
      // Use a Throwable to ensure the stack trace is logged.
      Log.d(TAG, "handler is null.", new Throwable());
      return;
    }
    handler.post(runnable);
  }
  
  /**
   * Gets the value selectedTrackId.
   * 
   * @return the selectedTrackId
   */
  @VisibleForTesting
  long getSelectedTrackId() {
    return selectedTrackId;
  }
  
  /**
   * Gets the minRequiredAccuracy.
   * 
   * @return the minRequiredAccuracy
   */
  @VisibleForTesting
  int getMinRequiredAccuracy() {
    return minRequiredAccuracy;
  }
  
  /**
   * Gets the metricUnits.
   * 
   * @return the metricUnits
   */
  @VisibleForTesting
  boolean isMetricUnits() {
    return metricUnits;
  }
  
  /**
   * Gets the reportSpeed.
   * 
   * @return the reportSpeed
   */
  @VisibleForTesting
  boolean isReportSpeed() {
    return reportSpeed;
  }
  
  /**
   * Sets the value of lastSeenLocation.
   * 
   * @param lastSeenLocation value of lastSeenLocation
   */
  @VisibleForTesting
  void setLastSeenLocation(Location lastSeenLocation) {
    this.lastSeenLocation = lastSeenLocation;
  }
}