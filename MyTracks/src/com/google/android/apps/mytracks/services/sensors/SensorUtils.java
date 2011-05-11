/*
 * Copyright 2010 Google Inc.
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
package com.google.android.apps.mytracks.services.sensors;

import android.content.Context;

import com.google.android.apps.mytracks.content.Sensor;
import com.google.android.maps.mytracks.R;

/**
 * A collection of methods for message parsers.
 *
 * @author Sandor Dornbush
 * @author Nico Laum
 */
public class SensorUtils {

  private SensorUtils() {
  }
  
  /**
   * Extract one unsigned short from a big endian byte array.
   * 
   * @param buffer the buffer to extract the short from
   * @param index the first byte to be interpreted as part of the short
   * @return The unsigned short at the given index in the buffer
   */
  public static int unsignedShortToInt(byte[] buffer, int index) {
    int r = (buffer[index] & 0xFF) << 8;
    r |= buffer[index + 1] & 0xFF;
    return r;
  }

  /**
   * Extract one unsigned short from a little endian byte array.
   * 
   * @param buffer the buffer to extract the short from
   * @param index the first byte to be interpreted as part of the short
   * @return The unsigned short at the given index in the buffer
   */
  public static int unsignedShortToIntLittleEndian(byte[] buffer, int index) {
    int r = buffer[index] & 0xFF;
    r |= (buffer[index + 1] & 0xFF) << 8;
    return r;
  }

  /**
   * Returns CRC8 (polynomial 0x8C) from byte array buffer[start] until
   * (including) buffer[end]
   * 
   * @param buffer the byte array of data (payload)
   * @param start the position in the byte array where the payload begins
   * @param end the position in the byte array where the payload ends
   * @return CRC8 value
   */
  public static byte getCrc8(byte[] buffer, int start, int length) {
    byte crc = 0x0;

    for (int i = start; i < (start + length); i++) {
      crc = crc8PushByte(crc, buffer[i]);
    }
    return crc;
  }

  /**
   * Updates a CRC8 value by using the next byte passed to this method
   * 
   * @param crc int of crc value
   * @param add the next byte to add to the CRC8 calculation
   */
  private static byte crc8PushByte(byte crc, byte add) {
    crc = (byte) (crc ^ add);
    
    for (int i = 0; i < 8; i++) {
      if ((crc & 0x1) != 0x0) {
		// we have to do the OxFF mask because the right shift is
		// supposed to shift in a 0
    	// as crc gets implicitly casted to a
		// signed int by >> it may be filled with 1s
        crc = (byte) (((crc & 0xFF) >> 1) ^ 0x8C);
      } else {
        crc = (byte) ((crc & 0xFF) >> 1);
      }
    }
    return crc;
  }
	
  public static String getStateAsString(Sensor.SensorState state, Context c) {
    switch (state) {
      case NONE:
        return c.getString(R.string.none);
      case CONNECTING:
        return c.getString(R.string.connecting);
      case CONNECTED:
        return c.getString(R.string.connected);
      case DISCONNECTED:
        return c.getString(R.string.disconnected);
      case SENDING:
        return c.getString(R.string.sending);
      default:
        return "";
    }
  }
}
