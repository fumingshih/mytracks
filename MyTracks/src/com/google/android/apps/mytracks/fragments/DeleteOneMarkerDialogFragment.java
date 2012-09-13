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

import com.google.android.apps.mytracks.MarkerListActivity;
import com.google.android.apps.mytracks.content.DescriptionGeneratorImpl;
import com.google.android.apps.mytracks.content.MyTracksProviderUtils;
import com.google.android.apps.mytracks.util.DialogUtils;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.maps.mytracks.R;

import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;

/**
 * A DialogFragment to delete one marker.
 *
 * @author Jimmy Shih
 */
public class DeleteOneMarkerDialogFragment extends DialogFragment {

  public static final String DELETE_ONE_MARKER_DIALOG_TAG = "deleteOneMarkerDialog";
  private static final String KEY_MARKER_ID = "markerId";
  private static final String KEY_TRACK_ID = "trackId";

  public static DeleteOneMarkerDialogFragment newInstance(long markerId, long trackId) {
    Bundle bundle = new Bundle();
    bundle.putLong(KEY_MARKER_ID, markerId);
    bundle.putLong(KEY_TRACK_ID, trackId);

    DeleteOneMarkerDialogFragment deleteOneMarkerDialogFragment = new DeleteOneMarkerDialogFragment();
    deleteOneMarkerDialogFragment.setArguments(bundle);
    return deleteOneMarkerDialogFragment;
  }

  private FragmentActivity activity;
  
  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    activity = getActivity();
    return DialogUtils.createConfirmationDialog(activity,
        R.string.marker_delete_one_marker_confirm_message, new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialog, int which) {
            new Thread(new Runnable() {
              @Override
              public void run() {
                MyTracksProviderUtils.Factory.get(activity).deleteWaypoint(
                    getArguments().getLong(KEY_MARKER_ID),
                    new DescriptionGeneratorImpl(activity));
              }
            }).start();
            Intent intent = IntentUtils.newIntent(activity, MarkerListActivity.class)
                .putExtra(MarkerListActivity.EXTRA_TRACK_ID, getArguments().getLong(KEY_TRACK_ID));
            startActivity(intent);
            // Close the activity since its content can change after delete.
            activity.finish();
          }
        });
  }
}