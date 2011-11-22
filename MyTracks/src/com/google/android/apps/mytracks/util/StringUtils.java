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
package com.google.android.apps.mytracks.util;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.content.DescriptionGenerator;
import com.google.android.apps.mytracks.content.Track;
import com.google.android.apps.mytracks.content.Waypoint;
import com.google.android.apps.mytracks.stats.TripStatistics;
import com.google.android.maps.mytracks.R;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.NumberFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.SimpleTimeZone;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Various string manipulation methods.
 *
 * @author Sandor Dornbush
 * @author Rodrigo Damazio
 */
public class StringUtils implements DescriptionGenerator {

  private final Context context;

  /**
   * Formats a number of milliseconds as a string.
   *
   * @param time - A period of time in milliseconds.
   * @return A string of the format M:SS, MM:SS or HH:MM:SS
   */
  public static String formatTime(long time) {
    return formatTimeInternal(time, false);
  }

  /**
   * Formats a number of milliseconds as a string. To be used when we need the
   * hours to be shown even when it is zero, e.g. exporting data to a
   * spreadsheet.
   *
   * @param time - A period of time in milliseconds
   * @return A string of the format HH:MM:SS even if time is less than 1 hour
   */
  public static String formatTimeAlwaysShowingHours(long time) {
    return formatTimeInternal(time, true);
  }

  private static final NumberFormat SINGLE_DECIMAL_PLACE_FORMAT = NumberFormat.getNumberInstance();
  
  static {
    SINGLE_DECIMAL_PLACE_FORMAT.setMaximumFractionDigits(1);
    SINGLE_DECIMAL_PLACE_FORMAT.setMinimumFractionDigits(1);
  }

  /**
   * Formats a double precision number as decimal number with a single decimal
   * place.
   *
   * @param number A double precision number
   * @return A string representation of a decimal number, derived from the input
   *         double, with a single decimal place
   */
  public static final String formatSingleDecimalPlace(double number) {
    return SINGLE_DECIMAL_PLACE_FORMAT.format(number);
  }

  /**
   * Formats the given text as a CDATA element to be used in a XML file. This
   * includes adding the starting and ending CDATA tags. Please notice that this
   * may result in multiple consecutive CDATA tags.
   *
   * @param unescaped the unescaped text to be formatted
   * @return the formatted text, inside one or more CDATA tags
   */
  public static String stringAsCData(String unescaped) {
    // "]]>" needs to be broken into multiple CDATA segments, like:
    // "Foo]]>Bar" becomes "<![CDATA[Foo]]]]><![CDATA[>Bar]]>"
    // (the end of the first CDATA has the "]]", the other has ">")
    String escaped = unescaped.replaceAll("]]>", "]]]]><![CDATA[>");
    return "<![CDATA[" + escaped + "]]>";
  }

  private static final SimpleDateFormat BASE_XML_DATE_FORMAT =
      new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
  static {
    BASE_XML_DATE_FORMAT.setTimeZone(new SimpleTimeZone(0, "UTC"));
  }
  private static final Pattern XML_DATE_EXTRAS_PATTERN =
      Pattern.compile("^(\\.\\d+)?(?:Z|([+-])(\\d{2}):(\\d{2}))?$");

  /**
   * Parses an XML dateTime element as defined by the XML standard.
   *
   * @see <a href="http://www.w3.org/TR/xmlschema-2/#dateTime">dateTime</a>
   */
  public static long parseXmlDateTime(String xmlTime) {
    // Parse the base date (fixed format)
    ParsePosition position = new ParsePosition(0);
    Date date = BASE_XML_DATE_FORMAT.parse(xmlTime, position);
    if (date == null) {
      throw new IllegalArgumentException("Invalid XML dateTime value: '" + xmlTime
          + "' (at position " + position.getErrorIndex() + ")");
    }

    // Parse the extras
    Matcher matcher =
        XML_DATE_EXTRAS_PATTERN.matcher(xmlTime.substring(position.getIndex()));
    if (!matcher.matches()) {
      // This will match even an empty string as all groups are optional,
      // so a non-match means some other garbage was there
      throw new IllegalArgumentException("Invalid XML dateTime value: " + xmlTime);
    }

    long time = date.getTime();

    // Account for fractional seconds
    String fractional = matcher.group(1);
    if (fractional != null) {
      // Regex ensures fractional part is in (0,1(
      float fractionalSeconds = Float.parseFloat(fractional);
      long fractionalMillis = (long) (fractionalSeconds * 1000.0f);
      time += fractionalMillis;
    }

    // Account for timezones
    String sign = matcher.group(2);
    String offsetHoursStr = matcher.group(3);
    String offsetMinsStr = matcher.group(4);
    if (sign != null && offsetHoursStr != null && offsetMinsStr != null) {
      // Regex ensures sign is + or -
      boolean plusSign = sign.equals("+");
      int offsetHours = Integer.parseInt(offsetHoursStr);
      int offsetMins = Integer.parseInt(offsetMinsStr);

      // Regex ensures values are >= 0
      if (offsetHours > 14 || offsetMins > 59) {
        throw new IllegalArgumentException("Bad timezone in " + xmlTime);
      }

      long totalOffsetMillis = (offsetMins + offsetHours * 60L) * 60000L;

      // Make time go back to UTC
      if (plusSign) {
        time -= totalOffsetMillis;
      } else {
        time += totalOffsetMillis;
      }
    }

    return time;
  }

  /**
   * Formats a number of milliseconds as a string.
   *
   * @param time - A period of time in milliseconds
   * @param alwaysShowHours - Whether to display 00 hours if time is less than 1
   *        hour
   * @return A string of the format HH:MM:SS
   */
  private static String formatTimeInternal(long time, boolean alwaysShowHours) {
    int[] parts = getTimeParts(time);
    StringBuilder builder = new StringBuilder();
    if (parts[2] > 0 || alwaysShowHours) {
      builder.append(parts[2]);
      builder.append(':');
      if (parts[1] <= 9) {
        builder.append("0");
      }
    }

    builder.append(parts[1]);
    builder.append(':');
    if (parts[0] <= 9) {
      builder.append("0");
    }
    builder.append(parts[0]);

    return builder.toString();
  }

  /**
   * Gets the time as an array of parts.
   */
  public static int[] getTimeParts(long time) {
    if (time < 0) {
      int[] parts = getTimeParts(time * -1);
      parts[0] *= -1;
      parts[1] *= -1;
      parts[2] *= -1;
      return parts;
    }
    int[] parts = new int[3];

    long seconds = time / 1000;
    parts[0] = (int) (seconds % 60);
    int tmp = (int) (seconds / 60);
    parts[1] = tmp % 60;
    parts[2] = tmp / 60;

    return parts;
  }

  public StringUtils(Context context) {
    this.context = context;
  }

  /**
   * Generates a description for a track (with information about the
   * statistics).
   *
   * @param track the track
   * @return a track description
   */
  public String generateTrackDescription(Track track, Vector<Double> distances,
      Vector<Double> elevations) {
    boolean displaySpeed = true;
    SharedPreferences preferences = context.getSharedPreferences(
        Constants.SETTINGS_NAME, Context.MODE_PRIVATE);
    if (preferences != null) {
      displaySpeed =
          preferences.getBoolean(context.getString(R.string.report_speed_key), true);
    }

    TripStatistics trackStats = track.getStatistics();
    final double distanceInKm = trackStats.getTotalDistance() / 1000;
    final double distanceInMiles = distanceInKm * UnitConversions.KM_TO_MI;
    final long minElevationInMeters = Math.round(trackStats.getMinElevation());
    final long minElevationInFeet =
        Math.round(trackStats.getMinElevation() * UnitConversions.M_TO_FT);
    final long maxElevationInMeters = Math.round(trackStats.getMaxElevation());
    final long maxElevationInFeet =
        Math.round(trackStats.getMaxElevation() * UnitConversions.M_TO_FT);
    final long elevationGainInMeters =
        Math.round(trackStats.getTotalElevationGain());
    final long elevationGainInFeet = Math.round(
        trackStats.getTotalElevationGain() * UnitConversions.M_TO_FT);

    long minGrade = 0;
    long maxGrade = 0;
    double trackMaxGrade = trackStats.getMaxGrade();
    double trackMinGrade = trackStats.getMinGrade();
    if (!Double.isNaN(trackMaxGrade)
        && !Double.isInfinite(trackMaxGrade)) {
      maxGrade = Math.round(trackMaxGrade * 100);
    }
    if (!Double.isNaN(trackMinGrade) && !Double.isInfinite(trackMinGrade)) {
      minGrade = Math.round(trackMinGrade * 100);
    }

    String category = context.getString(R.string.value_unknown);
    String trackCategory = track.getCategory();
    if (trackCategory != null && trackCategory.length() > 0) {
      category = trackCategory;
    }

    String averageSpeed =
        getSpeedString(trackStats.getAverageSpeed(),
            R.string.stat_average_speed,
            R.string.stat_average_pace,
            displaySpeed);

    String averageMovingSpeed =
        getSpeedString(trackStats.getAverageMovingSpeed(),
            R.string.stat_average_moving_speed,
            R.string.stat_average_moving_pace,
            displaySpeed);

    String maxSpeed =
        getSpeedString(trackStats.getMaxSpeed(),
            R.string.stat_max_speed,
            R.string.stat_min_pace,
            displaySpeed);

    return String.format("%s<p>"
        + "%s: %.2f %s (%.1f %s)<br>"
        + "%s: %s<br>"
        + "%s: %s<br>"
        + "%s %s %s"
        + "%s: %d %s (%d %s)<br>"
        + "%s: %d %s (%d %s)<br>"
        + "%s: %d %s (%d %s)<br>"
        + "%s: %d %%<br>"
        + "%s: %d %%<br>"
        + "%s: %tc<br>"
        + "%s: %s<br>"
        + "<img border=\"0\" src=\"%s\"/>",

        // Line 1
        getCreatedByMyTracks(context, true),

        // Line 2
        context.getString(R.string.stat_total_distance),
        distanceInKm, context.getString(R.string.unit_kilometer),
        distanceInMiles, context.getString(R.string.unit_mile),

        // Line 3
        context.getString(R.string.stat_total_time),
        StringUtils.formatTime(trackStats.getTotalTime()),

        // Line 4
        context.getString(R.string.stat_moving_time),
        StringUtils.formatTime(trackStats.getMovingTime()),

        // Line 5
        averageSpeed, averageMovingSpeed, maxSpeed,

        // Line 6
        context.getString(R.string.stat_min_elevation),
        minElevationInMeters, context.getString(R.string.unit_meter),
        minElevationInFeet, context.getString(R.string.unit_feet),

        // Line 7
        context.getString(R.string.stat_max_elevation),
        maxElevationInMeters, context.getString(R.string.unit_meter),
        maxElevationInFeet, context.getString(R.string.unit_feet),

        // Line 8
        context.getString(R.string.stat_elevation_gain),
        elevationGainInMeters, context.getString(R.string.unit_meter),
        elevationGainInFeet, context.getString(R.string.unit_feet),

        // Line 9
        context.getString(R.string.stat_max_grade), maxGrade,

        // Line 10
        context.getString(R.string.stat_min_grade), minGrade,

        // Line 11
        context.getString(R.string.send_google_recorded),
        new Date(trackStats.getStartTime()),

        // Line 12
        context.getString(R.string.track_detail_activity_type_hint), category,

        // Line 13
        ChartURLGenerator.getChartUrl(distances, elevations, track, context));
  }

  /**
   * Returns the 'Created by My Tracks on Android' string.
   * 
   * @param context the context
   * @param addLink true to add a link to the My Tracks web site
   */
  public static String getCreatedByMyTracks(Context context, boolean addLink) {
    String format = context.getString(R.string.send_google_by_my_tracks);
    if (addLink) {
      String url = context.getString(R.string.my_tracks_web_url);
      return String.format(format, "<a href='http://" + url + "'>", "</a>");
    } else {
      return String.format(format, "", "");
    }
  }
  
  private String getSpeedString(double speed, int speedLabel, int paceLabel,
      boolean displaySpeed) {
    double speedInKph = speed * 3.6;
    double speedInMph = speedInKph * UnitConversions.KMH_TO_MPH;
    if (displaySpeed) {
      return String.format("%s: %.2f %s (%.1f %s)<br>",
          context.getString(speedLabel),
          speedInKph, context.getString(R.string.unit_kilometer_per_hour),
          speedInMph, context.getString(R.string.unit_mile_per_hour));
    } else {
      double paceInKm;
      double paceInMi;
      if (speed == 0) {
        paceInKm = 0.0;
        paceInMi = 0.0;
      } else {
        paceInKm = 60.0 / speedInKph;
        paceInMi = 60.0 / speedInMph;
      }
      return String.format("%s: %.2f %s (%.1f %s)<br>",
          context.getString(paceLabel),
          paceInKm, context.getString(R.string.unit_minute_per_kilometer),
          paceInMi, context.getString(R.string.unit_minute_per_mile));
    }
  }

  /**
   * Generates a description for a waypoint (with information about the
   * statistics).
   *
   * @return a track description
   */
  public String generateWaypointDescription(Waypoint waypoint) {
    TripStatistics stats = waypoint.getStatistics();

    final double distanceInKm = stats.getTotalDistance() / 1000;
    final double distanceInMiles = distanceInKm * UnitConversions.KM_TO_MI;
    final double averageSpeedInKmh = stats.getAverageSpeed() * 3.6;
    final double averageSpeedInMph =
        averageSpeedInKmh * UnitConversions.KMH_TO_MPH;
    final double movingSpeedInKmh = stats.getAverageMovingSpeed() * 3.6;
    final double movingSpeedInMph =
        movingSpeedInKmh * UnitConversions.KMH_TO_MPH;
    final double maxSpeedInKmh = stats.getMaxSpeed() * 3.6;
    final double maxSpeedInMph = maxSpeedInKmh * UnitConversions.KMH_TO_MPH;
    final long minElevationInMeters = Math.round(stats.getMinElevation());
    final long minElevationInFeet =
        Math.round(stats.getMinElevation() * UnitConversions.M_TO_FT);
    final long maxElevationInMeters = Math.round(stats.getMaxElevation());
    final long maxElevationInFeet =
        Math.round(stats.getMaxElevation() * UnitConversions.M_TO_FT);
    final long elevationGainInMeters =
        Math.round(stats.getTotalElevationGain());
    final long elevationGainInFeet = Math.round(
        stats.getTotalElevationGain() * UnitConversions.M_TO_FT);
    long theMinGrade = 0;
    long theMaxGrade = 0;
    double maxGrade = stats.getMaxGrade();
    double minGrade = stats.getMinGrade();
    if (!Double.isNaN(maxGrade) &&
        !Double.isInfinite(maxGrade)) {
      theMaxGrade = Math.round(maxGrade * 100);
    }
    if (!Double.isNaN(minGrade) &&
        !Double.isInfinite(minGrade)) {
      theMinGrade = Math.round(minGrade * 100);
    }
    final String percent = "%";

    return String.format(
        "%s: %.2f %s (%.1f %s)\n"
        + "%s: %s\n"
        + "%s: %s\n"
        + "%s: %.2f %s (%.1f %s)\n"
        + "%s: %.2f %s (%.1f %s)\n"
        + "%s: %.2f %s (%.1f %s)\n"
        + "%s: %d %s (%d %s)\n"
        + "%s: %d %s (%d %s)\n"
        + "%s: %d %s (%d %s)\n"
        + "%s: %d %s\n"
        + "%s: %d %s\n",
        context.getString(R.string.stat_total_distance),
            distanceInKm, context.getString(R.string.unit_kilometer),
            distanceInMiles, context.getString(R.string.unit_mile),
        context.getString(R.string.stat_total_time),
            StringUtils.formatTime(stats.getTotalTime()),
        context.getString(R.string.stat_moving_time),
            StringUtils.formatTime(stats.getMovingTime()),
        context.getString(R.string.stat_average_speed),
            averageSpeedInKmh, context.getString(R.string.unit_kilometer_per_hour),
            averageSpeedInMph, context.getString(R.string.unit_mile_per_hour),
        context.getString(R.string.stat_average_moving_speed),
            movingSpeedInKmh, context.getString(R.string.unit_kilometer_per_hour),
            movingSpeedInMph, context.getString(R.string.unit_mile_per_hour),
        context.getString(R.string.stat_max_speed),
            maxSpeedInKmh, context.getString(R.string.unit_kilometer_per_hour),
            maxSpeedInMph, context.getString(R.string.unit_mile_per_hour),
        context.getString(R.string.stat_min_elevation),
            minElevationInMeters, context.getString(R.string.unit_meter),
            minElevationInFeet, context.getString(R.string.unit_feet),
        context.getString(R.string.stat_max_elevation),
            maxElevationInMeters, context.getString(R.string.unit_meter),
            maxElevationInFeet, context.getString(R.string.unit_feet),
        context.getString(R.string.stat_elevation_gain),
            elevationGainInMeters, context.getString(R.string.unit_meter),
            elevationGainInFeet, context.getString(R.string.unit_feet),
        context.getString(R.string.stat_max_grade),
            theMaxGrade, percent,
        context.getString(R.string.stat_min_grade),
            theMinGrade, percent);
  }
}
