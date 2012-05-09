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

package com.google.android.apps.mytracks.widgets;

import static com.google.android.apps.mytracks.Constants.SETTINGS_NAME;
import static com.google.android.apps.mytracks.Constants.TAG;

import com.google.android.apps.mytracks.TrackDetailActivity;
import com.google.android.apps.mytracks.TrackListActivity;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.TracksColumns;
import com.google.android.apps.mytracks.services.ControlRecordingService;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.apps.mytracks.util.StringUtils;
import com.google.android.maps.mytracks.R;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.database.ContentObserver;
import android.os.Handler;
import android.util.Log;
import android.widget.RemoteViews;

/**
 * An AppWidgetProvider for displaying key track statistics (distance, time,
 * speed) from the current or most recent track.
 *
 * @author Sandor Dornbush
 * @author Paul R. Saxman
 */
public class TrackWidgetProvider
    extends AppWidgetProvider
    implements OnSharedPreferenceChangeListener {

  class TrackObserver extends ContentObserver {

    public TrackObserver() {
      super(contentHandler);
    }

    public void onChange(boolean selfChange) {
      updateTrack(null);
    }
  }

  private final Handler contentHandler;
  private MyTracksProviderUtils providerUtils;
  private Context context;
  private String unknown;
  private TrackObserver trackObserver;
  private boolean metricUnits;
  private boolean reportSpeed;
  private long selectedTrackId;
  private SharedPreferences sharedPreferences;
  private String TRACK_STARTED_ACTION;
  private String TRACK_STOPPED_ACTION;

  public TrackWidgetProvider() {
    super();
    contentHandler = new Handler();
    selectedTrackId = PreferencesUtils.SELECTED_TRACK_ID_DEFAULT;
  }

  private void initialize(Context aContext) {
    if (this.context != null) {
      return;
    }
    this.context = aContext;
    trackObserver = new TrackObserver();
    providerUtils = MyTracksProviderUtils.Factory.get(context);
    unknown = context.getString(R.string.value_unknown);

    sharedPreferences = context.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE);
    sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    onSharedPreferenceChanged(sharedPreferences, null);

    context.getContentResolver().registerContentObserver(
        TracksColumns.CONTENT_URI, true, trackObserver);
    TRACK_STARTED_ACTION = context.getString(R.string.track_started_broadcast_action);
    TRACK_STOPPED_ACTION = context.getString(R.string.track_stopped_broadcast_action);
  }

  @Override
  public void onReceive(Context aContext, Intent intent) {
    super.onReceive(aContext, intent);
    initialize(aContext);

    selectedTrackId = intent.getLongExtra(
        context.getString(R.string.track_id_broadcast_extra), selectedTrackId);
    String action = intent.getAction();
    Log.d(TAG,
        "TrackWidgetProvider.onReceive: trackId=" + selectedTrackId + ", action=" + action);

    if (AppWidgetManager.ACTION_APPWIDGET_ENABLED.equals(action)
        || AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)
        || TRACK_STARTED_ACTION.equals(action)
        || TRACK_STOPPED_ACTION.equals(action)) {
      updateTrack(action);
    }
  }

  @Override
  public void onDisabled(Context aContext) {
    if (trackObserver != null) {
      aContext.getContentResolver().unregisterContentObserver(trackObserver);
    }
    if (sharedPreferences != null) {
      sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }
  }

  private void updateTrack(String action) {
    Track track = null;
    if (selectedTrackId != PreferencesUtils.SELECTED_TRACK_ID_DEFAULT) {
      Log.d(TAG, "TrackWidgetProvider.updateTrack: Retrieving specified track.");
      track = providerUtils.getTrack(selectedTrackId);
    } else {
      Log.d(TAG, "TrackWidgetProvider.updateTrack: Attempting to retrieve previous track.");
      // TODO we should really read the pref.
      track = providerUtils.getLastTrack();
    }

    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
    ComponentName widget = new ComponentName(context, TrackWidgetProvider.class);
    RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.track_widget);

    // Make all of the stats open the mytracks activity.
    Intent intent;
    if (track != null) {
      intent = IntentUtils.newIntent(context, TrackDetailActivity.class)
          .putExtra(TrackDetailActivity.EXTRA_TRACK_ID, track.getId());
    } else {
      intent = IntentUtils.newIntent(context, TrackListActivity.class);
    }
    PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
    views.setOnClickPendingIntent(R.id.appwidget_track_statistics, pendingIntent);

    if (action != null) {
      updateViewButton(views, action);
    }
    updateViewTrackStatistics(views, track);
    int[] appWidgetIds = appWidgetManager.getAppWidgetIds(widget);
    for (int appWidgetId : appWidgetIds) {
      appWidgetManager.updateAppWidget(appWidgetId, views);
    }
  }

  /**
   * Update the widget's button with the appropriate intent and icon.
   *
   * @param views The RemoteViews containing the button
   * @param action The action broadcast from the track service
   */
  private void updateViewButton(RemoteViews views, String action) {
    if (TRACK_STARTED_ACTION.equals(action)) {
      // If a new track is started by this appwidget or elsewhere,
      // toggle the button to active and have it disable the track if pressed.
      setButtonIntent(views, R.string.track_action_end, R.drawable.app_widget_button_enabled);
    } else {
      // If a track is stopped by this appwidget or elsewhere,
      // toggle the button to inactive and have it start a new track if pressed.
      setButtonIntent(views, R.string.track_action_start, R.drawable.app_widget_button_disabled);
    }
  }

  /**
   * Set up the main widget button.
   *
   * @param views The widget views
   * @param action The resource id of the action to fire when the button is pressed
   * @param icon The resource id of the icon to show for the button
   */
  private void setButtonIntent(RemoteViews views, int action, int icon) {
    Intent intent = new Intent(context, ControlRecordingService.class)
        .setAction(context.getString(action));
    PendingIntent pendingIntent = PendingIntent.getService(context, 0,
        intent, PendingIntent.FLAG_UPDATE_CURRENT);
    views.setOnClickPendingIntent(R.id.appwidget_button, pendingIntent);
    views.setImageViewResource(R.id.appwidget_button, icon);
  }

  /**
   * Update the specified widget's view with the distance, time, and speed of
   * the specified track.
   *
   * @param views The RemoteViews to update with statistics
   * @param track The track to extract statistics from.
   */
  protected void updateViewTrackStatistics(RemoteViews views, Track track) {
    if (track == null) {
      views.setTextViewText(R.id.appwidget_distance_text, unknown);
      views.setTextViewText(R.id.appwidget_time_text, unknown);
      views.setTextViewText(R.id.appwidget_speed_text, unknown);
      return;
    }

    TripStatistics stats = track.getStatistics();
    String distance = StringUtils.formatDistance(context, stats.getTotalDistance(), metricUnits);
    String time = StringUtils.formatElapsedTime(stats.getMovingTime());
    String speed = StringUtils.formatSpeed(
        context, stats.getAverageMovingSpeed(), metricUnits, reportSpeed);

    views.setTextViewText(R.id.appwidget_distance_text, distance);
    views.setTextViewText(R.id.appwidget_time_text, time);
    views.setTextViewText(R.id.appwidget_speed_text, speed);
  }

  @Override
  public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
    if (key == null || PreferencesUtils.getKey(context, R.string.metric_units_key).equals(key)) {
      metricUnits = PreferencesUtils.getBoolean(
          context, R.string.metric_units_key, PreferencesUtils.METRIC_UNITS_DEFAULT);
    }
    if (key == null || PreferencesUtils.getKey(context, R.string.report_speed_key).equals(key)) {
      reportSpeed = PreferencesUtils.getBoolean(
          context, R.string.report_speed_key, PreferencesUtils.REPORT_SPEED_DEFAULT);
    }
    if (key == null
        || PreferencesUtils.getKey(context, R.string.selected_track_id_key).equals(key)) {
      selectedTrackId = PreferencesUtils.getLong(context, R.string.selected_track_id_key);
      Log.d(TAG, "TrackWidgetProvider setting selecting track from preference: " + selectedTrackId);
    }
  }
}
