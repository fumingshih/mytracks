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

package com.google.android.apps.mytracks.util;

import com.google.android.maps.mytracks.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;

/**
 * Utilities for creating dialogs.
 *
 * @author Jimmy Shih
 */
public class DialogUtils {

  private DialogUtils() {}

  /**
   * Creates a confirmation dialog.
   *
   * @param context the context
   * @param messageId the confirmation message id
   * @param onClickListener the listener to invoke when the user clicks OK
   */
  public static Dialog createConfirmationDialog(
      Context context, int messageId, DialogInterface.OnClickListener onClickListener) {
    return new AlertDialog.Builder(context)
        .setCancelable(true)
        .setIcon(android.R.drawable.ic_dialog_alert)
        .setMessage(context.getString(messageId))
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, onClickListener)
        .setTitle(R.string.generic_confirm_title)
        .create();
  }

  /**
   * Creates a spinner progress dialog.
   *
   * @param context the context
   * @param messageId the progress message id
   * @param onCancelListener the cancel listener
   */
  public static ProgressDialog createSpinnerProgressDialog(
      Context context, int messageId, DialogInterface.OnCancelListener onCancelListener) {
    return createProgressDialog(true, context, messageId, onCancelListener);
  }

  /**
   * Creates a horizontal progress dialog.
   *
   * @param context the context
   * @param messageId the progress message id
   * @param onCancelListener the cancel listener
   * @param formatArgs the format arguments for the messageId
   */
  public static ProgressDialog createHorizontalProgressDialog(Context context, int messageId,
      DialogInterface.OnCancelListener onCancelListener, Object... formatArgs) {
    return createProgressDialog(false, context, messageId, onCancelListener, formatArgs);
  }

  /**
   * Creates a progress dialog.
   *
   * @param spinner true to use the spinner style
   * @param context the context
   * @param messageId the progress message id
   * @param onCancelListener the cancel listener
   * @param formatArgs the format arguments for the message id
   */
  private static ProgressDialog createProgressDialog(boolean spinner, Context context,
      int messageId, DialogInterface.OnCancelListener onCancelListener, Object... formatArgs) {
    ProgressDialog progressDialog = new ProgressDialog(context);
    progressDialog.setCancelable(true);
    progressDialog.setIcon(android.R.drawable.ic_dialog_info);
    progressDialog.setIndeterminate(true);
    progressDialog.setMessage(context.getString(messageId, formatArgs));
    progressDialog.setOnCancelListener(onCancelListener);
    progressDialog.setProgressStyle(spinner ? ProgressDialog.STYLE_SPINNER
        : ProgressDialog.STYLE_HORIZONTAL);
    progressDialog.setTitle(R.string.generic_progress_title);
    return progressDialog;
  }
}
