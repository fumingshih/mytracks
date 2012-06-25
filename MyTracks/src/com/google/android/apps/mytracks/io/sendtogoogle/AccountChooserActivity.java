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
package com.google.android.apps.mytracks.io.sendtogoogle;

import com.google.android.apps.mytracks.Constants;
import com.google.android.apps.mytracks.io.docs.SendDocsActivity;
import com.google.android.apps.mytracks.io.fusiontables.SendFusionTablesActivity;
import com.google.android.apps.mytracks.io.fusiontables.SendFusionTablesUtils;
import com.google.android.apps.mytracks.io.gdata.docs.DocumentsClient;
import com.google.android.apps.mytracks.io.gdata.docs.SpreadsheetsClient;
import com.google.android.apps.mytracks.io.gdata.maps.MapsConstants;
import com.google.android.apps.mytracks.io.maps.ChooseMapActivity;
import com.google.android.apps.mytracks.io.maps.SendMapsActivity;
import com.google.android.apps.mytracks.util.IntentUtils;
import com.google.android.apps.mytracks.util.PreferencesUtils;
import com.google.android.maps.mytracks.R;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

/**
 * A chooser to select an account.
 *
 * @author Jimmy Shih
 */
public class AccountChooserActivity extends Activity {

  private static final String TAG = AccountChooserActivity.class.getSimpleName();
  
  private static final int DIALOG_NO_ACCOUNT_ID = 0;
  private static final int DIALOG_CHOOSER_ID = 1;

  /**
   * A callback after getting the permission to access a Google service.
   *
   * @author Jimmy Shih
   */
  private interface PermissionCallback {
   
    /**
     * To be invoked when the permission is granted.
     */
    public void onSuccess();

    /**
     * To be invoked when the permission is not granted.
     */
    public void onFailure();
  }

  private SendRequest sendRequest;
  private Account[] accounts;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    sendRequest = getIntent().getParcelableExtra(SendRequest.SEND_REQUEST_KEY);
    accounts = AccountManager.get(this).getAccountsByType(Constants.ACCOUNT_TYPE);

    if (accounts.length == 0) {
      showDialog(DIALOG_NO_ACCOUNT_ID);
      return;
    }
    
    if (accounts.length == 1) {
      sendRequest.setAccount(accounts[0]);
      PreferencesUtils.setString(this, R.string.google_account_key, accounts[0].name);
      getPermission(MapsConstants.SERVICE_NAME, sendRequest.isSendMaps(), mapsCallback);
      return;
    }

    String googleAccount = PreferencesUtils.getString(this, R.string.google_account_key,
        PreferencesUtils.GOOGLE_ACCOUNT_DEFAULT);
    for (int i = 0; i < accounts.length; i++) {
      if (accounts[i].name.equals(googleAccount)) {
        sendRequest.setAccount(accounts[i]);
        getPermission(MapsConstants.SERVICE_NAME, sendRequest.isSendMaps(), mapsCallback);
        return;
      }
    }
    showDialog(DIALOG_CHOOSER_ID);
  }

  @Override
  protected Dialog onCreateDialog(int id) {
    switch (id) {
      case DIALOG_NO_ACCOUNT_ID:
        return new AlertDialog.Builder(this)
            .setCancelable(true)
            .setMessage(R.string.send_google_no_account_message)
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
              @Override
              public void onCancel(DialogInterface dialog) {
                finish();
              }
            })
            .setPositiveButton(R.string.generic_ok, new DialogInterface.OnClickListener() {
              public void onClick(DialogInterface dialog, int which) {
                finish();
              }
            })
            .setTitle(R.string.send_google_no_account_title)
            .create();
      case DIALOG_CHOOSER_ID:
        return createChooserDialog();
      default:
        return null;
    }
  }

  /**
   * Creates a chooser dialog.
   */
  private Dialog createChooserDialog() {
    String[] choices = new String[accounts.length];
    for (int i = 0; i < accounts.length; i++) {
      choices[i] = accounts[i].name;
    }
    return new AlertDialog.Builder(this)
        .setCancelable(true)
        .setNegativeButton(R.string.generic_cancel, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            finish();
          }
        })
        .setOnCancelListener(new DialogInterface.OnCancelListener() {
          @Override
          public void onCancel(DialogInterface dialog) {
            finish();
          }
        })
        .setPositiveButton(R.string.generic_ok, new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            int position = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
            Account account = accounts[position];
            PreferencesUtils.setString(
                AccountChooserActivity.this, R.string.google_account_key, account.name);
            sendRequest.setAccount(account);
            getPermission(MapsConstants.SERVICE_NAME, sendRequest.isSendMaps(), mapsCallback);
          }
        })
        .setSingleChoiceItems(choices, 0, null)
        .setTitle(R.string.send_google_choose_account_title)
        .create();
  }
  
  private PermissionCallback spreadsheetsCallback = new PermissionCallback() {
    @Override
    public void onSuccess() {
      startNextActivity();
    }  
    @Override
    public void onFailure() {
      handleNoAccountPermission();
    }
  };
  
  private PermissionCallback docsCallback = new PermissionCallback() {
    @Override
    public void onSuccess() {
      getPermission(SpreadsheetsClient.SERVICE, sendRequest.isSendDocs(), spreadsheetsCallback);
    }  
    @Override
    public void onFailure() {
      handleNoAccountPermission();
    }
  };
  
  private PermissionCallback fusionTablesCallback = new PermissionCallback() {
    @Override
    public void onSuccess() {
      getPermission(DocumentsClient.SERVICE, sendRequest.isSendDocs(), docsCallback);
    }  
    @Override
    public void onFailure() {
      handleNoAccountPermission();
    }
  };
  
  private PermissionCallback mapsCallback = new PermissionCallback() {
    @Override
    public void onSuccess() {
      getPermission(
          SendFusionTablesUtils.SERVICE, sendRequest.isSendFusionTables(), fusionTablesCallback);
    }
    @Override
    public void onFailure() {
      handleNoAccountPermission();
    }
  };
  
  /**
   * Gets the user permission to access a service.
   * 
   * @param authTokenType the auth token type of the service
   * @param needPermission true if need the permission
   * @param callback callback after getting the permission
   */
  private void getPermission(
      String authTokenType, boolean needPermission, final PermissionCallback callback) {
    if (needPermission) {
      AccountManager.get(this).getAuthToken(sendRequest.getAccount(), authTokenType, null, this,
          new AccountManagerCallback<Bundle>() {
            @Override
            public void run(AccountManagerFuture<Bundle> future) {
              try {
                if (future.getResult().getString(AccountManager.KEY_AUTHTOKEN) != null) {
                  callback.onSuccess();
                } else {
                  Log.d(TAG, "auth token is null");
                  callback.onFailure();
                }
              } catch (OperationCanceledException e) {
                Log.d(TAG, "Unable to get auth token", e);
                callback.onFailure();
              } catch (AuthenticatorException e) {
                Log.d(TAG, "Unable to get auth token", e);
                callback.onFailure();
              } catch (IOException e) {
                Log.d(TAG, "Unable to get auth token", e);
                callback.onFailure();
              }
            }
          }, null);
    } else {
      callback.onSuccess();
    }
  }

  /**
   * Starts the next activity. If
   * <p>
   * sendMaps and newMap -> {@link SendMapsActivity}
   * <p>
   * sendMaps and !newMap -> {@link ChooseMapActivity}
   * <p>
   * !sendMaps && sendFusionTables -> {@link SendFusionTablesActivity}
   * <p>
   * !sendMaps && !sendFusionTables && sendDocs -> {@link SendDocsActivity}
   * <p>
   * !sendMaps && !sendFusionTables && !sendDocs -> {@link UploadResultActivity}
   *
   */
  private void startNextActivity() {
    Class<?> next;
    if (sendRequest.isSendMaps()) {
      next = sendRequest.isNewMap() ? SendMapsActivity.class : ChooseMapActivity.class;
    } else if (sendRequest.isSendFusionTables()) {
      next = SendFusionTablesActivity.class;
    } else if (sendRequest.isSendDocs()) {
      next = SendDocsActivity.class;
    } else {
      next = UploadResultActivity.class;
    }
    Intent intent = IntentUtils.newIntent(this, next)
        .putExtra(SendRequest.SEND_REQUEST_KEY, sendRequest);
    startActivity(intent);
    finish();
  }
  
  /**
   * Handles when not able to get account permission.
   */
  private void handleNoAccountPermission() {
    Toast.makeText(this, R.string.send_google_no_account_permission, Toast.LENGTH_LONG).show();
    finish();
  }
}