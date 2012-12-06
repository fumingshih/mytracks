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

import com.google.android.apps.mytracks.endtoendtest.others.BigTestUtils;

import android.os.Bundle;
import android.test.InstrumentationTestRunner;
import android.util.Log;

/**
 * A test runner can be used to run end-to-end test.
 * 
 * @author Youtao Liu
 */
public class EndToEndTestRunner extends InstrumentationTestRunner {
  
  /**
   * Gets the parameter port which is used to fix GPS signal to emulators.
   */
  @Override
  public void onCreate(Bundle arguments) {
    String portNumber = arguments.getString("port");
    if (portNumber != null) {
      try {
        EndToEndTestUtils.emulatorPort = Integer.parseInt(portNumber);
      } catch (Exception e) {
        Log.e(EndToEndTestUtils.LOG_TAG, "Unable to get port parameter, use default value."
            + EndToEndTestUtils.emulatorPort, e);
      }
    }

    String isStressTest = arguments.getString("stress");
    if (isStressTest != null && isStressTest.equalsIgnoreCase("true")) {
      BigTestUtils.runStressTest = true;
    } else {
      BigTestUtils.runStressTest = false;
    }

    String isSensorTest = arguments.getString("sensor");
    if (isSensorTest != null && isSensorTest.equalsIgnoreCase("true")) {
      BigTestUtils.runSensorTest = true;
    } else {
      BigTestUtils.runSensorTest = false;
    }

    Log.d(EndToEndTestUtils.LOG_TAG, "Use port number when run test on emulator:"
        + EndToEndTestUtils.emulatorPort);
    Log.i(EndToEndTestUtils.LOG_TAG, "Run stress test:" + BigTestUtils.runStressTest);
    Log.i(EndToEndTestUtils.LOG_TAG, "Run sensor test:" + BigTestUtils.runSensorTest);

    super.onCreate(arguments);
  }

}
