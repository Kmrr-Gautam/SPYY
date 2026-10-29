package com.example.sspy.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.sspy.R;
import com.example.sspy.services.RecordingService;

public final class RecordingNotificationManager {
    public static final int NOTIFICATION_ID = 4107;
    private static final String CHANNEL_ID = "recording_status";
    private static final String TAG = "SSPY.Notification";

    private final Context context;
    private final NotificationManagerCompat notificationManager;

    public RecordingNotificationManager(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = NotificationManagerCompat.from(this.context);
        createChannel();
    }

    public Notification buildStartingNotification() {
        return buildNotification(
                context.getString(R.string.notification_title_starting),
                context.getString(R.string.notification_body_starting),
                true);
    }

    public Notification buildRecordingNotification() {
        return buildNotification(
                context.getString(R.string.notification_title_recording),
                context.getString(R.string.notification_body_recording),
                true);
    }

    public boolean updateRecording() {
        if (!notificationManager.areNotificationsEnabled()) {
            Log.e(TAG, "Notification permission is unavailable while recording");
            return false;
        }
        try {
            notificationManager.notify(NOTIFICATION_ID, buildRecordingNotification());
            return true;
        } catch (SecurityException exception) {
            Log.e(TAG, "Could not update the recording notification", exception);
            return false;
        }
    }

    public void cancel() {
        notificationManager.cancel(NOTIFICATION_ID);
    }

    private Notification buildNotification(String title, String body, boolean showStop) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (showStop) {
            Intent stopIntent = new Intent(context, RecordingService.class)
                    .setAction(RecordingService.ACTION_STOP);
            PendingIntent stopPendingIntent = PendingIntent.getForegroundService(
                    context,
                    1,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(
                    R.drawable.ic_notification,
                    context.getString(R.string.notification_action_stop),
                    stopPendingIntent);
        }
        return builder.build();
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }
}
