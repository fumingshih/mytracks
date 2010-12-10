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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.os.HandlerThread;

/**
 * The Cupcake (API level 3) specific implementation of the
 * {@link ApiPlatformAdapter}.
 * 
 * @author Bartlomiej Niechwiej
 */
public class CupcakePlatformAdapter implements ApiPlatformAdapter {

  @Override
  public void startForeground(Service service,
      NotificationManager notificationManager, int id,
      Notification notification) {
    service.setForeground(true);
    notificationManager.notify(id, notification);
  }

  @Override
  public void stopForeground(Service service,
      NotificationManager notificationManager, int id) {
    service.setForeground(false);
    if (id != -1) {
      notificationManager.cancel(id);
    }
  }

  @Override
  public boolean stopHandlerThread(HandlerThread handlerThread) {
    // Do nothing, as Cupcake doesn't provide quit().
    return false;
  }

  @Override
  public void enableStrictMode() {
    // Not supported
  }
}
