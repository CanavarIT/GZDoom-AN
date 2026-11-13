package com.az.doom;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies the engine resources (gzdoom.pk3, soundfonts, ...) from the APK to app storage. */
final class AssetInstaller {
    private AssetInstaller() {}

    /** Directory the engine treats as its "progdir" (must match i_main.cpp: home + "/gzdoom/"). */
    static File engineDir(File home) {
        return new File(home, "gzdoom");
    }

    /** Where WADs/PK3s supplied by the user live. */
    static File wadsDir(File home) {
        return new File(home, "wads");
    }

    /** Home directory handed to the engine with -androidhome. */
    static File homeDir(Context c) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) dir = c.getFilesDir();
        return dir;
    }

    static boolean isInstalled(Context c, File home) {
        File marker = new File(engineDir(home), ".installed");
        File pk3 = new File(engineDir(home), "gzdoom.pk3");
        if (!marker.exists() || !pk3.exists()) return false;
        try {
            return stamp(c).equals(readText(marker));
        } catch (IOException e) {
            return false;
        }
    }

    static void install(Context c, File home) throws IOException {
        File dst = engineDir(home);
        if (!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create " + dst);
        copyAssetDir(c.getAssets(), "gzdoom", dst);
        try (OutputStream out = new FileOutputStream(new File(dst, ".installed"))) {
            out.write(stamp(c).getBytes("UTF-8"));
        }
    }

    private static String stamp(Context c) {
        try {
            long t = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            return "v1-" + t;
        } catch (Exception e) {
            return "v1";
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 256)];
            int n = in.read(buf);
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        }
    }

    private static void copyAssetDir(AssetManager am, String path, File dst) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            copyAssetFile(am, path, dst);
            return;
        }
        if (!dst.exists() && !dst.mkdirs()) throw new IOException("Cannot create " + dst);
        for (String child : children) {
            copyAssetDir(am, path + "/" + child, new File(dst, child));
        }
    }

    private static void copyAssetFile(AssetManager am, String path, File dst) throws IOException {
        try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
