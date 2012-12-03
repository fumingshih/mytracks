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
package com.google.android.apps.mytracks.endtoendtest;

import com.google.android.apps.mytracks.TrackListActivity;
import com.google.android.maps.mytracks.R;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.widget.CheckBox;

import java.util.ArrayList;

/**
 * Tests creating a track with markers, and editing and sending send a track.
 * 
 * @author Youtao Liu
 */
public class CreateTrackTest extends ActivityInstrumentationTestCase2<TrackListActivity> {

  private Instrumentation instrumentation;
  private TrackListActivity activityMyTracks;

  public CreateTrackTest() {
    super(TrackListActivity.class);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    instrumentation = getInstrumentation();
    activityMyTracks = getActivity();
    EndToEndTestUtils.setupForAllTest(instrumentation, activityMyTracks);
  }

  /**
   * Does not check any service and try to send to google.
   */
  public void testCreateAndSendTrack_notSend() {
    EndToEndTestUtils.createTrackIfEmpty(1, false);
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_send_google), true);
    EndToEndTestUtils.SOLO.waitForText(activityMyTracks.getString(R.string.send_google_title));
    EndToEndTestUtils.rotateCurrentActivity();
    instrumentation.waitForIdleSync();

    ArrayList<CheckBox> checkBoxs = EndToEndTestUtils.SOLO.getCurrentCheckBoxes();
    for (int i = 0; i < checkBoxs.size(); i++) {
      if (checkBoxs.get(i).isChecked()) {
        EndToEndTestUtils.SOLO.clickOnCheckBox(i);
      }
    }

    if (checkBoxs.size() < 3) {
      EndToEndTestUtils.SOLO.scrollDown();
      checkBoxs = EndToEndTestUtils.SOLO.getCurrentCheckBoxes();
      for (int i = 0; i < checkBoxs.size(); i++) {
        if (checkBoxs.get(i).isChecked()) {
          EndToEndTestUtils.SOLO.clickOnCheckBox(i);
        }
      }
    }

    instrumentation.waitForIdleSync();
    EndToEndTestUtils.SOLO.clickOnText(activityMyTracks.getString(R.string.send_google_send_now));
    assertTrue(EndToEndTestUtils.SOLO.waitForText(activityMyTracks
        .getString(R.string.send_google_no_service_selected)));
    // Stop here for do not really send.
  }
  
  /**
   * Tests editing a track.
   */
  public void testEditTrack() {
    EndToEndTestUtils.createTrackIfEmpty(1, false);
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_edit), true);

    String newTrackName = EndToEndTestUtils.TRACK_NAME_PREFIX + "_new" + System.currentTimeMillis();
    String newType = EndToEndTestUtils.activityType; 
    String newDesc = "desc" + newTrackName;

    instrumentation.waitForIdleSync();
    EndToEndTestUtils.rotateAllActivities();
    EndToEndTestUtils.SOLO.waitForText(activityMyTracks.getString(R.string.generic_save));
    sendKeys(KeyEvent.KEYCODE_DEL);

    EndToEndTestUtils.enterTextAvoidSoftKeyBoard(0, newTrackName);
    EndToEndTestUtils.enterTextAvoidSoftKeyBoard(1, newType);
    EndToEndTestUtils.enterTextAvoidSoftKeyBoard(2, newDesc);
    EndToEndTestUtils.SOLO.clickOnButton(activityMyTracks.getString(R.string.generic_save));
    instrumentation.waitForIdleSync();
    // Go back to track list.
    EndToEndTestUtils.SOLO.goBack();
    instrumentation.waitForIdleSync();
    assertTrue(EndToEndTestUtils.SOLO.searchText(newTrackName));
    assertTrue(EndToEndTestUtils.SOLO.searchText(newDesc));
  }

  /**
   * Creates one track with a two locations, a way point and a statistics point.
   */
  public void testCreateTrackWithMarkers() {
    EndToEndTestUtils.startRecording();
    instrumentation.waitForIdleSync();
    // Send Gps before send marker.
    EndToEndTestUtils.sendGps(2);
    if (EndToEndTestUtils.hasActionBar) {
      // Check the title is Recording.
      assertTrue(EndToEndTestUtils.SOLO.searchText(activityMyTracks
          .getString(R.string.generic_recording)));
    }
    
    EndToEndTestUtils.createWaypoint(0);
    EndToEndTestUtils.sendGps(2, 2);
    // Back to tracks list.
    EndToEndTestUtils.SOLO.goBack();
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.rotateAllActivities();
    EndToEndTestUtils.stopRecording(false);
    EndToEndTestUtils.trackName = EndToEndTestUtils.TRACK_NAME_PREFIX + System.currentTimeMillis();
    EndToEndTestUtils.SOLO.enterText(0, EndToEndTestUtils.trackName);
    EndToEndTestUtils.SOLO.enterText(1, EndToEndTestUtils.activityType);

    EndToEndTestUtils.SOLO.clickOnButton(activityMyTracks.getString(R.string.generic_save));

    instrumentation.waitForIdleSync();
    // Check the new track
    EndToEndTestUtils.SOLO.scrollUp();
    assertTrue(EndToEndTestUtils.SOLO.waitForText(EndToEndTestUtils.trackName, 1,
        EndToEndTestUtils.NORMAL_WAIT_TIME, true, false));
  }

  /**
   * Tests the display of track start time. Use relative time (x mins ago) for
   * the start time if it is within the past week. But only show the start time
   * if it is different from the track name.
   */
  public void testTrackStartTime() {
    // Delete all track first.
    EndToEndTestUtils.deleteAllTracks(); 
    // Reset all settings.
    EndToEndTestUtils.resetAllSettings(activityMyTracks, false);
    
    assertTrue(EndToEndTestUtils.SOLO.waitForText(activityMyTracks.getString(R.string.track_list_empty_message)));
    // Test should not show relative time.
    EndToEndTestUtils.startRecording();
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.stopRecording(false);
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.getButtonOnScreen(activityMyTracks.getString(R.string.generic_save), true,
        true);
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.SOLO.goBack();
    instrumentation.waitForIdleSync();
    assertFalse(EndToEndTestUtils.SOLO.searchText(EndToEndTestUtils.RELATIVE_STARTTIME_POSTFIX, 1, false, true));

    // Test should show relative time for createSimpleTrack would save a track
    // name that is different with the start time.
    EndToEndTestUtils.createSimpleTrack(2, true);
    assertTrue(EndToEndTestUtils.SOLO.waitForText(EndToEndTestUtils.RELATIVE_STARTTIME_POSTFIX, 1, EndToEndTestUtils.NORMAL_WAIT_TIME));
  }
  
  /**
   * Tests whether the split marker is created as setting.
   */
  public void testSplitSetting() {
    EndToEndTestUtils.startRecording();

    EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_split_frequency), true);
    boolean isFoundKM = EndToEndTestUtils.SOLO.searchText(EndToEndTestUtils.KM);
    if (isFoundKM) {
      EndToEndTestUtils.SOLO.clickOnText(EndToEndTestUtils.KM, 0);
    } else {
      EndToEndTestUtils.SOLO.clickOnText(EndToEndTestUtils.MILE, 0);
    }

    EndToEndTestUtils
        .getButtonOnScreen(activityMyTracks.getString(R.string.generic_ok), true, true);
    // Send Gps to give a distance more than one kilometer or one mile.
    EndToEndTestUtils.sendGps(20);    
    assertTrue(EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_markers),
        true));
    instrumentation.waitForIdleSync();
    if (EndToEndTestUtils.hasGpsSingal) {
      assertTrue(EndToEndTestUtils.SOLO.getCurrentListViews().get(0).getCount() > 0);
    } else {
      assertTrue(EndToEndTestUtils.SOLO.getCurrentListViews().get(0).getCount() == 0);
    }
    EndToEndTestUtils.SOLO.goBack();

    EndToEndTestUtils.stopRecording(true);
  }
  
  @Override
  protected void tearDown() throws Exception {
    EndToEndTestUtils.SOLO.finishOpenedActivities();
    super.tearDown();
  }

}