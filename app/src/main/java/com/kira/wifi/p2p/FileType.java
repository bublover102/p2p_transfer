package com.kira.wifi.p2p;

import android.text.TextUtils;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;

public class FileType {

    private static final String TAG = "FileType";

    public static final HashMap<String, String> mFileTypes = new HashMap<>();

    static {
        mFileTypes.put("ffd8ffe000104a464946", "jpg");
        mFileTypes.put("ffd8ffe1250f45786966", "jpg");
        mFileTypes.put("89504e470d0a1a0a0000", "png");
        mFileTypes.put("47494638396126026f01", "gif");
        mFileTypes.put("49492a00227105008037", "tif");
        mFileTypes.put("424d228c010000000000", "bmp");
        mFileTypes.put("424d8240090000000000", "bmp");
        mFileTypes.put("424d8e1b030000000000", "bmp");
        mFileTypes.put("41433130313500000000", "dwg");
        mFileTypes.put("3c21444f435459504520", "html");
        mFileTypes.put("3c21646f637479706520", "htm");
        mFileTypes.put("48544d4c207b0d0a0942", "css");
        mFileTypes.put("696b2e71623d696b2e71", "js");
        mFileTypes.put("7b5c727466315c616e73", "rtf");
        mFileTypes.put("38425053000100000000", "psd");
        mFileTypes.put("46726f6d3a203d3f6762", "eml");
        mFileTypes.put("d0cf11e0a1b11ae10000", "doc");
        mFileTypes.put("d0cf11e0a1b11ae10000", "vsd");
        mFileTypes.put("5374616E64617264204A", "mdb");
        mFileTypes.put("252150532D41646F6265", "ps");
        mFileTypes.put("255044462d312e350d0a", "pdf");
        mFileTypes.put("2e524d46000000120001", "rmvb");
        mFileTypes.put("464c5601050000000900", "flv");
        mFileTypes.put("00000020667479706d70", "mp4");
        mFileTypes.put("00000020667479706973", "mp4");
        mFileTypes.put("00000018667479706D70", "mp4");
        mFileTypes.put("49443303000000002176", "mp3");
        mFileTypes.put("000001ba210001000180", "mpg");
        mFileTypes.put("3026b2758e66cf11a6d9", "wmv");
        mFileTypes.put("52494646e27807005741", "wav");
        mFileTypes.put("52494646d07d60074156", "avi");
        mFileTypes.put("4d546864000000060001", "mid");
        mFileTypes.put("504b0304140000000800", "zip");
        mFileTypes.put("526172211a0700cf9073", "rar");
        mFileTypes.put("235468697320636f6e66", "ini");
        mFileTypes.put("504b03040a0000000000", "jar");
        mFileTypes.put("4d5a9000030000000400", "exe");
        mFileTypes.put("3c25402070616765206c", "jsp");
        mFileTypes.put("4d616e69666573742d56", "mf");
        mFileTypes.put("3c3f786d6c2076657273", "xml");
        mFileTypes.put("494e5345525420494e54", "sql");
        mFileTypes.put("7061636b616765207765", "java");
        mFileTypes.put("406563686f206f66660d", "bat");
        mFileTypes.put("1f8b0800000000000000", "gz");
        mFileTypes.put("6c6f67346a2e726f6f74", "properties");
        mFileTypes.put("cafebabe0000002e0041", "class");
        mFileTypes.put("49545346030000006000", "chm");
        mFileTypes.put("04000000010000001300", "mxp");
        mFileTypes.put("504b0304140006000800", "docx");
        mFileTypes.put("d0cf11e0a1b11ae10000", "wps");
        mFileTypes.put("6431303a637265617465", "torrent");
        mFileTypes.put("6d6f6f76", "mov");
        mFileTypes.put("ff575043", "wpd");
        mFileTypes.put("cfad12feC5fd746f", "dbx");
        mFileTypes.put("2142444e", "pst");
        mFileTypes.put("ac9ebd8f", "qdf");
        mFileTypes.put("e3828596", "pwl");
        mFileTypes.put("2e7261fd", "ram");
    }

    /**
     * Detect file type from header bytes.
     *
     * @param in FileInputStream to read header from (will be closed after reading)
     * @return file extension string, or "bin" if unknown
     */
    public static String getFileType(FileInputStream in) {
        String keySearch = getFileHeader(in);
        if (keySearch == null) {
            return "bin";
        }
        keySearch = keySearch.toLowerCase();
        String fileSuffix = mFileTypes.get(keySearch);

        if (TextUtils.isEmpty(fileSuffix)) {
            // Try fuzzy match with first 5 hex chars
            if (keySearch.length() >= 5) {
                String keySearchPrefix = keySearch.substring(0, 5);
                for (String key : mFileTypes.keySet()) {
                    if (key != null && key.toLowerCase().contains(keySearchPrefix)) {
                        fileSuffix = mFileTypes.get(key);
                        break;
                    }
                }
            }
        }

        // Fallback to bin if no match found
        if (TextUtils.isEmpty(fileSuffix)) {
            fileSuffix = "bin";
        }
        return fileSuffix;
    }

    private static String getFileHeader(FileInputStream in) {
        String value = null;
        try {
            byte[] b = new byte[10];
            int read = in.read(b, 0, b.length);
            if (read > 0) {
                value = bytesToHexString(b);
            }
        } catch (Exception e) {
            Log.e(TAG, "getFileHeader error: " + e.getMessage());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return value;
    }

    private static String bytesToHexString(byte[] src) {
        if (src == null || src.length <= 0) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (byte b : src) {
            String hv = Integer.toHexString(b & 0xFF).toUpperCase();
            if (hv.length() < 2) {
                builder.append('0');
            }
            builder.append(hv);
        }
        return builder.toString();
    }
}
