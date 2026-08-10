package com.kira.wifi.p2p;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;

import static com.kira.wifi.p2p.MainActivity.EXTRA_IP_ADDR;
import static com.kira.wifi.p2p.MainActivity.TCP_CLIENT_ACTION;

public class WiFiDirectReceiveService extends Service {
    private static final String TAG = "WiFiDirectReceiveService";
    private static final int PORT = 8988;
    private static final String CHANNEL_ID = "p2p_transfer_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;

    private volatile boolean mRunning = true;
    private Thread mServerThread;
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;
    private WifiManager mWifiManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        // Start as foreground service to prevent system from killing us during screen-off
        startForeground(FOREGROUND_NOTIFICATION_ID, buildNotification("Ready to receive files"));

        // Acquire locks if not already held
        acquireLocks();

        // Start server thread if not running
        if (mServerThread == null || !mServerThread.isAlive()) {
            mRunning = true;
            mServerThread = new Thread(new ServerRunnable());
            mServerThread.setName("P2P-Server");
            mServerThread.start();
            updateNotification("Listening for connections...");
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mRunning = false;
        releaseLocks();
        stopForeground(true);
        super.onDestroy();
    }

    /**
     * Acquire WakeLock and WifiLock to prevent sleep during transfers.
     */
    private void acquireLocks() {
        // CPU lock - keep CPU awake during screen-off
        if (mWakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "P2PTransfer:ReceiveWakeLock");
                mWakeLock.acquire(10 * 60 * 1000L); // 10 minute timeout
                Log.d(TAG, "WakeLock acquired");
            }
        }

        // WiFi lock - prevent WiFi from going into low-power mode
        if (mWifiLock == null) {
            mWifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (mWifiManager != null) {
                mWifiLock = mWifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF, "P2PTransfer:ReceiveWifiLock");
                mWifiLock.acquire();
                Log.d(TAG, "WifiLock acquired");
            }
        }
    }

    /**
     * Release WakeLock and WifiLock.
     */
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

    // ---- Server Runnable ----

    private class ServerRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "Server thread started");
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                while (mRunning) {
                    try {
                        // accept with timeout so we can check mRunning periodically
                        serverSocket.setSoTimeout(2000);
                        Socket socket = serverSocket.accept();
                        serverSocket.setSoTimeout(0); // reset timeout

                        String clientAddr = socket.getInetAddress().getHostAddress();
                        Log.d(TAG, "Client connected from " + clientAddr);
                        updateNotification("Receiving file from " + clientAddr + "...");

                        // Notify MainActivity about the connected client
                        Intent mainIntent = new Intent(TCP_CLIENT_ACTION);
                        mainIntent.putExtra(EXTRA_IP_ADDR, clientAddr);
                        sendBroadcast(mainIntent);

                        // Receive the file
                        InputStream inputStream = socket.getInputStream();

                        File dirs = new File(Environment.getExternalStorageDirectory(), "wifip2p");
                        if (!dirs.exists() && !dirs.mkdirs()) {
                            Log.e(TAG, "Failed to create directory: " + dirs);
                            socket.close();
                            continue;
                        }

                        String baseName = String.valueOf(System.currentTimeMillis());
                        File tempFile = new File(dirs, baseName);
                        Log.d(TAG, "Receiving file to " + tempFile);

                        boolean copied = FileUtils.copyFile(inputStream, new FileOutputStream(tempFile));
                        socket.close();

                        if (!copied) {
                            Log.e(TAG, "Failed to copy file data");
                            if (tempFile.exists()) tempFile.delete();
                            updateNotification("File receive failed");
                            continue;
                        }

                        // Detect file type and rename
                        String extension;
                        try (FileInputStream fis = new FileInputStream(tempFile)) {
                            extension = FileType.getFileType(fis);
                        }
                        String finalName = baseName + "." + extension;
                        File finalFile = new File(dirs, finalName);
                        if (tempFile.renameTo(finalFile)) {
                            Log.d(TAG, "File saved as " + finalFile);
                        } else {
                            Log.w(TAG, "Rename failed, kept as " + tempFile);
                            finalFile = tempFile;
                        }

                        updateNotification("File received: " + finalFile.getName());

                        // Notify media scanner
                        Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        scanIntent.setData(Uri.fromFile(finalFile));
                        sendBroadcast(scanIntent);

                    } catch (java.net.SocketTimeoutException e) {
                        // Timeout from accept(), just loop and check mRunning
                    } catch (Exception e) {
                        Log.e(TAG, "Server error: " + e.getMessage());
                        updateNotification("Connection error");
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Server fatal error: " + e.getMessage());
            }
            Log.d(TAG, "Server thread stopped");
        }
    }
}
