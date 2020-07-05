import 'package:bluebubble_messages/managers/method_channel_interface.dart';
import 'package:bluebubble_messages/managers/settings_manager.dart';
import 'package:bluebubble_messages/repository/models/attachment.dart';
import 'package:bluebubble_messages/repository/models/chat.dart';
import 'package:flutter/material.dart';

class NotificationManager {
  factory NotificationManager() {
    return _manager;
  }

  String _currentChatGuid = "";
  String get chat => _currentChatGuid;

  static final NotificationManager _manager = NotificationManager._internal();

  NotificationManager._internal();

  List<String> processedNotifications = <String>[];

  void switchChat(Chat chat) {
    _currentChatGuid = chat.guid;
    MethodChannelInterface()
        .invokeMethod("clear-chat-notifs", {"chatGuid": _currentChatGuid});
  }

  void leaveChat() {
    _currentChatGuid = "";
  }

  void createNotificationChannel() {
    MethodChannelInterface().invokeMethod("create-notif-channel", {
      "channel_name": "New Messages",
      "channel_description": "For new messages retreived",
      "CHANNEL_ID": "com.bluebubbles.new_messages"
    });
  }

  void createNewNotification(String contentTitle, String contentText,
      String group, int id, int summaryId) {
    MethodChannelInterface().platform.invokeMethod("new-message-notification", {
      "CHANNEL_ID": "com.bluebubbles.new_messages",
      "contentTitle": contentTitle,
      "contentText": contentText,
      "group": group,
      "notificationId": id,
      "summaryId": summaryId,
    });
  }

  void updateProgressNotification(int id, double progress) {
    debugPrint(
        "updating progress notif with progress ${(progress * 100).floor()}");
    MethodChannelInterface()
        .platform
        .invokeMethod("update-attachment-download-notification", {
      "CHANNEL_ID": "com.bluebubbles.new_messages",
      "notificationId": id,
      "progress": (progress * 100).floor(),
    });
  }

  void createProgressNotification(String contentTitle, String contentText,
      String group, int id, int summaryId, double progress) {
    MethodChannelInterface()
        .platform
        .invokeMethod("create-attachment-download-notification", {
      "CHANNEL_ID": "com.bluebubbles.new_messages",
      "contentTitle": contentTitle,
      "contentText": contentText,
      "group": group,
      "notificationId": id,
      "summaryId": summaryId,
      "progress": (progress * 100).floor(),
    });
  }

  void finishProgressWithAttachment(
      String contentText, int id, Attachment attachment) {
    String path;
    if (attachment.mimeType != null && attachment.mimeType.startsWith("image/"))
      path = "/attachments/${attachment.guid}/${attachment.transferName}";

    MethodChannelInterface()
        .platform
        .invokeMethod("finish-attachment-download", {
      "CHANNEL_ID": "com.bluebubbles.new_messages",
      "contentText": contentText,
      "notificationId": id,
      "path": path,
    });
  }
}
