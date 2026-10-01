package org.dnaboy.grblsender;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Foreground service held for the duration of a run. It does not stream by itself:
 * it keeps the process at foreground priority (so Android does not kill or freeze the
 * app when the screen locks or another app is opened) and holds a partial wake lock so
 * the CPU keeps feeding Grbl. It also shows the job progress as a notification.
 */
public class JobService extends Service {

    private static final String CHANNEL_RUN = "run";
    private static final String CHANNEL_DONE = "done";
    private static final int ID_RUN = 1;
    private static final int ID_DONE = 2;
    private static final String EXTRA_TITLE = "title";

    private static long lastUpdateMs;
    private static String title = "Grbl Sender";

    private PowerManager.WakeLock wakeLock;

    // ---------------------------------------------------------------- API used by MainActivity

    static void start(Context ctx, String jobTitle) {
        title = jobTitle;
        lastUpdateMs = 0;
        Intent i = new Intent(ctx, JobService.class).putExtra(EXTRA_TITLE, jobTitle);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i);
        } catch (Exception e) {
            RemoteLog.e("service", "could not start foreground service", e);
        }
    }

    /** Update the progress notification (rate-limited to one update every 2 s). */
    static void update(Context ctx, String text, int permille) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastUpdateMs < 2000) return;
        lastUpdateMs = now;
        notifyManager(ctx).notify(ID_RUN, build(ctx, CHANNEL_RUN, title, text, permille, true));
    }

    /** Stop the service; {@code finalText} (if not null) is left as a dismissible notification. */
    static void stop(Context ctx, String finalText) {
        ctx.stopService(new Intent(ctx, JobService.class));
        if (finalText != null) {
            notifyManager(ctx).notify(ID_DONE, build(ctx, CHANNEL_DONE, title, finalText, -1, false));
        }
    }

    // ---------------------------------------------------------------- Service

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getStringExtra(EXTRA_TITLE) != null) title = intent.getStringExtra(EXTRA_TITLE);
        notifyManager(this).cancel(ID_DONE);
        Notification n = build(this, CHANNEL_RUN, title, "Starting…", 0, true);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(ID_RUN, n);
            }
        } catch (Exception e) {
            RemoteLog.e("service", "startForeground failed", e);
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GrblSender:run");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(24 * 3600 * 1000L);   // safety cap: 24 h
        }
        RemoteLog.i("service", "foreground service started: " + title);
        return START_NOT_STICKY;   // the stream lives in the activity; never restart without it
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        RemoteLog.i("service", "foreground service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------------------------------------------------------- notifications

    private static NotificationManager notifyManager(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_RUN) == null) {
            NotificationChannel run = new NotificationChannel(CHANNEL_RUN, "Running job", NotificationManager.IMPORTANCE_LOW);
            run.setDescription("Progress of the G-code job being streamed to Grbl");
            nm.createNotificationChannel(run);
            NotificationChannel done = new NotificationChannel(CHANNEL_DONE, "Job finished", NotificationManager.IMPORTANCE_HIGH);
            done.setDescription("Shown when a job finishes or stops");
            nm.createNotificationChannel(done);
        }
        return nm;
    }

    private static Notification build(Context ctx, String channel, String title, String text, int permille, boolean ongoing) {
        Intent open = new Intent(ctx, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        notifyManager(ctx);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, channel)
                : new Notification.Builder(ctx);
        b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true);
        if (permille >= 0) b.setProgress(1000, permille, false);
        return b.build();
    }
}
