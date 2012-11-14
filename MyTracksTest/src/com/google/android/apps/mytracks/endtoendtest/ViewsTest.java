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
import com.google.android.apps.mytracks.fragments.MyTracksMapFragment;
import com.google.android.maps.mytracks.R;

import android.app.Instrumentation;
import android.test.ActivityInstrumentationTestCase2;
import android.widget.TextView;

/**
 * Tests switching views and the menu list of each view.
 * 
 * @author Youtao Liu
 */
public class ViewsTest extends ActivityInstrumentationTestCase2<TrackListActivity> {

  private Instrumentation instrumentation;
  private TrackListActivity activityMyTracks;

  public ViewsTest() {
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
   * Switches view from {@link MyTracksMapFragment} to @ ChartFragment} , then
   * changes to @ StatsFragment} . Finally back to {@link MyTracksMapFragment}.
   * And check some menus in these views. In MapFragment, the menu should
   * contain satellite/map mode. In ChartFragment and StatsFragment, the menu
   * should not contain satellite/map mode.
   */
  public void testSwitchViewsAndMenusOfView() {
    EndToEndTestUtils.createTrackIfEmpty(3, true);

    EndToEndTestUtils.SOLO.scrollUp();
    EndToEndTestUtils.SOLO.clickOnText(EndToEndTestUtils.trackName, 1, true);

    EndToEndTestUtils.SOLO.clickOnText(activityMyTracks.getString(R.string.track_detail_chart_tab));
    EndToEndTestUtils.rotateAllActivities();

    assertTrue(EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_settings),
        false));
    assertFalse(EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_map_layer),
        false));

    EndToEndTestUtils.SOLO.clickOnText(activityMyTracks.getString(R.string.track_detail_stats_tab));
    assertTrue(EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_settings),
        false));
    assertFalse(EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_map_layer),
        false));

    EndToEndTestUtils.SOLO.clickOnText(activityMyTracks.getString(R.string.track_detail_map_tab));
    EndToEndTestUtils.rotateAllActivities();
    assertTrue(EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_settings),
        false));
    assertTrue(EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_map_layer),
        false));
  }

  /**
   * Tests the switch between satellite mode and map mode.
   */
  public void testSatelliteAndMapView() {
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.createTrackIfEmpty(1, false);
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_map_layer), true);
    EndToEndTestUtils.SOLO.clickOnText(activityMyTracks.getString(R.string.menu_terrain));
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_map_layer), true);
    EndToEndTestUtils.SOLO.clickOnText(activityMyTracks.getString(R.string.menu_satellite));
    instrumentation.waitForIdleSync();
    EndToEndTestUtils.findMenuItem(activityMyTracks.getString(R.string.menu_map_layer), true);
    // But in some languages only has one match(Such as French).
    EndToEndTestUtils.SOLO.clickOnText(activityMyTracks.getString(R.string.menu_map));
    // The first match maybe a noise(The title of the radio dialog) for some
    // language(Such as English), we should click the second one.
    TextView text = EndToEndTestUtils.findTextViewByIndex(
        activityMyTracks.getString(R.string.menu_map), 2);
    if (text != null) {
      EndToEndTestUtils.SOLO.clickOnView(text);
    }
  }

  @Override
  protected void tearDown() throws Exception {
    EndToEndTestUtils.SOLO.finishOpenedActivities();
    super.tearDown();
  }

}