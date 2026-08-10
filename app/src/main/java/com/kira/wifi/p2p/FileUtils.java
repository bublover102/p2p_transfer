package com.kira.wifi.p2p;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Utility class for file operations.
 */
public class FileUtils {
    private static final String TAG = "FileUtils";

    /**
     * Copy data from input stream to output stream.
     * Both streams are closed in a finally block to ensure cleanup.
     *
     * @param in  the input stream
     * @param out the output stream
     * @return true if copy succeeded, false otherwise
     */
    public static boolean copyFile(InputStream in, OutputStream out) {
        if (in == null || out == null) {
            Log.e(TAG, "copyFile: null stream");
            return false;
        }
        byte[] buf = new byte[8192];
        int len;
        try {
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            out.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "copyFile error: " + e.getMessage());
            return false;
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing output stream: " + e.getMessage());
            }
            try {
                in.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing input stream: " + e.getMessage());
            }
        }
    }
}
