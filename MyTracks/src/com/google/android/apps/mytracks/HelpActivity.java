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

import com.google.android.apps.mytracks.fragments.AboutDialogFragment;
import com.google.android.apps.mytracks.util.StringUtils;
import com.google.android.maps.mytracks.R;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;

/**
 * An activity that displays the help page.
 * 
 * @author Sandor Dornbush
 */
public class HelpActivity extends AbstractMyTracksActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    TextView mapsPublicUnlisted = (TextView) findViewById(R.id.help_maps_public_unlisted_answer);
    mapsPublicUnlisted.setText(StringUtils.getHtml(
        this, R.string.help_maps_public_unlisted_answer, R.string.maps_public_unlisted_url));

    TextView sendTrack = (TextView) findViewById(R.id.help_send_track_answer);
    sendTrack.setText(StringUtils.getHtml(
        this, R.string.help_send_track_answer, R.string.send_google_maps_url,
        R.string.send_google_fusion_tables_url, R.string.send_google_docs_url));

    findViewById(R.id.help_ok).setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        finish();
      }
    });
    findViewById(R.id.help_about).setOnClickListener(new OnClickListener() {
      public void onClick(View v) {
        new AboutDialogFragment().show(
            getSupportFragmentManager(), AboutDialogFragment.ABOUT_DIALOG_TAG);
      }
    });
  }

  @Override
  protected int getLayoutResId() {
    return R.layout.help;
  }
}
