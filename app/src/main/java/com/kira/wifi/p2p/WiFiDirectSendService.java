package com.kira.wifi.p2p;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public class WiFiDirectSendService extends Service {
    private static final String TAG = "WiFiDirectSendService";
    private static final int SOCKET_TIMEOUT = 5000;
    private static final String CHANNEL_ID = "p2p_transfer_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 1002;

    public static final String ACTION_SEND_FILE = "com.kira.wifi.p2p.SEND_FILE";
    public static final String EXTRAS_FILE_PATH = "file_url";
    public static final String EXTRAS_ADDRESS = "go_host";
    public static final String EXTRAS_PORT = "go_port";

    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_SEND_FILE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String fileUri = intent.getExtras().getString(EXTRAS_FILE_PATH);
        String host = intent.getExtras().getString(EXTRAS_ADDRESS);
        int port = intent.getExtras().getInt(EXTRAS_PORT, 8988);

        Log.d(TAG, "onStartCommand: uri=" + fileUri + ", host=" + host + ", port=" + port);

        if (fileUri == null || host == null) {
            Log.e(TAG, "onStartCommand: null fileUri or host");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(FOREGROUND_NOTIFICATION_ID,
                buildNotification("Sending file..."));

        // Acquire locks
        acquireLocks();

        // Do the transfer on a background thread
        final int thisStartId = startId;
        final String finalFileUri = fileUri;
        final String finalHost = host;
        final int finalPort = port;

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean success = doTransfer(finalFileUri, finalHost, finalPort);
                Log.d(TAG, "Transfer " + (success ? "successful" : "failed"));

                // Release locks
                releaseLocks();

                // Update notification result then stop
                if (success) {
                    updateNotification("File sent successfully");
                } else {
                    updateNotification("File send failed");
                }

                // Brief delay so user can see result, then stop
                try { Thread.sleep(1000); } catch (InterruptedException e) { /* ignore */ }
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf(thisStartId);
            }
        }, "P2P-Sender").start();

        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseLocks();
    }

    private boolean doTransfer(String fileUri, String host, int port) {
        Socket socket = new Socket();
        try {
            socket.bind(null);
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT);
            Log.d(TAG, "Socket connected: " + socket.isConnected());

            OutputStream stream = socket.getOutputStream();
            ContentResolver cr = getApplicationContext().getContentResolver();
            InputStream is = null;
            try {
                is = cr.openInputStream(Uri.parse(fileUri));
                if (is == null) {
                    Log.e(TAG, "Failed to open input stream: " + fileUri);
                    return false;
                }
            } catch (FileNotFoundException e) {
                Log.e(TAG, "File not found: " + e.getMessage());
                return false;
            }

            return FileUtils.copyFile(is, stream);

        } catch (IOException e) {
            Log.e(TAG, "Transfer error: " + e.getMessage());
            return false;
        } finally {
            if (socket.isConnected()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing socket: " + e.getMessage());
                }
            }
        }
    }

    private void acquireLocks() {
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "P2PTransfer:SendWakeLock");
                mWakeLock.acquire(10 * 60 * 1000L);
                Log.d(TAG, "WakeLock acquired");
            }
        }
        if (mWifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null) {
                mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "P2PTransfer:SendWifiLock");
                mWifiLock.acquire();
                Log.d(TAG, "WifiLock acquired");
            }
        }
    }

    private void releaseLocks() {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
            Log.d(TAG, "WakeLock released");
        }
        if (mWifiLock != null && mWifiLock.isHeld()) {
            mWifiLock.release();
            mWifiLock = null;
            Log.d(TAG, "WifiLock released");
        }
    }

    // ---- Notification helpers ----

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "P2P Transfer",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("P2P file transfer service");
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notifyIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("P2P Transfer")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(text));
        }
    }
}
