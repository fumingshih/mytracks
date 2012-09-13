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

import com.google.android.apps.mytracks.io.sendtogoogle.ConfirmSharingActivity;
import com.google.android.apps.mytracks.io.sendtogoogle.SendRequest;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.maps.mytracks.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.ShareCompat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * A DialogFragment to choose an activity.
 * 
 * @author Jimmy Shih
 */
public class ChooseActivityDialogFragment extends DialogFragment {

  public static final String CHOOSE_ACTIVITY_DIALOG_TAG = "chooseActivityDialog";

  private static final String KEY_TRACK_ID = "trackId";
  private static final String KEY_TRACK_URL = "trackUrl";

  private FragmentActivity activity;
  private PackageManager packageManager;

  public static ChooseActivityDialogFragment newInstance(long trackId, String trackUrl) {
    Bundle bundle = new Bundle();
    bundle.putLong(KEY_TRACK_ID, trackId);
    bundle.putString(KEY_TRACK_URL, trackUrl);

    ChooseActivityDialogFragment chooseActivityDialogFragment = new ChooseActivityDialogFragment();
    chooseActivityDialogFragment.setArguments(bundle);
    return chooseActivityDialogFragment;
  }

  @Override
  public Dialog onCreateDialog(Bundle savedInstanceState) {
    activity = getActivity();
    packageManager = activity.getPackageManager();
    List<DisplayInfo> displayInfos = getDisplayInfos();

    ArrayAdapter<DisplayInfo> arrayAdapter = new ArrayAdapter<DisplayInfo>(activity,
        R.layout.choose_activity_list_item, R.id.choose_activity_list_item_text1, displayInfos) {
        @Override
      public View getView(int position, View convertView, ViewGroup parent) {
        View view;
        if (convertView == null) {
          view = activity.getLayoutInflater()
              .inflate(R.layout.choose_activity_list_item, parent, false);
        } else {
          view = convertView;
        }
        DisplayInfo displayInfo = getItem(position);
        TextView text1 = (TextView) view.findViewById(R.id.choose_activity_list_item_text1);
        TextView text2 = (TextView) view.findViewById(R.id.choose_activity_list_item_text2);
        ImageView icon = (ImageView) view.findViewById(R.id.choose_activity_list_item_icon);
        text1.setText(displayInfo.primaryLabel);
        if (displayInfo.secondaryLabel != null) {
          text2.setVisibility(View.VISIBLE);
          text2.setText(displayInfo.secondaryLabel);
        } else {
          text2.setVisibility(View.GONE);
        }
        icon.setImageDrawable(displayInfo.icon);
        return view;
      }
    };
    return new AlertDialog.Builder(activity)
        .setSingleChoiceItems(arrayAdapter, 0, new DialogInterface.OnClickListener() {
            @Override
          public void onClick(DialogInterface dialog, int which) {
            AlertDialog alertDialog = (AlertDialog) dialog;
            DisplayInfo displayInfo = (DisplayInfo) alertDialog.getListView()
                .getItemAtPosition(which);
            ActivityInfo activityInfo = displayInfo.resolveInfo.activityInfo;
            String packageName = activityInfo.applicationInfo.packageName;
            String className = activityInfo.name;

            long trackId = getArguments().getLong(KEY_TRACK_ID);
            String trackUrl = getArguments().getString(KEY_TRACK_URL);
            if (trackUrl == null) {
              SendRequest sendRequest = new SendRequest(trackId);
              sendRequest.setSendMaps(true);
              sendRequest.setNewMap(true);
              sendRequest.setSharingAppPackageName(packageName);
              sendRequest.setSharingAppClassName(className);
              Intent intent = IntentUtils.newIntent(activity, ConfirmSharingActivity.class)
                  .putExtra(SendRequest.SEND_REQUEST_KEY, sendRequest);
              startActivity(intent);
              dismiss();
            } else {
              Intent intent = IntentUtils.newShareUrlIntent(
                  activity, trackId, trackUrl, packageName, className);
              startActivity(intent);
              activity.finish();
            }
          }
        })
        .setTitle(R.string.share_track_picker_title)
        .create();
  }

  /**
   * Gets the display info.
   */
  private List<DisplayInfo> getDisplayInfos() {
    List<DisplayInfo> displayInfos = new ArrayList<DisplayInfo>();
    Intent intent = ShareCompat.IntentBuilder.from(activity)
        .setType(IntentUtils.TEXT_PLAIN_TYPE).getIntent();
    List<ResolveInfo> resolveInfos = packageManager.queryIntentActivities(
        intent, PackageManager.MATCH_DEFAULT_ONLY);
    if (resolveInfos != null && resolveInfos.size() > 0) {
      int size = resolveInfos.size();
      ResolveInfo firstResolveInfo = resolveInfos.get(0);
      for (int i = 1; i < size; i++) {
        ResolveInfo resolveInfo = resolveInfos.get(i);
        if (firstResolveInfo.priority != resolveInfo.priority
            || firstResolveInfo.isDefault != resolveInfo.isDefault) {
          while (i < size) {
            resolveInfos.remove(i);
            size--;
          }
        }
      }
      if (size > 1) {
        ResolveInfo.DisplayNameComparator displayNameComparator = new ResolveInfo.DisplayNameComparator(
            packageManager);
        Collections.sort(resolveInfos, displayNameComparator);
      }

      firstResolveInfo = resolveInfos.get(0);
      int start = 0;
      CharSequence firstLabel = firstResolveInfo.loadLabel(packageManager);
      for (int i = 1; i < size; i++) {
        if (firstLabel == null) {
          firstLabel = firstResolveInfo.activityInfo.packageName;
        }
        ResolveInfo resolveInfo = resolveInfos.get(i);
        CharSequence label = resolveInfo.loadLabel(packageManager);
        if (label == null) {
          label = resolveInfo.activityInfo.packageName;
        }
        if (label.equals(firstLabel)) {
          continue;
        }
        processGroup(resolveInfos, displayInfos, start, i - 1);
        firstResolveInfo = resolveInfo;
        firstLabel = label;
        start = i;
      }
      // Process last group
      processGroup(resolveInfos, displayInfos, start, size - 1);
    }
    return displayInfos;
  }

  /**
   * Contains display info.
   * 
   * @author Jimmy Shih
   */
  private final class DisplayInfo {
    private ResolveInfo resolveInfo;
    private CharSequence primaryLabel;
    private CharSequence secondaryLabel;
    private Drawable icon;

    public DisplayInfo(ResolveInfo resolveInfo, CharSequence primaryLabel,
        CharSequence secondaryLabel, Drawable icon) {
      this.resolveInfo = resolveInfo;
      this.primaryLabel = primaryLabel;
      this.secondaryLabel = secondaryLabel;
      this.icon = icon;
    }
  }

  /**
   * Processes a group of items with the same label.
   * 
   * @param resolveInfos list of resolve infos
   * @param displayInfos list of display infos
   * @param start start index
   * @param end end index
   */
  private void processGroup(
      List<ResolveInfo> resolveInfos, List<DisplayInfo> displayInfos, int start, int end) {
    ResolveInfo startResolveInfo = resolveInfos.get(start);
    CharSequence primaryLabel = startResolveInfo.loadLabel(packageManager);
    Drawable icon = startResolveInfo.loadIcon(packageManager);

    int num = end - start + 1;
    if (num == 1) {
      // Only one, set the secondary label to null
      displayInfos.add(new DisplayInfo(startResolveInfo, primaryLabel, null, icon));
    } else {
      // Decide package name or application name for the secondary label
      boolean usePackageName = false;
      CharSequence appName = startResolveInfo.activityInfo.applicationInfo.loadLabel(
          packageManager);
      if (appName == null) {
        usePackageName = true;
      } else {
        // Use HashSet to track duplicates
        HashSet<CharSequence> duplicates = new HashSet<CharSequence>();
        duplicates.add(appName);
        for (int i = start + 1; i <= end; i++) {
          ResolveInfo resolveInfo = resolveInfos.get(i);
          CharSequence name = resolveInfo.activityInfo.applicationInfo.loadLabel(packageManager);
          if ((name == null) || (duplicates.contains(name))) {
            usePackageName = true;
            break;
          } else {
            duplicates.add(name);
          }
        }
        // Clear HashSet for later use
        duplicates.clear();
      }
      for (int i = start; i <= end; i++) {
        ResolveInfo resolveInfo = resolveInfos.get(i);
        CharSequence secondaryLabel = usePackageName ? resolveInfo.activityInfo.packageName
            : resolveInfo.activityInfo.applicationInfo.loadLabel(packageManager);
        displayInfos.add(new DisplayInfo(resolveInfo, primaryLabel, secondaryLabel, icon));
      }
    }
  }
}