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
package com.google.android.apps.mytracks;

import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.content.TracksColumns;
import com.google.android.apps.mytracks.io.file.GpxImporter;
import com.google.android.apps.mytracks.util.FileUtils;
import com.google.android.apps.mytracks.util.SystemUtils;
import com.google.android.maps.mytracks.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager.WakeLock;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

/**
 * A class that will import all GPX tracks in /sdcard/MyTracks/gpx/
 *
 * @author David Piggott
 */
public class ImportAllTracks {

  private final Activity activity;
  private FileUtils fileUtils;
  private boolean singleTrackSelected;
  private String gpxPath;  
  private WakeLock wakeLock;
  private ProgressDialog progress;
  private int gpxFileCount;
  private int importSuccessCount;
  private long importedTrackIds[];
  
  public ImportAllTracks(Activity activity) {
    this(activity, null);
  }

  /**
   * Constructor to import tracks.
   *
   * @param activity the activity
   * @param path path of the gpx file to import and display. If null, then just
   *          import all the gpx files under MyTracks/gpx and do not display any
   *          track.
   */
  public ImportAllTracks(Activity activity, String path) {
    Log.i(Constants.TAG, "ImportAllTracks: Starting");
    this.activity = activity;
    fileUtils = new FileUtils();
    singleTrackSelected = path != null;
    gpxPath = path == null ? fileUtils.buildExternalDirectoryPath("gpx") : path;
    new Thread(runner).start();
  }
  
  private final Runnable runner = new Runnable() {
    public void run() {
      aquireLocksAndImport();
    }
  };

  /**
   * Makes sure that we keep the phone from sleeping. See if there is a current
   * track. Acquire a wake lock if there is no current track.
   */
  private void aquireLocksAndImport() {
    SharedPreferences prefs = activity.getSharedPreferences(
        Constants.SETTINGS_NAME, Context.MODE_PRIVATE);
    long recordingTrackId = -1;
    if (prefs != null) {
      recordingTrackId = prefs.getLong(activity.getString(R.string.recording_track_key), -1);
    }
    if (recordingTrackId == -1) {
      wakeLock = SystemUtils.acquireWakeLock(activity, wakeLock);
    }

    // Now we can safely import everything.
    importAll();

    // Release the wake lock if we acquired one.
    // TODO check what happens if we started recording after getting this lock.
    if (wakeLock != null && wakeLock.isHeld()) {
      wakeLock.release();
      Log.i(Constants.TAG, "ImportAllTracks: Releasing wake lock.");
    }

    activity.runOnUiThread(new Thread() {
      @Override
      public void run() {
        showDoneDialog();
      }
    });
  }

  private void showDoneDialog() {
    Log.i(Constants.TAG, "ImportAllTracks: Done");
    AlertDialog.Builder builder = new AlertDialog.Builder(activity);
    if (gpxFileCount == 0) {
      builder.setMessage(activity.getString(R.string.import_no_file, gpxPath));
    } else {
      String totalFiles = activity.getResources().getQuantityString(
          R.plurals.importGpxFiles, gpxFileCount, gpxFileCount);
      builder.setMessage(
          activity.getString(R.string.import_success, importSuccessCount, totalFiles, gpxPath));
    }
    builder.setPositiveButton(R.string.generic_ok, new DialogInterface.OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        if (singleTrackSelected) {
          long lastTrackId = importedTrackIds[importedTrackIds.length - 1];
          Uri trackUri = ContentUris.withAppendedId(TracksColumns.CONTENT_URI, lastTrackId);

          Intent intent = new Intent(Intent.ACTION_VIEW);
          intent.setDataAndType(trackUri, TracksColumns.CONTENT_ITEMTYPE);
          intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
          activity.startActivity(intent);
          activity.finish();
        }
      }
    });
    builder.show();
  }

  private void makeProgressDialog(final int trackCount) {
    String importMsg = activity.getString(R.string.track_list_import_all);
    progress = new ProgressDialog(activity);
    progress.setIcon(android.R.drawable.ic_dialog_info);
    progress.setTitle(importMsg);
    progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
    progress.setMax(trackCount);
    progress.setProgress(0);
    progress.show();
  }

  /**
   * Actually import the tracks. This should be called after the wake locks have
   * been acquired.
   */
  private void importAll() {
    MyTracksProviderUtils providerUtils = MyTracksProviderUtils.Factory.get(activity);

    if (!fileUtils.isSdCardAvailable()) {
      return;
    }

    List<File> gpxFiles = getGpxFiles();
    gpxFileCount = gpxFiles.size();
    if (gpxFileCount == 0) {
      return;
    }

    Log.i(Constants.TAG, "ImportAllTracks: Importing: " + gpxFileCount + " tracks.");
    activity.runOnUiThread(new Runnable() {
      public void run() {
        makeProgressDialog(gpxFileCount);
      }
    });

    Iterator<File> gpxFilesIterator = gpxFiles.iterator();
    for (int currentFileNumber = 0; gpxFilesIterator.hasNext(); currentFileNumber++) {
      File currentFile = gpxFilesIterator.next();
      final int status = currentFileNumber;
      activity.runOnUiThread(new Runnable() {
        public void run() {
          synchronized (this) {
            if (progress == null) {
              return;
            }
            progress.setProgress(status);
          }
        }
      });
      if (importFile(currentFile, providerUtils)) {
        importSuccessCount++;
      }
    }

    if (progress != null) {
      synchronized (this) {
        progress.dismiss();
        progress = null;
      }
    }
  }

  /**
   * Attempts to import a GPX file. Returns true on success, issues error
   * notifications and returns false on failure.
   */
  private boolean importFile(File gpxFile, MyTracksProviderUtils providerUtils) {
    Log.i(Constants.TAG, "ImportAllTracks: importing: " + gpxFile.getName());
    try {
      importedTrackIds = GpxImporter.importGPXFile(new FileInputStream(gpxFile), providerUtils);
      return true;
    } catch (FileNotFoundException e) {
      Log.w(Constants.TAG, "GPX file wasn't found/went missing: "
          + gpxFile.getAbsolutePath(), e);
    } catch (ParserConfigurationException e) {
      Log.w(Constants.TAG, "Error parsing file: " + gpxFile.getAbsolutePath(), e);
    } catch (SAXException e) {
      Log.w(Constants.TAG, "Error parsing file: " + gpxFile.getAbsolutePath(), e);
    } catch (IOException e) {
      Log.w(Constants.TAG, "Error reading file: " + gpxFile.getAbsolutePath(), e);
    }
    Toast.makeText(activity, activity.getString(R.string.import_error, gpxFile.getName()),
        Toast.LENGTH_LONG).show();
    return false;
  }

  /**
   * Gets a list of the GPX files. If singleTrackSelected is true, returns a
   * list containing just the gpxPath file. If singleTrackSelected is false,
   * returns a list of GPX files under the gpxPath directory.
   */
  private List<File> getGpxFiles() {
    List<File> gpxFiles = new LinkedList<File>();    
    File file = new File(gpxPath);
    if (singleTrackSelected) {
      gpxFiles.add(file);
    } else {      
      File[] gpxFileCandidates = file.listFiles();
      if (gpxFileCandidates != null) {
        for (File candidate : gpxFileCandidates) {
          if (!candidate.isDirectory() && candidate.getName().endsWith(".gpx")) {
            gpxFiles.add(candidate);
          }
        }
      }
    }
    return gpxFiles;
  }
}