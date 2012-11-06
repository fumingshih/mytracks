// Copyright 2012 Google Inc. All Rights Reserved.

package com.google.android.apps.mytracks.io.fusiontables;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.content.DescriptionGenerator;
import com.google.android.apps.mytracks.content.DescriptionGeneratorImpl;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.io.sendtogoogle.AbstractSendAsyncTask;
import com.google.android.apps.mytracks.io.sendtogoogle.SendToGoogleUtils;
import com.google.android.apps.mytracks.stats.TripStatisticsUpdater;
import com.google.android.apps.mytracks.util.ApiAdapterFactory;
import com.google.android.apps.mytracks.util.LocationUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.apps.mytracks.util.SystemUtils;
import com.google.android.maps.mytracks.R;
import com.google.api.client.googleapis.GoogleHeaders;
import com.google.api.client.googleapis.MethodOverride;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.util.Strings;
import com.google.common.annotations.VisibleForTesting;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * AsyncTask to send a track to Google Fusion Tables.
 * 
 * @author Jimmy Shih
 */
public class SendFusionTablesAsyncTask extends AbstractSendAsyncTask {

  @VisibleForTesting
  public static final String APP_NAME_PREFIX = "Google-MyTracks-";
  private static final String SQL_KEY = "sql=";
  @VisibleForTesting
  public static final String CONTENT_TYPE = "application/x-www-form-urlencoded";
  @VisibleForTesting
  public static final String
      FUSION_TABLES_BASE_URL = "https://www.google.com/fusiontables/api/query";
  private static final int MAX_POINTS_PER_UPLOAD = 2048;
  @VisibleForTesting
  public static final String GDATA_VERSION = "2";

  private static final int PROGRESS_CREATE_TABLE = 0;
  private static final int PROGRESS_UNLIST_TABLE = 5;
  private static final int PROGRESS_UPLOAD_DATA_MIN = 10;
  private static final int PROGRESS_UPLOAD_DATA_MAX = 90;
  private static final int PROGRESS_UPLOAD_WAYPOINTS = 95;
  private static final int PROGRESS_COMPLETE = 100;

  // See
  // http://support.google.com/fusiontables/bin/answer.py?hl=en&answer=185991
  private static final String MARKER_TYPE_START = "large_green";
  private static final String MARKER_TYPE_END = "large_red";
  private static final String MARKER_TYPE_WAYPOINT = "large_blue";
  private static final String MARKER_TYPE_STATISTICS = "large_yellow";

  private static final String TAG = SendFusionTablesAsyncTask.class.getSimpleName();

  private final Context context;
  private final long trackId;
  private final Account account;
  private final MyTracksProviderUtils myTracksProviderUtils;
  private final HttpRequestFactory httpRequestFactory;

  // The following variables are for per upload states
  private String authToken;
  private String tableId;
  int currentSegment;

  public SendFusionTablesAsyncTask(
      SendFusionTablesActivity activity, long trackId, Account account) {
    super(activity);
    this.trackId = trackId;
    this.account = account;

    context = activity.getApplicationContext();
    myTracksProviderUtils = MyTracksProviderUtils.Factory.get(context);
    HttpTransport transport = ApiAdapterFactory.getApiAdapter().getHttpTransport();
    httpRequestFactory = transport.createRequestFactory(new MethodOverride());
  }

  @Override
  protected void closeConnection() {
    // No action needed for Google Fusion Tables
  }

  @Override
  protected void saveResult() {
    Track track = myTracksProviderUtils.getTrack(trackId);
    if (track == null) {
      Log.d(TAG, "No track for " + trackId);
      return;
    }
    track.setTableId(tableId);
    myTracksProviderUtils.updateTrack(track);
  }

  @Override
  protected boolean performTask() {
    // Reset the per upload states
    authToken = null;
    tableId = null;
    currentSegment = 1;

    try {
      authToken = AccountManager.get(context)
          .blockingGetAuthToken(account, SendFusionTablesUtils.SERVICE, false);
    } catch (OperationCanceledException e) {
      Log.d(TAG, "Unable to get auth token", e);
      return retryTask();
    } catch (AuthenticatorException e) {
      Log.d(TAG, "Unable to get auth token", e);
      return retryTask();
    } catch (IOException e) {
      Log.d(TAG, "Unable to get auth token", e);
      return retryTask();
    }

    Track track = myTracksProviderUtils.getTrack(trackId);
    if (track == null) {
      Log.d(TAG, "No track for " + trackId);
      return false;
    }

    // Create a new table
    publishProgress(PROGRESS_CREATE_TABLE);
    if (!createNewTable(track)) {
      // Retry upload in case the auth token is invalid
      return retryTask();
    }

    // Unlist table
    publishProgress(PROGRESS_UNLIST_TABLE);
    if (!unlistTable()) {
      return false;
    }

    // Upload all the track points plus the start and end markers
    publishProgress(PROGRESS_UPLOAD_DATA_MIN);
    if (!uploadAllTrackPoints(track)) {
      return false;
    }

    // Upload all the waypoints
    publishProgress(PROGRESS_UPLOAD_WAYPOINTS);
    if (!uploadWaypoints()) {
      return false;
    }

    publishProgress(PROGRESS_COMPLETE);
    return true;
  }

  @Override
  protected void invalidateToken() {
    AccountManager.get(context).invalidateAuthToken(Constants.ACCOUNT_TYPE, authToken);
  }

  /**
   * Creates a new table.
   * 
   * @param track the track
   * @return true if success.
   */
  private boolean createNewTable(Track track) {
    String query = "CREATE TABLE '" + SendFusionTablesUtils.escapeSqlString(track.getName())
        + "' (name:STRING,description:STRING,geometry:LOCATION,marker:STRING)";
    return sendQuery(query, true);
  }

  /**
   * Unlists a table.
   * 
   * @return true if success.
   */
  private boolean unlistTable() {
    String query = "UPDATE TABLE " + tableId + " SET VISIBILITY = UNLISTED";
    return sendQuery(query, false);
  }

  /**
   * Uploads all the points in a track.
   * 
   * @param track the track
   * @return true if success.
   */
  private boolean uploadAllTrackPoints(Track track) {
    Cursor cursor = null;
    try {
      cursor = myTracksProviderUtils.getTrackPointCursor(trackId, 0, -1, false);
      if (cursor == null) {
        Log.d(TAG, "Location cursor is null");
        return false;
      }

      int count = cursor.getCount();
      List<Location> locations = new ArrayList<Location>(MAX_POINTS_PER_UPLOAD);
      Location lastLocation = null;

      // For chart server, limit the number of elevation readings to 250.
      int elevationSamplingFrequency = Math.max(1, (int) (count / 250.0));
      Vector<Double> distances = new Vector<Double>();
      Vector<Double> elevations = new Vector<Double>();
      TripStatisticsUpdater tripStatisticsUpdater = new TripStatisticsUpdater(
          track.getTripStatistics().getStartTime());
      int minRecordingDistance = PreferencesUtils.getInt(context,
          R.string.min_recording_distance_key, PreferencesUtils.MIN_RECORDING_DISTANCE_DEFAULT);

      for (int i = 0; i < count; i++) {
        cursor.moveToPosition(i);

        Location location = myTracksProviderUtils.createTrackPoint(cursor);
        locations.add(location);

        if (i == 0) {
          // Create a start marker
          String name = context.getString(R.string.marker_label_start, track.getName());
          if (!createNewPoint(name, "", location, MARKER_TYPE_START)) {
            Log.d(TAG, "Unable to create the start marker");
            return false;
          }
        }

        tripStatisticsUpdater.addLocation(location, minRecordingDistance);
        if (i % elevationSamplingFrequency == 0) {
          distances.add(tripStatisticsUpdater.getTripStatistics().getTotalDistance());
          elevations.add(tripStatisticsUpdater.getSmoothedElevation());
        }
        if (LocationUtils.isValidLocation(location)) {
          lastLocation = location;
        }

        // Upload periodically
        int readCount = i + 1;
        if (readCount % MAX_POINTS_PER_UPLOAD == 0) {
          if (!prepareAndUploadPoints(track, locations, false)) {
            Log.d(TAG, "Unable to upload points");
            return false;
          }
          updateProgress(readCount, count);
          locations.clear();
        }
      }

      // Do a final upload with the remaining locations
      if (!prepareAndUploadPoints(track, locations, true)) {
        Log.d(TAG, "Unable to upload points");
        return false;
      }

      // Create an end marker
      if (lastLocation != null) {
        distances.add(tripStatisticsUpdater.getTripStatistics().getTotalDistance());
        elevations.add(tripStatisticsUpdater.getSmoothedElevation());
        DescriptionGenerator descriptionGenerator = new DescriptionGeneratorImpl(context);
        track.setDescription(
            descriptionGenerator.generateTrackDescription(track, distances, elevations, true));
        String name = context.getString(R.string.marker_label_end, track.getName());
        if (!createNewPoint(name, track.getDescription(), lastLocation, MARKER_TYPE_END)) {
          Log.d(TAG, "Unable to create the end marker");
          return false;
        }
      }

      return true;
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

  /**
   * Prepares and uploads a list of locations from a track.
   * 
   * @param track the track
   * @param locations the locations from the track
   * @param lastBatch true if it is the last batch of locations
   */
  private boolean prepareAndUploadPoints(Track track, List<Location> locations, boolean lastBatch) {
    // Prepare locations
    ArrayList<Track> splitTracks = SendToGoogleUtils.prepareLocations(track, locations);

    // Upload segments
    boolean onlyOneSegment = lastBatch && currentSegment == 1 && splitTracks.size() == 1;
    for (Track splitTrack : splitTracks) {
      if (!onlyOneSegment) {
        splitTrack.setName(context.getString(
            R.string.send_google_track_part_label, splitTrack.getName(), currentSegment));
      }
      if (!createNewLineString(splitTrack)) {
        Log.d(TAG, "Upload points failed");
        return false;
      }
      currentSegment++;
    }
    return true;
  }

  /**
   * Uploads all the waypoints.
   * 
   * @return true if success.
   */
  private boolean uploadWaypoints() {
    Cursor cursor = null;
    try {
      cursor = myTracksProviderUtils.getWaypointCursor(
          trackId, 0, Constants.MAX_LOADED_WAYPOINTS_POINTS);
      if (cursor != null && cursor.moveToFirst()) {
        // This will skip the first waypoint (it carries the stats for the
        // track).
        while (cursor.moveToNext()) {
          Waypoint wpt = myTracksProviderUtils.createWaypoint(cursor);
          String type = wpt.getType() == Waypoint.TYPE_STATISTICS ? MARKER_TYPE_STATISTICS
              : MARKER_TYPE_WAYPOINT;
          if (!createNewPoint(wpt.getName(), wpt.getDescription(), wpt.getLocation(), type)) {
            Log.d(TAG, "Upload waypoints failed");
            return false;
          }
        }
      }
      return true;
    } finally {
      if (cursor != null) {
        cursor.close();
      }
    }
  }

  /**
   * Creates a new row in Google Fusion Tables representing a marker as a point.
   * 
   * @param name the marker name
   * @param description the marker description
   * @param location the marker location
   * @param type the marker type
   * @return true if success.
   */
  private boolean createNewPoint(String name, String description, Location location, String type) {
    String query = "INSERT INTO " + tableId + " (name,description,geometry,marker) VALUES "
        + SendFusionTablesUtils.formatSqlValues(
            name, description, SendFusionTablesUtils.getKmlPoint(location), type);
    return sendQuery(query, false);
  }

  /**
   * Creates a new row in Google Fusion Tables representing the track as a line
   * segment.
   * 
   * @param track the track
   * @return true if success.
   */
  private boolean createNewLineString(Track track) {
    String query = "INSERT INTO " + tableId
        + " (name,description,geometry) VALUES " + SendFusionTablesUtils.formatSqlValues(
            track.getName(), track.getDescription(),
            SendFusionTablesUtils.getKmlLineString(track.getLocations()));
    return sendQuery(query, false);
  }

  /**
   * Sends a query to Google Fusion Tables.
   * 
   * @param query the Fusion Tables SQL query
   * @param setTableId true to set the table id
   * @return true if success.
   */
  private boolean sendQuery(String query, boolean setTableId) {
    Log.d(TAG, "SendQuery: " + query);

    if (isCancelled()) {
      return false;
    }

    GenericUrl url = new GenericUrl(FUSION_TABLES_BASE_URL);
    String sql;
    try {
      sql = SQL_KEY + URLEncoder.encode(query, "UTF-8");
    } catch (UnsupportedEncodingException e1) {
      Log.d(TAG, "Unable to encode query", e1);
      return false;
    }
    ByteArrayInputStream inputStream = new ByteArrayInputStream(Strings.toBytesUtf8(sql));
    InputStreamContent inputStreamContent = new InputStreamContent(null, inputStream);
    HttpRequest request;
    try {
      request = httpRequestFactory.buildPostRequest(url, inputStreamContent);
    } catch (IOException e) {
      Log.d(TAG, "Unable to build request", e);
      return false;
    }

    GoogleHeaders headers = new GoogleHeaders();
    headers.setApplicationName(APP_NAME_PREFIX + SystemUtils.getMyTracksVersion(context));
    headers.gdataVersion = GDATA_VERSION;
    headers.setGoogleLogin(authToken);
    headers.setContentType(CONTENT_TYPE);
    request.setHeaders(headers);

    HttpResponse response;
    try {
      response = request.execute();
    } catch (IOException e) {
      Log.d(TAG, "Unable to execute request", e);
      return false;
    }
    boolean isSuccess = response.isSuccessStatusCode();
    if (isSuccess) {
      InputStream content = null;
      try {
        content = response.getContent();
        if (setTableId) {
          tableId = SendFusionTablesUtils.getTableId(content);
          if (tableId == null) {
            Log.d(TAG, "tableId is null");
            return false;
          }
        }
      } catch (IOException e) {
        Log.d(TAG, "Unable to get response", e);
        return false;
      } finally {
        if (content != null) {
          try {
            content.close();
          } catch (IOException e) {
            Log.d(TAG, "Unable to close content", e);
          }
        }
      }
    } else {
      Log.d(TAG,
          "sendQuery failed: " + response.getStatusMessage() + ": " + response.getStatusCode());
      return false;
    }
    return true;
  }

  /**
   * Updates the progress based on the number of locations uploaded.
   * 
   * @param uploaded the number of uploaded locations
   * @param total the number of total locations
   */
  private void updateProgress(int uploaded, int total) {
    double totalPercentage = (double) uploaded / total;
    double scaledPercentage = totalPercentage
        * (PROGRESS_UPLOAD_DATA_MAX - PROGRESS_UPLOAD_DATA_MIN) + PROGRESS_UPLOAD_DATA_MIN;
    publishProgress((int) scaledPercentage);
  }
}
