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

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.services.StatusAnnouncerFactory;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.apps.mytracks.util.ApiFeatures;
import com.google.android.apps.mytracks.util.GeoRect;
import com.google.android.apps.mytracks.util.MyTracksUtils;
import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapActivity;
import com.google.android.maps.MapController;
import com.google.android.maps.MapView;
import com.google.android.maps.mytracks.R;

import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.View.OnCreateContextMenuListener;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * The map view activity of the MyTracks application.
 *
 * @author Leif Hendrik Wilden
 * @author Rodrigo Damazio
 */
public class MyTracksMap extends MapActivity
    implements View.OnTouchListener, View.OnClickListener,
        TrackDataListener {

  // Saved instance state keys:
  // ---------------------------

  private static final String KEY_CURRENT_LOCATION = "currentLocation";
  private static final String KEY_KEEP_MY_LOCATION_VISIBLE = "keepMyLocationVisible";

  private TrackDataHub dataHub;

  /**
   * True if the map should be scrolled so that the pointer is always in the
   * visible area.
   */
  private boolean keepMyLocationVisible;

  /**
   * The current pointer location.
   * This is kept to quickly center on it when the user requests.
   */
  private Location currentLocation;

  // UI elements:
  // -------------

  private RelativeLayout screen;
  private MapView mapView;
  private MyTracksOverlay mapOverlay;
  private LinearLayout messagePane;
  private TextView messageText;
  private LinearLayout busyPane;
  private ImageButton optionsBtn;

  private MenuItem myLocation;
  private MenuItem toggleLayers;

  /**
   * We are not displaying driving directions. Just an arbitrary track that is
   * not associated to any licensed mapping data. Therefore it should be okay to
   * return false here and still comply with the terms of service.
   */
  @Override
  protected boolean isRouteDisplayed() {
    return false;
  }

  /**
   * We are displaying a location. This needs to return true in order to comply
   * with the terms of service.
   */
  @Override
  protected boolean isLocationDisplayed() {
    return true;
  }

  // Application life cycle:
  // ------------------------

  @Override
  public void onCreate(Bundle bundle) {
    Log.d(TAG, "MyTracksMap.onCreate");
    super.onCreate(bundle);

    // The volume we want to control is the Text-To-Speech volume
    int volumeStream =
        new StatusAnnouncerFactory(ApiFeatures.getInstance()).getVolumeStream();
    setVolumeControlStream(volumeStream);

    dataHub = MyTracks.getInstance().getDataHub();

    // We don't need a window title bar:
    requestWindowFeature(Window.FEATURE_NO_TITLE);

    // Inflate the layout:
    setContentView(R.layout.mytracks_layout);

    // Remove the window's background because the MapView will obscure it
    getWindow().setBackgroundDrawable(null);

    // Set up a map overlay:
    screen = (RelativeLayout) findViewById(R.id.screen);
    mapView = (MapView) findViewById(R.id.map);
    mapView.requestFocus();
    mapOverlay = new MyTracksOverlay(this);
    mapView.getOverlays().add(mapOverlay);
    mapView.setOnTouchListener(this);
    mapView.setBuiltInZoomControls(true);
    messagePane = (LinearLayout) findViewById(R.id.messagepane);
    messageText = (TextView) findViewById(R.id.messagetext);
    busyPane = (LinearLayout) findViewById(R.id.busypane);
    optionsBtn = (ImageButton) findViewById(R.id.showOptions);

    optionsBtn.setOnCreateContextMenuListener(contextMenuListener);
    optionsBtn.setOnClickListener(this);
  }

  @Override
  protected void onRestoreInstanceState(Bundle bundle) {
    Log.d(TAG, "MyTracksMap.onRestoreInstanceState");
    if (bundle != null) {
      super.onRestoreInstanceState(bundle);
      keepMyLocationVisible =
          bundle.getBoolean(KEY_KEEP_MY_LOCATION_VISIBLE, false);
      if (bundle.containsKey(KEY_CURRENT_LOCATION)) {
        currentLocation = (Location) bundle.getParcelable(KEY_CURRENT_LOCATION);
        if (currentLocation != null) {
          showCurrentLocation();
        }
      } else {
        currentLocation = null;
      }
    }
  }

  @Override
  protected void onStart() {
    Log.d(TAG, "MyTracksMap.onStart");
    super.onStart();

    dataHub.registerTrackDataListener(this);
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    Log.d(TAG, "MyTracksMap.onSaveInstanceState");
    outState.putBoolean(KEY_KEEP_MY_LOCATION_VISIBLE, keepMyLocationVisible);
    if (currentLocation != null) {
      outState.putParcelable(KEY_CURRENT_LOCATION, currentLocation);
    }
    super.onSaveInstanceState(outState);
  }

  @Override
  protected void onStop() {
    Log.d(TAG, "MyTracksMap.onStop");

    dataHub.unregisterTrackDataListener(this);

    super.onStop();
  }

  // Utility functions:
  // -------------------

  /**
   * Shows the options button if a track is selected, or hide it if not.
   */
  private void updateOptionsButton(boolean trackSelected) {
    optionsBtn.setVisibility(
        trackSelected ? View.VISIBLE : View.INVISIBLE);
  }

  /**
   * Tests if a location is visible.
   *
   * @param location a given location
   * @return true if the given location is within the visible map area
   */
  private boolean locationIsVisible(Location location) {
    if (location == null || mapView == null) {
      return false;
    }
    GeoPoint center = mapView.getMapCenter();
    int latSpan = mapView.getLatitudeSpan();
    int lonSpan = mapView.getLongitudeSpan();

    // Bottom of map view is obscured by zoom controls/buttons.
    // Subtract a margin from the visible area:
    GeoPoint marginBottom = mapView.getProjection().fromPixels(
        0, mapView.getHeight());
    GeoPoint marginTop = mapView.getProjection().fromPixels(0,
        mapView.getHeight()
            - mapView.getZoomButtonsController().getZoomControls().getHeight());
    int margin =
        Math.abs(marginTop.getLatitudeE6() - marginBottom.getLatitudeE6());
    GeoRect r = new GeoRect(center, latSpan, lonSpan);
    r.top += margin;

    GeoPoint geoPoint = MyTracksUtils.getGeoPoint(location);
    return r.contains(geoPoint);
  }

  /**
   * Moves the location pointer to the current location and center the map if
   * the current location is outside the visible area.
   */
  private void showCurrentLocation() {
    if (currentLocation == null || mapOverlay == null || mapView == null) {
      return;
    }
    mapOverlay.setMyLocation(currentLocation);
    mapView.postInvalidate();
    if (keepMyLocationVisible && !locationIsVisible(currentLocation)) {
      GeoPoint geoPoint = MyTracksUtils.getGeoPoint(currentLocation);
      MapController controller = mapView.getController();
      controller.animateTo(geoPoint);
    }

  }

  @Override
  public void onTrackUpdated(Track track) {
    // We don't care.
  }

  /**
   * Zooms and pans the map so that the given track is visible.
   *
   * @param track the track
   */
  private void zoomMapToBoundaries(Track track) {
    if (mapView == null) {
      return;
    }

    if (track == null || track.getNumberOfPoints() < 2) {
      return;
    }

    TripStatistics stats = track.getStatistics();
    int bottom = stats.getBottom();
    int left = stats.getLeft();
    int latSpanE6 = stats.getTop() - bottom;
    int lonSpanE6 = stats.getRight() - left;
    if (latSpanE6 > 0
        && latSpanE6 < 180E6
        && lonSpanE6 > 0
        && lonSpanE6 < 360E6) {
      keepMyLocationVisible = false;
      GeoPoint center = new GeoPoint(
          bottom + latSpanE6 / 2,
          left + lonSpanE6 / 2);
      if (MyTracksUtils.isValidGeoPoint(center)) {
        mapView.getController().setCenter(center);
        mapView.getController().zoomToSpan(latSpanE6, lonSpanE6);
      }
    }
  }

  /**
   * Zooms and pans the map so that the given waypoint is visible.
   */
  public void showWaypoint(long waypointId) {
    MyTracksProviderUtils providerUtils = MyTracksProviderUtils.Factory.get(this);
    Waypoint wpt = providerUtils.getWaypoint(waypointId);
    if (wpt != null && wpt.getLocation() != null) {
      keepMyLocationVisible = false;
      GeoPoint center = new GeoPoint(
          (int) (wpt.getLocation().getLatitude() * 1E6),
          (int) (wpt.getLocation().getLongitude() * 1E6));
      mapView.getController().setCenter(center);
      mapView.getController().setZoom(20);
      mapView.invalidate();
    }
  }

  @Override
  public void onSelectedTrackChanged(final Track track, final boolean isRecording) {
    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        boolean trackSelected = track != null;
        updateOptionsButton(trackSelected);

        mapOverlay.setTrackDrawingEnabled(trackSelected);

        if (trackSelected) {
          busyPane.setVisibility(View.VISIBLE);
  
          zoomMapToBoundaries(track);
  
          mapOverlay.setShowEndMarker(!isRecording);
          busyPane.setVisibility(View.GONE);
        }
        mapView.invalidate();
      }
    });
  }

  private final OnCreateContextMenuListener contextMenuListener =
      new OnCreateContextMenuListener() {
        @Override
        public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
          menu.setHeaderTitle(R.string.tracklist_this_track);
          menu.add(0, Constants.MENU_EDIT, 0,
              R.string.tracklist_edit_track);
          if (!dataHub.isRecordingSelected()) {
            menu.add(0, Constants.MENU_SEND_TO_GOOGLE, 0,
                R.string.tracklist_send_to_google);
            SubMenu share = menu.addSubMenu(0, Constants.MENU_SHARE, 0,
                R.string.tracklist_share_track);
            share.add(0, Constants.MENU_SHARE_LINK, 0,
                R.string.tracklist_share_link);
            share.add(0, Constants.MENU_SHARE_GPX_FILE, 0,
                R.string.tracklist_share_gpx_file);
            share.add(0, Constants.MENU_SHARE_KML_FILE, 0,
                R.string.tracklist_share_kml_file);
            share.add(0, Constants.MENU_SHARE_CSV_FILE, 0,
                R.string.tracklist_share_csv_file);
            share.add(0, Constants.MENU_SHARE_TCX_FILE, 0,
                R.string.tracklist_share_tcx_file);
            SubMenu save = menu.addSubMenu(0,
                Constants.MENU_WRITE_TO_SD_CARD, 0,
                R.string.tracklist_write_to_sd);
            save.add(0, Constants.MENU_SAVE_GPX_FILE, 0,
                R.string.tracklist_save_as_gpx);
            save.add(0, Constants.MENU_SAVE_KML_FILE, 0,
                R.string.tracklist_save_as_kml);
            save.add(0, Constants.MENU_SAVE_CSV_FILE, 0,
                R.string.tracklist_save_as_csv);
            save.add(0, Constants.MENU_SAVE_TCX_FILE, 0,
                R.string.tracklist_save_as_tcx);
            menu.add(0, Constants.MENU_CLEAR_MAP, 0,
                R.string.tracklist_clear_map);
            menu.add(0, Constants.MENU_DELETE, 0,
                R.string.tracklist_delete_track);
          }
        }
      };

  @Override
  public boolean onMenuItemSelected(int featureId, MenuItem item) {
    if (!super.onMenuItemSelected(featureId, item)) {
      MyTracks.getInstance().onActivityResult(
          Constants.getActionFromMenuId(item.getItemId()), RESULT_OK,
          new Intent());
      return true;
    }
    return false;
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);
    myLocation = menu.add(0, Constants.MENU_MY_LOCATION, 0,
        R.string.mylocation);
    myLocation.setIcon(android.R.drawable.ic_menu_mylocation);
    toggleLayers = menu.add(0, Constants.MENU_TOGGLE_LAYERS, 0,
        R.string.switch_to_sat);
    toggleLayers.setIcon(android.R.drawable.ic_menu_mapmode);
    return true;
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    toggleLayers.setTitle(mapView.isSatellite() ?
        R.string.switch_to_map : R.string.switch_to_sat);
    return super.onPrepareOptionsMenu(menu);
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case Constants.MENU_MY_LOCATION: {
        dataHub.forceUpdateLocation();
        keepMyLocationVisible = true;
        if (mapView.getZoomLevel() < 18) {
          mapView.getController().setZoom(18);
        }
        return true;
      }
      case Constants.MENU_TOGGLE_LAYERS: {
        mapView.setSatellite(!mapView.isSatellite());
        return true;
      }
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onClick(View v) {
    if (v == messagePane) {
      startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    } else if (v == optionsBtn) {
      optionsBtn.performLongClick();
    }
  }

  /**
   * We want the pointer to become visible again in case of the next location
   * update:
   */
  @Override
  public boolean onTouch(View view, MotionEvent event) {
    if (keepMyLocationVisible && event.getAction() == MotionEvent.ACTION_MOVE) {
      if (!locationIsVisible(currentLocation)) {
        keepMyLocationVisible = false;
      }
    }
    return false;
  }

  @Override
  public void onProviderStateChange(ProviderState state) {
    final int messageId;
    final boolean bindClick;
    switch (state) {
      case DISABLED:
        messageId = R.string.status_enable_gps;
        bindClick = true;
        break;
      case NO_FIX:
      case BAD_FIX:
        messageId = R.string.wait_for_fix;
        bindClick = false;
        break;
      case GOOD_FIX:
        // Nothing to show.
        messageId = -1;
        bindClick = false;
        break;
      default:
        throw new IllegalArgumentException("Unexpected state: " + state);
    }

    runOnUiThread(new Runnable() {
      @Override
      public void run() {
        if (messageId != -1) {
          messageText.setText(messageId);
          messagePane.setVisibility(View.VISIBLE);

          if (bindClick) {
            messagePane.setOnClickListener(MyTracksMap.this);
          } else {
            messagePane.setOnClickListener(null);
          }
        } else {
          messagePane.setVisibility(View.GONE);
        }

        screen.requestLayout();
      }
    });
  }

  @Override
  public void onCurrentLocationChanged(Location location) {
    if (!location.getProvider().equals(Constants.GPS_PROVIDER)) {
      Log.d(TAG,
          "MyTracksMap: Network location update received (provider '" + location.getProvider() + "'.");
    }

    currentLocation = location;
    showCurrentLocation();
  }

  @Override
  public void onCurrentHeadingChanged(double heading) {
    synchronized (this) {
      if (mapOverlay.setHeading((float) heading)) {
        mapView.postInvalidate();
      }
    }
  }

  @Override
  public void clearWaypoints() {
    mapOverlay.clearWaypoints();
  }

  @Override
  public void onNewWaypoint(Waypoint waypoint) {
    if (MyTracksUtils.isValidLocation(waypoint.getLocation())) {
      // TODO: Optimize locking inside addWaypoint
      mapOverlay.addWaypoint(waypoint);
    }
  }

  @Override
  public void onNewWaypointsDone() {
    mapView.postInvalidate();
  }

  @Override
  public void clearTrackPoints() {
    mapOverlay.clearPoints();
  }

  @Override
  public void onNewTrackPoint(Location loc) {
    mapOverlay.addLocation(loc);
  }

  @Override
  public void onSegmentSplit() {
    mapOverlay.addSegmentSplit();
  }

  @Override
  public void onSampledOutTrackPoint(Location loc) {
    // We don't care.
  }

  @Override
  public void onNewTrackPointsDone() {
    mapView.postInvalidate();
  }

  @Override
  public boolean onUnitsChanged(boolean metric) {
    // We don't care.
    return false;
  }

  @Override
  public boolean onReportSpeedChanged(boolean reportSpeed) {
    // We don't care.
    return false;
  }
}
