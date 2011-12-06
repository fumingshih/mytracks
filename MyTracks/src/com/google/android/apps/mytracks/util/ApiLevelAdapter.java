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
package com.google.android.apps.mytracks.util;

import com.google.android.apps.mytracks.io.backup.BackupPreferencesListener;
import com.google.android.apps.mytracks.services.tasks.PeriodicTask;
import com.google.api.client.http.HttpTransport;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * A set of methods that may be implemented differently depending on the Android API level. 
 *  
 * @author Bartlomiej Niechwiej
 */
public interface ApiLevelAdapter {
  
  /**
   * Puts the specified service into foreground.
   * 
   * Due to changes in API level 5.
   * 
   * @param service the service to be put in foreground.
   * @param notificationManager the notification manager used to post the given
   *        notification.
   * @param id the ID of the notification, unique within the application.
   * @param notification the notification to post.
   */
  void startForeground(Service service, NotificationManager notificationManager,
      int id, Notification notification);
  
  /**
   * Puts the given service into background.
   * 
   * Due to changes in API level 5.
   * 
   * @param service the service to put into background.
   * @param notificationManager the notification manager to user when removing
   *        notifications. 
   * @param id the ID of the notification to be remove, or -1 if the
   *        notification shouldn't be removed.
   */
  void stopForeground(Service service, NotificationManager notificationManager,
      int id);

  /**
   * Gets a status announcer task.
   * 
   * Due to changes in API level 8.
   */
  PeriodicTask getStatusAnnouncerTask(Context context);
  
  /**
   * Gets a {@link BackupPreferencesListener}.
   * 
   * Due to changes in API level 8.
   */
  BackupPreferencesListener getBackupPreferencesListener(Context context);
  
  /**
   * Applies all changes done to the given preferences editor.
   * Changes may or may not be applied immediately.
   * 
   * Due to changes in API level 9.
   */
  void applyPreferenceChanges(SharedPreferences.Editor editor);
  
  /**
   * Enables strict mode where supported, only if this is a development build.
   * 
   * Due to changes in API level 9.
   */
  void enableStrictMode();
  
  /**
   * Copies elements from the input byte array into a new byte array, from
   * indexes start (inclusive) to end (exclusive). The end index must be less
   * than or equal to input.length.
   *
   * Due to changes in API level 9.
   * 
   * @param input the input byte array
   * @param start the start index
   * @param end the end index
   * @return a new array containing elements from the input byte array
   */
  byte[] copyByteArray(byte[] input, int start, int end);
  
  
  /**
   * Gets a {@link HttpTransport}. 
   * 
   * Due to changes in API level 9.
   */
  HttpTransport getHttpTransport();  
}
