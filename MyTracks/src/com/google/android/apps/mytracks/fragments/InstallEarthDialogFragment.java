/*
 * Copyright 2012 Google Inc.
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

package com.google.android.apps.mytracks.fragments;

import com.google.android.apps.mytracks.io.file.SaveActivity;
import com.google.android.maps.mytracks.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.widget.Toast;

/**
 * A DialogFragment to install Google Earth.
 *
 * @author Jimmy Shih
 */
public class InstallEarthDialogFragment extends DialogFragment {

  public static final String INSTALL_EARTH_DIALOG_TAG = "installEarthDialog";

  private FragmentActivity activity;
  
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    activity = getActivity();
    return new AlertDialog.Builder(activity)
        .setMessage(R.string.track_detail_install_earth_message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            Intent intent = new Intent().setData(Uri.parse(SaveActivity.GOOGLE_EARTH_MARKET_URL));
            try {
              startActivity(intent);
            } catch (ActivityNotFoundException e) {
              Toast.makeText(
                  activity, R.string.track_detail_install_earth_error, Toast.LENGTH_LONG)
                  .show();
            }
          }
        })
        .create();
  }
}