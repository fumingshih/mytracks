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

import android.location.Location;

/**
 * Listener for track data changes.
 * 
 * @author Rodrigo Damazio
 */
public interface TrackDataListener {

  /**
   * Location state.
   * 
   * @author Jimmy Shih
   */
  public enum LocationState {
    DISABLED, NO_FIX, BAD_FIX, GOOD_FIX;
  }

  /**
   * Called when the location state changes.
   */
  public void onLocationStateChanged(LocationState locationState);

  /**
   * Called when the location changes. This is meant for location display only,
   * track point data is reported with other methods like
   * {@link #onSampledInTrackPoint(Location)} and
   * {@link #onSampledOutTrackPoint(Location)}.
   * 
   * @param location the location
   */
  public void onLocationChanged(Location location);

  /**
   * Called when the heading changes.
   * 
   * @param heading the heading
   */
  public void onHeadingChanged(double heading);

  /**
   * Called when the selected track changes. This will be followed by calls to
   * data methods such as {@link #onTrackUpdated(Track)},
   * {@link #clearTrackPoints()}, {@link #onSampledInTrackPoint(Location)},
   * etc., even if no track is currently selected (in which case you'll only get
   * calls to clear the current data).
   * 
   * @param track the selected track or null if no track is selected
   */
  public void onSelectedTrackChanged(Track track);

  /**
   * Called when the track or its statistics has been updated.
   * 
   * @param track the track
   */
  public void onTrackUpdated(Track track);

  /**
   * Called to clear previously-sent track points.
   */
  public void clearTrackPoints();

  /**
   * Called when a sampled in track point is read.
   * 
   * @param location the location
   */
  public void onSampledInTrackPoint(Location location);

  /**
   * Called when a sampled out track point is read.
   * 
   * @param location the location
   */
  public void onSampledOutTrackPoint(Location location);

  /**
   * Called when an invalid track point representing a segment split is read.
   */
  public void onSegmentSplit(Location location);

  /**
   * Called when finish sending new track points. This gets called after every
   * batch of calls to {@link #onSampledInTrackPoint(Location)},
   * {@link #onSampledOutTrackPoint(Location)} and
   * {@link #onSegmentSplit(Location)}.
   */
  public void onNewTrackPointsDone();

  /**
   * Called to clear previously sent waypoints.
   */
  public void clearWaypoints();

  /**
   * Called when a new waypoint is read.
   * 
   * @param waypoint the waypoint
   */
  public void onNewWaypoint(Waypoint waypoint);

  /**
   * Called when finish sending new waypoints. This gets called after every
   * batch of calls to {@link #clearWaypoints()} and
   * {@link #onNewWaypoint(Waypoint)}.
   */
  public void onNewWaypointsDone();

  /**
   * Called when the metric units preference value is change.
   * 
   * @param metricUnits true to use metric units, false to use imperial units
   * @return true to reload all the data, false otherwise.
   */
  public boolean onMetricUnitsChanged(boolean metricUnits);

  /**
   * Called when the report speed preference value is changed.
   * 
   * @param reportSpeed true to report speed, false to report pace
   * @return true to reload all the data, false otherwise.
   */
  public boolean onReportSpeedChanged(boolean reportSpeed);

  /**
   * Called when the min recording distance preference value is changed.
   * 
   * @param minRecordingDistance the new value
   * @return true to reload all the data, false otherwise.
   */
  public boolean onMinRecordingDistanceChanged(int minRecordingDistance);
}
