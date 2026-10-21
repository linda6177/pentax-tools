package com.ricohimaging.imagesync.modern;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

/**
 * System-notification progress for background photo downloads. Downloads keep
 * running while the user switches tabs; the notification reports progress and
 * the final result.
 */
public final class NotificationHelper {

    private static final String CHANNEL_ID = "image_sync_downloads";
    private static final int CHANNEL_NAME = 1;

    private NotificationHelper() {}

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager manager = manager(context);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "照片下载",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("相机照片下载进度");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        } catch (Throwable ignored) {
            // Notifications are best effort.
        }
    }

    public static void progress(Context context, int id, int done, int total, String title) {
        try {
            NotificationManager manager = manager(context);
            if (manager == null) return;
            ensureChannel(context);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentTitle(title)
                    .setContentText(done + " / " + total)
                    .setOnlyAlertOnce(true)
                    .setOngoing(true)
                    .setProgress(Math.max(1, total), Math.min(done, total), total <= 0);
            manager.notify(id, builder.build());
        } catch (Throwable ignored) {
            // Missing notification permission must not break downloading.
        }
    }

    public static void finish(Context context, int id, String title, String text) {
        try {
            NotificationManager manager = manager(context);
            if (manager == null) return;
            ensureChannel(context);
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true);
            manager.notify(id, builder.build());
        } catch (Throwable ignored) {
            // Ignore.
        }
    }

    public static void cancel(Context context, int id) {
        try {
            NotificationManager manager = manager(context);
            if (manager != null) manager.cancel(id);
        } catch (Throwable ignored) {
            // Ignore.
        }
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
