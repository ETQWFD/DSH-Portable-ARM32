// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 cyf112233
package io.github.cyf112233.portable;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import io.github.cyf112233.portable.core.ProotLauncher;

/**
 * Keeps the guest server alive while the app is not in front.
 *
 * A foreground service with an ongoing notification is the only mechanism
 * Android reliably honours for this: the process is exempt from the usual
 * background limits, the notification makes the trade-off visible to the user,
 * and stopping it is a single tap. The service owns no server state itself, it
 * only promotes the process that already hosts the PRoot child.
 */
public class KeepAliveService extends Service {

    private static final String TAG = "DshKeepAlive";
    private static final String CHANNEL_ID = "dsh-portable-server";
    private static final int NOTIFICATION_ID = 0x4453;

    public static final String ACTION_START = "io.github.cyf112233.portable.KEEPALIVE_START";
    public static final String ACTION_STOP = "io.github.cyf112233.portable.KEEPALIVE_STOP";
    public static final String ACTION_REFRESH = "io.github.cyf112233.portable.KEEPALIVE_REFRESH";
    public static final String EXTRA_STATUS = "status";

    private Handler handler;
    private Runnable ticker;
    private String status;

    public static void start(Context context, String status) {
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_STATUS, status);
        startCompat(context, intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_STOP);
        context.stopService(intent);
    }

    public static void refresh(Context context, String status) {
        Intent intent = new Intent(context, KeepAliveService.class);
        intent.setAction(ACTION_REFRESH);
        intent.putExtra(EXTRA_STATUS, status);
        try {
            context.startService(intent);
        } catch (Exception e) {
            // If the service is not running there is nothing to refresh.
        }
    }

    private static void startCompat(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "could not start keep-alive service: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createChannel();
        // Must be posted promptly: a foreground service that never calls
        // startForeground is killed by the platform.
        startForeground(NOTIFICATION_ID,
                buildNotification(getString(R.string.status_starting)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        String extra = intent == null ? null : intent.getStringExtra(EXTRA_STATUS);

        if (ACTION_STOP.equals(action)) {
            stopServerIfRunning();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (extra != null) {
            status = extra;
        }
        updateNotification(status);
        startTicker();
        return START_STICKY;
    }

    /**
     * Follows the launcher rather than owning it, so the notification always
     * reflects reality even when the server exits on its own.
     */
    private void startTicker() {
        stopTicker();
        ticker = new Runnable() {
            @Override
            public void run() {
                DshApp app = DshApp.get(KeepAliveService.this);
                ProotLauncher launcher = app.launcher();
                String current;
                if (!launcher.isRunning()) {
                    current = getString(R.string.status_stopped);
                } else if (launcher.uiUrl() != null) {
                    // Report the port the server actually bound, not the preferred
                    // one: the preferred port may have been taken and the launcher
                    // moved on to the next free one.
                    current = getString(R.string.status_running)
                            + " · " + getString(R.string.notif_port)
                            + " " + portOf(launcher.uiUrl(), app.port());
                } else {
                    current = getString(R.string.status_starting);
                }
                if (!current.equals(status)) {
                    status = current;
                    updateNotification(current);
                }
                handler.postDelayed(this, 3000);
            }
        };
        handler.postDelayed(ticker, 3000);
    }

    /**
     * The port out of the server's ready URL, falling back to the preferred one.
     *
     * @param url       the tokenised URL dsh printed, or null
     * @param preferred the port the user asked for
     */
    private static int portOf(String url, int preferred) {
        if (url == null) {
            return preferred;
        }
        try {
            int port = java.net.URI.create(url).getPort();
            return port > 0 ? port : preferred;
        } catch (Exception e) {
            Log.w(TAG, "could not read the port from " + url);
            return preferred;
        }
    }

    private void stopTicker() {
        if (ticker != null && handler != null) {
            handler.removeCallbacks(ticker);
            ticker = null;
        }
    }

    private void stopServerIfRunning() {
        try {
            DshApp.get(this).launcher().stop();
        } catch (Exception e) {
            Log.w(TAG, "stop from notification failed: " + e.getMessage());
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        channel.setShowBadge(false);
        // A silent, ongoing row: informative, never intrusive.
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Intent stopIntent = new Intent(this, KeepAliveService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stopIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.addAction(new Notification.Action.Builder(
                    null, getString(R.string.notif_stop), stopPending).build());
        }
        return builder.build();
    }

    @Override
    public void onDestroy() {
        stopTicker();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
