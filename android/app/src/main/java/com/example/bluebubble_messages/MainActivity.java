package com.example.bluebubble_messages;

import androidx.annotation.NonNull;

import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugins.GeneratedPluginRegistrant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.InstanceIdResult;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.judemanutd.autostarter.AutoStartPermissionHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


public class MainActivity extends FlutterActivity {
    private static final String CHANNEL = "samples.flutter.dev/fcm";
    private static final String TAG = "MainActivity";
    FirebaseApp app;
    public FlutterEngine engine;
    public Long callbackHandle;
    private DatabaseReference db;
    private FusedLocationProviderClient fusedLocationClient;


    private ValueEventListener dbListener = new ValueEventListener() {
        @Override
        public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
            Log.d("firebase", "data changed");
            String serverURL = dataSnapshot.child("config").child("serverUrl").getValue().toString();
            Log.d("firebase", "new server: " + serverURL);
            if (engine != null) {
                new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL).invokeMethod("new-server", "[" + serverURL + "]");
            }
        }

        @Override
        public void onCancelled(@NonNull DatabaseError databaseError) {

        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState, @Nullable PersistableBundle persistentState) {
        super.onCreate(savedInstanceState, persistentState);
    }



    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void configureFlutterEngine(@NonNull FlutterEngine flutterEngine) {
        GeneratedPluginRegistrant.registerWith(flutterEngine);
        engine = flutterEngine;

        new MethodChannel(flutterEngine.getDartExecutor().getBinaryMessenger(), CHANNEL)
                .setMethodCallHandler(
                        (call, result) -> {
                            if (call.method.equals("auth")) {
                                if (app == null) {
                                    app = FirebaseApp.initializeApp(getContext(), new FirebaseOptions.Builder()
                                            .setProjectId(call.argument("project_id"))
                                            .setStorageBucket(call.argument("storage_bucket"))
                                            .setApiKey(call.argument("api_key"))
                                            .setDatabaseUrl(call.argument("firebase_url"))
                                            .setGcmSenderId(call.argument("client_id"))
                                            .setApplicationId(call.argument("application_id"))
                                            .build());
                                }
                                FirebaseInstanceId.getInstance(app).getInstanceId()
                                        .addOnCompleteListener(new OnCompleteListener<InstanceIdResult>() {
                                            @Override
                                            public void onComplete(@NonNull Task<InstanceIdResult> task) {
                                                if (!task.isSuccessful()) {
                                                    Log.d("FCM", "getInstanceId failed", task.getException());
                                                    result.error("Failed to authenticate", "getInstanceId failed", task.getException());
                                                } else {

                                                    String token = task.getResult().getToken();
                                                    Log.d("FCM", "token: " + token);
                                                    result.success(token);
                                                }
                                            }
                                        });
                                db = FirebaseDatabase.getInstance(app).getReference();
                                try {
                                    db.removeEventListener(dbListener);
                                } catch (Exception e) {

                                }
                                db.addValueEventListener(dbListener);
                            } else if (call.method.equals("create-notif-channel")) {
                                createNotificationChannel(call.argument("channel_name"), call.argument("channel_description"), call.argument("CHANNEL_ID"));
                                result.success("");
                            } else if (call.method.equals("new-message-notification")) {
                                //occurs when clicking on the notification
                                PendingIntent openIntent = PendingIntent.getActivity(MainActivity.this, call.argument("notificationId"), new Intent(this, MainActivity.class).putExtra("id", (int) call.argument("notificationId")).putExtra("chatGUID", (String) call.argument("group")).setType("NotificationOpen"), Intent.FILL_IN_ACTION);

                                //for the dismiss button
                                PendingIntent dismissIntent = PendingIntent.getActivity(MainActivity.this, call.argument("notificationId"), new Intent(this, MainActivity.class).putExtra("id", (int) call.argument("notificationId")).setType("markAsRead"), PendingIntent.FLAG_UPDATE_CURRENT);
                                NotificationCompat.Action dismissAction = new NotificationCompat.Action.Builder(0, "Mark As Read", dismissIntent).build();

                                //for the quick reply
                                PendingIntent replyIntent = PendingIntent.getBroadcast(this, call.argument("notificationId"), new Intent(this, ReplyReceiver.class).putExtra("id", (int) call.argument("notificationId")).setType("reply"), PendingIntent.FLAG_UPDATE_CURRENT);
                                androidx.core.app.RemoteInput replyInput = new androidx.core.app.RemoteInput.Builder("key_text_reply").setLabel("Reply").build();
                                NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(0, "Reply", replyIntent).addRemoteInput(replyInput).build();

                                //actual notification
                                NotificationCompat.Builder builder = new NotificationCompat.Builder(this, call.argument("CHANNEL_ID"))
                                        .setSmallIcon(R.mipmap.ic_launcher)
                                        .setContentTitle(call.argument("contentTitle"))
                                        .setContentText(call.argument("contentText"))
                                        .setAutoCancel(true)
                                        .setContentIntent(openIntent)
                                        .addAction(dismissAction)
                                        .addAction(replyAction)
                                        .setGroup(call.argument("group"));
//                                        .setGroup("messageGroup");

                                NotificationCompat.Builder summaryBuilder = new NotificationCompat.Builder(this, call.argument("CHANNEL_ID"))
                                        .setSmallIcon(R.mipmap.ic_launcher)
                                        .setContentTitle("New messages")
                                        .setGroup(call.argument("group"))
                                        .setAutoCancel(true)
                                        .setContentIntent(openIntent)
//                                        .setGroup("messageGroup")
                                        .setGroupSummary(true);

                                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getContext());
//                                NotificationManager manager = (NotificationManager) getApplicationContext().getSystemService(getApplicationContext().NOTIFICATION_SERVICE);
//                                StatusBarNotification[] activeNotifications = manager.getActiveNotifications();

                                notificationManager.notify(call.argument("notificationId"), builder.build());
                                notificationManager.notify(call.argument("summaryId"), summaryBuilder.build());
                                result.success("");
                            } else if (call.method.equals("CreateContact")) {

//                                Intent intent = new Intent(ContactsContract.Intents.Insert.ACTION);
//                                intent.setType(ContactsContract.RawContacts.CONTENT_TYPE);
//
//                                String email = call.argument("email");
//                                String phone = call.argument("phone");
//                                String displayName = call.argument("displayName");
//                                // Inserts an email address
//                                intent.putExtra(ContactsContract.Intents.Insert.EMAIL, email)
//                                        .putExtra(ContactsContract.Intents.Insert.PHONE, phone)
//                                        .putExtra(ContactsContract.Intents.Insert.NAME, displayName);
//                                startActivity(intent);

                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                Uri data =FileProvider.getUriForFile(getApplicationContext(), "com.example.path_provider", new File((String) call.argument("path")));
                                getApplicationContext().grantUriPermission(getApplicationContext().getPackageName(), data, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                intent.setDataAndType(data, "text/x-vcard");
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                startActivity(intent);

                                result.success("");
                            } else if (call.method.equals("clear-chat-notifs")) {
                                NotificationManager manager = (NotificationManager) getApplicationContext().getSystemService(getApplicationContext().NOTIFICATION_SERVICE);
                                for (StatusBarNotification statusBarNotification : manager.getActiveNotifications()) {
                                    if (statusBarNotification.getGroupKey().contains(call.argument("chatGuid"))) {
                                        NotificationManagerCompat.from(getContext()).cancel(statusBarNotification.getId());
                                    } else {
                                        Log.d("notification clearing", statusBarNotification.getGroupKey());
                                    }
                                }
                                result.success("");
                            } else if (call.method.equals("get-last-location")) {
                                if (fusedLocationClient == null)
                                    fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
                                fusedLocationClient.getLastLocation()
                                        .addOnSuccessListener(this, new OnSuccessListener<Location>() {
                                            @Override
                                            public void onSuccess(Location location) {
                                                // Got last known location. In some rare situations this can be null.
                                                if (location != null) {
                                                    // Logic to handle location object
                                                    Map<String, Double> latlng = new HashMap<String, Double>();
                                                    latlng.put("longitude", location.getLongitude());
                                                    latlng.put("latitude", location.getLatitude());
                                                    Log.d("Location", "Location retreived " + latlng.toString());
                                                    result.success(latlng);
                                                } else {
                                                    Log.d("Location", "unable to retreive location");
                                                    result.success(null);
                                                }
                                            }
                                        });

                            } else {
                                result.notImplemented();
                            }
                        }
                );
    }


    //for notifications
    private void createNotificationChannel(String channel_name, String channel_description, String CHANNEL_ID) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = channel_name;
            String description = channel_description;
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    protected void onNewIntent(Intent intent) {
        // Get intent, action and MIME type
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            if ("text/plain".equals(type)) {
                handleSendText(intent); // Handle text being sent
            } else if (type.startsWith("image/")) {
                handleSendImage(intent); // Handle single image being sent
            } else if (type.startsWith("video/")) {
                handleSendImage(intent);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            if (type.startsWith("image/")) {
                handleSendMultipleImages(intent); // Handle multiple images being sent
            } else if (type.startsWith("video/")) {
                handleSendMultipleImages(intent);
            }
        } else {
            // Handle other intents, such as being started from the home screen
            if (intent == null || intent.getType() == null) return;
            if (intent.getType().equals("NotificationOpen")) {
                Log.d("Notifications", "tapped on notification by id " + intent.getExtras().getInt("id"));
                new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL).invokeMethod("ChatOpen", intent.getExtras().getString("chatGUID"));
            } else if (intent.getType().equals("reply")) {
            } else if (intent.getType().equals("markAsRead")) {
                NotificationManagerCompat notificationManager = NotificationManagerCompat.from(getContext());
                notificationManager.cancel(intent.getExtras().getInt("id"));
            }
        }

    }

    void handleSendText(Intent intent) {
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText != null) {
            new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL).invokeMethod("shareText", sharedText);
            // Update UI to reflect text being shared
        }
    }

    void handleSendImage(Intent intent) {
        ArrayList<String> imagePaths = new ArrayList<String>();
        Uri imageUri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (imageUri != null) {
            imagePaths.add(getRealPathFromURI(imageUri));
            new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL).invokeMethod("shareAttachments", imagePaths);
            // Update UI to reflect image being shared
        }
    }

    public String getRealPathFromURI(Uri contentUri) {
        String[] proj = {MediaStore.Audio.Media.DATA};
        Cursor cursor = managedQuery(contentUri, proj, null, null, null);
        int column_index = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
        cursor.moveToFirst();
        return cursor.getString(column_index);
    }

    void handleSendMultipleImages(Intent intent) {
        ArrayList<Uri> imageUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        ArrayList<String> imagePaths = new ArrayList<String>();
        if (imageUris != null) {
            for (Uri imageUri : imageUris) {
                imagePaths.add(getRealPathFromURI(imageUri));
            }
            new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL).invokeMethod("shareAttachments", imagePaths);
            // Update UI to reflect multiple images being shared
        }
    }


    BackgroundService backgroundService;

    protected ServiceConnection mServerConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            backgroundService = ((BackgroundService.LocalBinder) binder).getService();
            backgroundService.isAlive = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            backgroundService = null;

            // Close the DB connection
            DatabaseHelper helper = DatabaseHelper.getInstance(getContext());
            helper.close();
        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, new IntentFilter("MyData"));
        registerReceiver(replyReceiver, new IntentFilter("Reply"));
        getApplicationContext().bindService(new Intent(getApplicationContext(), BackgroundService.class), mServerConn, Context.BIND_AUTO_CREATE);
        Intent serviceIntent = new Intent(getApplicationContext(), BackgroundService.class);
        startService(serviceIntent);
    }

    @Override
    protected void onDestroy() {
        Log.d("MainActivity", "removed from memory");
//        unregisterReceiver(mMessageReceiver);
        if (backgroundService != null) {
            backgroundService.isAlive = false;
            Log.d("isAlive", "set isAlive to false");
        }
        try {
            getApplicationContext().unbindService(mServerConn);
            unregisterReceiver(mMessageReceiver);
        } catch (Exception e) {
            e.printStackTrace();
            Log.d("isolate", "unable to unbind service");

        }
//        if(backgroundService != null) {
//            backgroundService.unbindService(mServerConn);
//        }
        super.onDestroy();
    }

    private BroadcastReceiver mMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getType() != null && intent.getType().equals("reply")) {
                new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL).invokeMethod("reply", intent.getExtras());
            } else {
                new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), CHANNEL).invokeMethod(intent.getExtras().getString("type"), intent.getExtras().getString("data"));
            }
        }
    };

    private BroadcastReceiver replyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d("Notification", "reply");
        }
    };
}
