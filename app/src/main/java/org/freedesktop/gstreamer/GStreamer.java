package org.freedesktop.gstreamer;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class GStreamer {
    private static final String TAG = "GStreamerJava";
    private static boolean isInitialized = false;

    private static native void nativeInit(Context context) throws Exception;

    public static synchronized void init(Context context) throws Exception {
        if (isInitialized) {
            Log.i(TAG, "GStreamer already initialized");
            return;
        }

        Log.i(TAG, "Initializing GStreamer via nativeInit...");
        nativeInit(context);
        copyFonts(context);
        copyCaCertificates(context);
        isInitialized = true;
        Log.i(TAG, "GStreamer initialization completed successfully");
    }

    public static boolean isInitialized() {
        return isInitialized;
    }

    private static void copyFonts(Context context) {
        AssetManager assetManager = context.getAssets();
        File filesDir = context.getFilesDir();
        File fontDir = new File(filesDir, "fontconfig");
        if (!fontDir.exists()) {
            fontDir.mkdirs();
        }
        copyAssetFolder(assetManager, "fontconfig", fontDir);
    }

    private static void copyCaCertificates(Context context) {
        AssetManager assetManager = context.getAssets();
        File filesDir = context.getFilesDir();
        File sslDir = new File(filesDir, "ssl");
        if (!sslDir.exists()) {
            sslDir.mkdirs();
        }
        File certFile = new File(sslDir, "ca-certificates.crt");
        if (!certFile.exists()) {
            copyAssetFile(assetManager, "ssl/ca-certificates.crt", certFile);
        }
    }

    private static void copyAssetFolder(AssetManager assetManager, String fromAssetPath, File toDir) {
        try {
            String[] files = assetManager.list(fromAssetPath);
            if (files == null || files.length == 0) {
                return;
            }
            for (String file : files) {
                String subAssetPath = fromAssetPath + "/" + file;
                File targetFile = new File(toDir, file);
                String[] subFiles = assetManager.list(subAssetPath);
                if (subFiles != null && subFiles.length > 0) {
                    targetFile.mkdirs();
                    copyAssetFolder(assetManager, subAssetPath, targetFile);
                } else {
                    copyAssetFile(assetManager, subAssetPath, targetFile);
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "Optional asset folder not found: " + fromAssetPath);
        }
    }

    private static void copyAssetFile(AssetManager assetManager, String assetPath, File targetFile) {
        try (InputStream in = assetManager.open(assetPath);
             OutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Log.d(TAG, "Optional asset not present: " + assetPath);
        }
    }
}
