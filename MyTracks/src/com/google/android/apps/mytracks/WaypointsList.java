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
package com.google.android.apps.mytracks;

import static com.google.android.apps.mytracks.Constants.TAG;

import com.google.android.apps.mytracks.content.DescriptionGeneratorImpl;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.content.WaypointCreationRequest;
import com.google.android.apps.mytracks.content.WaypointsColumns;
import com.google.android.apps.mytracks.services.ITrackRecordingService;
import com.google.android.apps.mytracks.services.TrackRecordingServiceConnection;
import com.google.android.apps.mytracks.util.DialogUtils;
import com.google.android.apps.mytracks.util.StringUtils;
import com.google.android.maps.mytracks.R;

import android.app.Dialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnCreateContextMenuListener;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Activity which shows the list of waypoints in a track.
 *
 * @author Leif Hendrik Wilden
 */
public class WaypointsList extends ListActivity implements View.OnClickListener {

  private static final int DIALOG_DELETE_CURRENT_ID = 0;
  
  private int contextPosition = -1;
  private long trackId = -1;
  private long selectedWaypointId = -1;
  private ListView listView = null;
  private Button insertWaypointButton = null;
  private Button insertStatisticsButton = null;
  private long recordingTrackId = -1;
  private MyTracksProviderUtils providerUtils;
  private TrackRecordingServiceConnection serviceConnection;

  private Cursor waypointsCursor = null;

  private final OnCreateContextMenuListener contextMenuListener =
      new OnCreateContextMenuListener() {
        public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
          menu.setHeaderTitle(R.string.marker_list_context_menu_title);
          AdapterView.AdapterContextMenuInfo info =
              (AdapterView.AdapterContextMenuInfo) menuInfo;
          contextPosition = info.position;
          selectedWaypointId = WaypointsList.this.listView.getAdapter()
              .getItemId(contextPosition);
          Waypoint waypoint = providerUtils.getWaypoint(info.id);
          if (waypoint != null) {
            int type = waypoint.getType();
            menu.add(Menu.NONE, Constants.MENU_SHOW, Menu.NONE, R.string.marker_list_show_on_map);
            menu.add(Menu.NONE, Constants.MENU_EDIT, Menu.NONE, R.string.marker_list_edit_marker);
            MenuItem deleteMenu = menu.add(
                Menu.NONE, Constants.MENU_DELETE, Menu.NONE, R.string.marker_list_delete_marker);
            deleteMenu.setEnabled(recordingTrackId < 0
                || type == Waypoint.TYPE_WAYPOINT
                || type == Waypoint.TYPE_STATISTICS
                || info.id != providerUtils.getLastWaypointId(recordingTrackId));
          }
        }
      };

  @Override
  protected void onListItemClick(ListView l, View v, int position, long id) {
    editWaypoint(id);
  }

  @Override
  public boolean onMenuItemSelected(int featureId, MenuItem item) {
    if (!super.onMenuItemSelected(featureId, item)) {
      switch (item.getItemId()) {
        case Constants.MENU_SHOW: {
          Intent result = new Intent();
          result.putExtra("trackid", trackId);
          result.putExtra(WaypointDetails.WAYPOINT_ID_EXTRA, selectedWaypointId);
          setResult(RESULT_OK, result);
          finish();
          return true;
        }
        case Constants.MENU_EDIT: {
          editWaypoint(selectedWaypointId);
          return true;
        }
        case Constants.MENU_DELETE: {
          showDialog(DIALOG_DELETE_CURRENT_ID);          
        }
      }
    }
    return false;
  }

  private void editWaypoint(long waypointId) {
    Intent intent = new Intent(this, WaypointDetails.class);
    intent.putExtra("trackid", trackId);
    intent.putExtra(WaypointDetails.WAYPOINT_ID_EXTRA, waypointId);
    startActivity(intent);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    providerUtils = MyTracksProviderUtils.Factory.get(this);
    serviceConnection = new TrackRecordingServiceConnection(this, null);

    // We don't need a window title bar:
    requestWindowFeature(Window.FEATURE_NO_TITLE);

    setContentView(R.layout.mytracks_waypoints_list);

    listView = getListView();
    listView.setOnCreateContextMenuListener(contextMenuListener);

    insertWaypointButton =
        (Button) findViewById(R.id.waypointslist_btn_insert_waypoint);
    insertWaypointButton.setOnClickListener(this);
    insertStatisticsButton =
        (Button) findViewById(R.id.waypointslist_btn_insert_statistics);
    insertStatisticsButton.setOnClickListener(this);
    SharedPreferences preferences = getSharedPreferences(
        Constants.SETTINGS_NAME, Context.MODE_PRIVATE);

    // TODO: Get rid of selected and recording track IDs
    long selectedTrackId = -1;
    if (preferences != null) {
      recordingTrackId =
          preferences.getLong(getString(R.string.recording_track_key), -1);
      selectedTrackId =
          preferences.getLong(getString(R.string.selected_track_key), -1);
    }
    boolean selectedRecording = selectedTrackId > 0
        && selectedTrackId == recordingTrackId;
    insertWaypointButton.setEnabled(selectedRecording);
    insertStatisticsButton.setEnabled(selectedRecording);

    if (getIntent() != null && getIntent().getExtras() != null) {
      trackId = getIntent().getExtras().getLong("trackid", -1);
    } else {
      trackId = -1;
    }

    final long firstWaypointId = providerUtils.getFirstWaypointId(trackId);
    waypointsCursor = getContentResolver().query(
        WaypointsColumns.CONTENT_URI, null,
        WaypointsColumns.TRACKID + "=" + trackId + " AND "
        + WaypointsColumns._ID + "!=" + firstWaypointId, null, null);
    startManagingCursor(waypointsCursor);
    setListAdapter();
  }

  @Override
  protected void onResume() {
    super.onResume();

    serviceConnection.bindIfRunning();
  }

  @Override
  protected void onDestroy() {
    serviceConnection.unbind();

    super.onDestroy();
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    switch (id) {
      case DIALOG_DELETE_CURRENT_ID:
        return DialogUtils.createConfirmationDialog(this,
            getString(R.string.marker_list_delete_marker_confirm_message),
            new DialogInterface.OnClickListener() {
              @Override
              public void onClick(DialogInterface dialog, int which) {
                providerUtils.deleteWaypoint(
                    selectedWaypointId, new DescriptionGeneratorImpl(WaypointsList.this));
              }
            });
      default:
        return null;
    }
  }
  
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.search_only, menu);
    return true;
  }
  
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case R.id.menu_search:
        onSearchRequested();
        return true;
      default:
        return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public void onClick(View v) {
    WaypointCreationRequest request;
    switch (v.getId()) {
      case R.id.waypointslist_btn_insert_waypoint:
        request = WaypointCreationRequest.DEFAULT_MARKER;
        break;
      case R.id.waypointslist_btn_insert_statistics:
        request = WaypointCreationRequest.DEFAULT_STATISTICS;
        break;
      default:
        return;
    }
    long id = insertWaypoint(request);
    if (id < 0) {
      Toast.makeText(this, R.string.marker_insert_error, Toast.LENGTH_LONG).show();
      Log.e(Constants.TAG, "Failed to insert marker.");
      return;
    }
    Intent intent = new Intent(this, WaypointDetails.class);
    intent.putExtra(WaypointDetails.WAYPOINT_ID_EXTRA, id);
    startActivity(intent);
  }

  private long insertWaypoint(WaypointCreationRequest request) {
    try {
      ITrackRecordingService trackRecordingService = serviceConnection.getServiceIfBound();
      if (trackRecordingService != null) {
        long waypointId = trackRecordingService.insertWaypoint(request);
        if (waypointId >= 0) {
          Toast.makeText(this, R.string.marker_insert_success, Toast.LENGTH_LONG).show();
          return waypointId;
        }
      } else {
        Log.e(TAG, "Not connected to service, not inserting waypoint");
      }
    } catch (RemoteException e) {
      Log.e(Constants.TAG, "Cannot insert marker.", e);
    } catch (IllegalStateException e) {
      Log.e(Constants.TAG, "Cannot insert marker.", e);
    }

    return -1;
  }

  private void setListAdapter() {
    // Get a cursor with all tracks
    SimpleCursorAdapter adapter = new SimpleCursorAdapter(
        this,
        R.layout.mytracks_marker_item,
        waypointsCursor,
        new String[] { WaypointsColumns.NAME, WaypointsColumns.TIME,
                       WaypointsColumns.CATEGORY, WaypointsColumns.TYPE },
        new int[] { R.id.waypointslist_item_name,
                    R.id.waypointslist_item_time,
                    R.id.waypointslist_item_category,
                    R.id.waypointslist_item_icon });

    final int timeIdx =
        waypointsCursor.getColumnIndexOrThrow(WaypointsColumns.TIME);
    final int typeIdx =
        waypointsCursor.getColumnIndexOrThrow(WaypointsColumns.TYPE);
    adapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
      @Override
      public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
        if (columnIndex == timeIdx) {
          long time = cursor.getLong(timeIdx);
          TextView textView = (TextView) view;

          if (time == 0) {
            textView.setVisibility(View.GONE);
          } else {
            textView.setText(StringUtils.formatDateTime(WaypointsList.this, time));
            textView.setVisibility(View.VISIBLE);
          }
        } else if (columnIndex == typeIdx) {
          int type = cursor.getInt(typeIdx);
          ImageView imageView = (ImageView) view;
          imageView.setImageDrawable(getResources().getDrawable(
              type == Waypoint.TYPE_STATISTICS
                  ? R.drawable.ylw_pushpin
                  : R.drawable.blue_pushpin));
        } else {
          TextView textView = (TextView) view;
          textView.setText(cursor.getString(columnIndex));
          textView.setVisibility(
              textView.getText().length() < 1 ? View.GONE : View.VISIBLE);
        }
        return true;
      }
    });
    setListAdapter(adapter);
  }
}
