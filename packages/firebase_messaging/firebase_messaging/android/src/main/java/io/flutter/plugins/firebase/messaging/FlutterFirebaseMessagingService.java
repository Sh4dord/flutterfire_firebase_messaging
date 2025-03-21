// Copyright 2020 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.firebase.messaging;

import static com.google.firebase.messaging.Constants.TAG;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.google.android.gms.cloudmessaging.CloudMessage;
import com.google.android.gms.cloudmessaging.Rpc;
import com.google.firebase.messaging.Constants;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.MessagingAnalytics;
import com.google.firebase.messaging.RemoteMessage;

import io.flutter.plugins.firebase.messaging.FlutterFirebaseTokenLiveData;

import java.util.ArrayDeque;
import java.util.Queue;

public class FlutterFirebaseMessagingService extends FirebaseMessagingService {

  public static final String THREAD_NETWORK_IO = "Firebase-Messaging-Network-Io";

  static final String ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE";

  /**
   * Action for Firebase Cloud Messaging direct boot message intents.
   *
   * @hide
   */
  public static final String ACTION_DIRECT_BOOT_REMOTE_INTENT =
    "com.google.firebase.messaging.RECEIVE_DIRECT_BOOT";

  static final String ACTION_NEW_TOKEN = "com.google.firebase.messaging.NEW_TOKEN";
  static final String EXTRA_TOKEN = "token";

  private static final int RECENTLY_RECEIVED_MESSAGE_IDS_MAX_SIZE = 10;

  /**
   * Last N message IDs that have been received by this app to prevent duplicate messages.
   *
   * <p>This is small enough that it can be kept static to survive service restarts.
   */
  private static final Queue<String> recentlyReceivedMessageIds =
    new ArrayDeque<>(RECENTLY_RECEIVED_MESSAGE_IDS_MAX_SIZE);

  private Rpc rpc;

  @Override
  public void onNewToken(@NonNull String token) {
    FlutterFirebaseTokenLiveData.getInstance().postToken(token);
  }

  @Override
  public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
    // Added for commenting purposes;
    // We don't handle the message here as we already handle it in the receiver and don't want to duplicate.
  }

  @Override
  public void handleIntent(Intent intent) {
    Log.d(TAG, "Intent received");
    //super.handleIntent(intent);
    String action = intent.getAction();

    // Using if/else here instead of a switch to reduce code size
    if (ACTION_REMOTE_INTENT.equals(action) || ACTION_DIRECT_BOOT_REMOTE_INTENT.equals(action)) {
      handleMessageIntent(intent);
    } else if (ACTION_NEW_TOKEN.equals(action)) {
      onNewToken(intent.getStringExtra(EXTRA_TOKEN));
    } else {
      Log.d(TAG, "Unknown intent action: " + intent.getAction());
    }
  }

  private void handleMessageIntent(Intent intent) {
    String messageId = intent.getStringExtra(Constants.MessagePayloadKeys.MSGID);
    if (!alreadyReceivedMessage(messageId)) {
      passMessageIntentToSdk(intent);
    }
    getRpc(this).messageHandled(new CloudMessage(intent));
  }

  private void passMessageIntentToSdk(Intent intent) {
    String messageType = intent.getStringExtra(Constants.MessagePayloadKeys.MESSAGE_TYPE);
    if (messageType == null) {
      messageType = Constants.MessageTypes.MESSAGE;
    }
    switch (messageType) {
      case Constants.MessageTypes.MESSAGE:
        MessagingAnalytics.logNotificationReceived(intent);
        //dispatchMessage(intent);
        break;
      case Constants.MessageTypes.DELETED:
        onDeletedMessages();
        break;
      case Constants.MessageTypes.SEND_EVENT:
        onMessageSent(intent.getStringExtra(Constants.MessagePayloadKeys.MSGID));
        break;
      case Constants.MessageTypes.SEND_ERROR:
        onSendError(
          getMessageId(intent),
          new Exception(intent.getStringExtra(Constants.IPC_BUNDLE_KEY_SEND_ERROR)));
        break;
      default:
        Log.w(TAG, "Received message with unknown type: " + messageType);
        break;
    }
  }

  private boolean alreadyReceivedMessage(String messageId) {
    if (TextUtils.isEmpty(messageId)) {
      return false;
    }
    if (recentlyReceivedMessageIds.contains(messageId)) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        Log.d(TAG, "Received duplicate message: " + messageId);
      }
      return true;
    }
    // Add this message ID to the queue
    if (recentlyReceivedMessageIds.size() >= RECENTLY_RECEIVED_MESSAGE_IDS_MAX_SIZE) {
      recentlyReceivedMessageIds.remove();
    }
    recentlyReceivedMessageIds.add(messageId);
    return false;
  }

  private String getMessageId(Intent intent) {
    String messageId = intent.getStringExtra(Constants.MessagePayloadKeys.MSGID);
    if (messageId == null) {
      messageId = intent.getStringExtra(Constants.MessagePayloadKeys.MSGID_SERVER);
    }
    return messageId;
  }

  private Rpc getRpc(Context context) {
    if (rpc == null) {
      rpc = new Rpc(context.getApplicationContext());
    }
    return rpc;
  }

  @VisibleForTesting
  static void resetForTesting() {
    recentlyReceivedMessageIds.clear();
  }

  @VisibleForTesting
  void setRpcForTesting(Rpc rpc) {
    this.rpc = rpc;
  }
}
