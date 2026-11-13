package com.az.doom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Makes a cover picture for a game file:
 *  - WAD: the TITLEPIC (or TITLE / INTERPIC / CREDIT) lump, decoded with the file's own PLAYPAL
 *  - PK3/PK7/IPK3: graphics/titlepic.* and friends
 *  - otherwise a generated placeholder
 * Results are cached as PNG in the app cache directory.
 */
final class CoverLoader {
    static final int W = 640, H = 480;       // 4:3, like the real game on a CRT

    private CoverLoader() {}

    // ------------------------------------------------------------------ public API

    static Bitmap load(Context ctx, File f, byte[] fallbackPalette) {
        File cacheDir = new File(ctx.getCacheDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        String key = "c2_" + f.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "_" + f.length() + "_" + f.lastModified() + ".png";
        File cached = new File(cacheDir, key);
        if (cached.exists()) {
            Bitmap b = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (b != null) return b;
        }

        Bitmap cover = null;
        try {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (n.endsWith(".wad")) cover = fromWad(f, fallbackPalette);
            else if (n.endsWith(".pk3") || n.endsWith(".ipk3") || n.endsWith(".pk7")
                    || n.endsWith(".ipk7") || n.endsWith(".pkz")) cover = fromZip(f, fallbackPalette);
        } catch (Throwable ignored) {
            cover = null;
        }
        if (cover == null) cover = placeholder(f.getName());

        try (FileOutputStream out = new FileOutputStream(cached)) {
            cover.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException ignored) {
        }
        return cover;
    }

    /** The first PLAYPAL found in the given IWADs (used for add-ons that have no palette of their own). */
    static byte[] findPalette(List<File> wads) {
        for (File f : wads) {
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".wad")) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                Map<String, long[]> dir = readWadDirectory(raf);
                byte[] p = readHead(raf, dir.get("PLAYPAL"), 768);
                if (p != null && p.length >= 768) return p;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ WAD

    private static final String[] WAD_COVER_LUMPS = { "TITLEPIC", "TITLE", "INTERPIC", "CREDIT", "HELP1", "HELP" };

    private static Bitmap fromWad(File f, byte[] fallbackPalette) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            Map<String, long[]> dir = readWadDirectory(raf);
            byte[] pal = readHead(raf, dir.get("PLAYPAL"), 768);
            if (pal == null || pal.length < 768) pal = fallbackPalette;
            for (String name : WAD_COVER_LUMPS) {
                byte[] lump = readLump(raf, dir.get(name), 4 * 1024 * 1024);
                if (lump == null) continue;
                Bitmap b = decodeImage(lump, pal);
                if (b != null) return fit(b);
            }
        }
        return null;
    }

    /** name -> {filepos, size}; a later lump with the same name replaces an earlier one. */
    private static Map<String, long[]> readWadDirectory(RandomAccessFile raf) throws IOException {
        byte[] h = new byte[12];
        raf.readFully(h);
        String id = new String(h, 0, 4, "US-ASCII");
        if (!id.equals("IWAD") && !id.equals("PWAD")) throw new IOException("not a WAD");
        int n = le32(h, 4);
        long dirPos = le32(h, 8) & 0xFFFFFFFFL;
        if (n < 0 || n > 400000 || dirPos < 12) throw new IOException("bad WAD");
        byte[] d = new byte[n * 16];
        raf.seek(dirPos);
        raf.readFully(d);
        Map<String, long[]> map = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int o = i * 16;
            int len = 0;
            while (len < 8 && d[o + 8 + len] != 0) len++;
            String name = new String(d, o + 8, len, "US-ASCII").toUpperCase(Locale.ROOT);
            map.put(name, new long[] { le32(d, o) & 0xFFFFFFFFL, le32(d, o + 4) & 0xFFFFFFFFL });
        }
        return map;
    }

    /** First {@code n} bytes of a lump (the palette lump holds 14 palettes, only the first is needed). */
    private static byte[] readHead(RandomAccessFile raf, long[] e, int n) throws IOException {
        if (e == null || e[1] < n) return null;
        byte[] b = new byte[n];
        raf.seek(e[0]);
        raf.readFully(b);
        return b;
    }

    private static byte[] readLump(RandomAccessFile raf, long[] e, int maxSize) throws IOException {
        if (e == null || e[1] <= 0 || e[1] > maxSize) return null;
        byte[] b = new byte[(int) e[1]];
        raf.seek(e[0]);
        raf.readFully(b);
        return b;
    }

    // ------------------------------------------------------------------ ZIP (pk3)

    private static final String[] ZIP_COVERS = {
        "titlepic.png", "titlepic.jpg", "titlepic.jpeg", "graphics/titlepic.png", "graphics/titlepic.jpg",
        "graphics/titlepic.jpeg", "titlepic", "graphics/titlepic", "title.png", "graphics/title.png",
        "graphics/title", "title", "interpic.png", "graphics/interpic.png", "graphics/credit.png", "credit.png",
        "graphics/credit", "credit", "banner.png", "cover.png", "icon.png"
    };

    private static Bitmap fromZip(File f, byte[] fallbackPalette) throws IOException {
        try (ZipFile zip = new ZipFile(f)) {
            Map<String, ZipEntry> entries = new HashMap<>();
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry ze = en.nextElement();
                if (!ze.isDirectory()) entries.put(ze.getName().toLowerCase(Locale.ROOT), ze);
            }
            byte[] pal = fallbackPalette;
            ZipEntry pp = entries.get("playpal");
            if (pp == null) pp = entries.get("palettes/playpal");
            if (pp != null && pp.getSize() >= 768) {
                byte[] p = readAll(zip.getInputStream(pp), 1 << 16);
                if (p != null && p.length >= 768) pal = p;
            }
            for (String name : ZIP_COVERS) {
                ZipEntry ze = entries.get(name);
                if (ze == null || ze.getSize() <= 0 || ze.getSize() > 16 * 1024 * 1024) continue;
                byte[] data = readAll(zip.getInputStream(ze), 16 * 1024 * 1024);
                Bitmap b = data == null ? null : decodeImage(data, pal);
                if (b != null) return fit(b);
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n, total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                if (total > max) return null;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ decoding

    private static Bitmap decodeImage(byte[] d, byte[] pal) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return BitmapFactory.decodeByteArray(d, 0, d.length);
        }
        if (d.length > 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            return BitmapFactory.decodeByteArray(d, 0, d.length);
        }
        if (d.length == 64000) return decodeRaw320x200(d, pal);     // Heretic / Hexen title screens
        return decodePatch(d, pal);                                 // Doom picture format
    }

    private static int[] palette(byte[] pal) {
        int[] c = new int[256];
        for (int i = 0; i < 256; i++) {
            if (pal != null && pal.length >= 768) {
                c[i] = 0xFF000000 | ((pal[i * 3] & 0xFF) << 16) | ((pal[i * 3 + 1] & 0xFF) << 8) | (pal[i * 3 + 2] & 0xFF);
            } else {
                c[i] = 0xFF000000 | (i << 16) | (i << 8) | i;
            }
        }
        return c;
    }

    private static Bitmap decodeRaw320x200(byte[] d, byte[] pal) {
        int[] c = palette(pal);
        int[] px = new int[320 * 200];
        for (int i = 0; i < px.length; i++) px[i] = c[d[i] & 0xFF];
        return Bitmap.createBitmap(px, 320, 200, Bitmap.Config.ARGB_8888);
    }

    private static Bitmap decodePatch(byte[] d, byte[] pal) {
        if (d.length < 8) return null;
        int w = le16(d, 0), h = le16(d, 2);
        if (w < 8 || h < 8 || w > 2048 || h > 2048) return null;
        if (d.length < 8 + w * 4) return null;
        int[] c = palette(pal);
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, 0xFF000000);
        for (int x = 0; x < w; x++) {
            int p = le32(d, 8 + x * 4);
            if (p < 0 || p >= d.length) return null;
            while (p < d.length) {
                int top = d[p] & 0xFF;
                if (top == 0xFF) break;
                if (p + 3 > d.length) return null;
                int len = d[p + 1] & 0xFF;
                int src = p + 3;
                if (src + len > d.length) return null;
                for (int i = 0; i < len; i++) {
                    int y = top + i;
                    if (y < h) px[y * w + x] = c[d[src + i] & 0xFF];
                }
                p = src + len + 1;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    // ------------------------------------------------------------------ helpers

    /** Scales/crops to W x H. Small pixel art keeps hard pixels. */
    private static Bitmap fit(Bitmap src) {
        float s = Math.max((float) W / src.getWidth(), (float) H / src.getHeight());
        // a Doom picture is 320x200 but shown as 4:3: stretch it, do not crop it
        if (Math.abs(src.getWidth() / (float) src.getHeight() - 1.6f) < 0.01f) {
            return Bitmap.createScaledBitmap(src, W, H, false);
        }
        Matrix m = new Matrix();
        m.postScale(s, s);
        m.postTranslate((W - src.getWidth() * s) / 2f, (H - src.getHeight() * s) / 2f);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setFilterBitmap(src.getWidth() > 400);
        c.drawBitmap(src, m, p);
        return out;
    }

    /** Pretty generated cover when the file has no picture. */
    static Bitmap placeholder(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        int hue = Math.abs(base.toLowerCase(Locale.ROOT).hashCode()) % 360;

        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int top = Color.HSVToColor(new float[] { hue, 0.55f, 0.55f });
        int bottom = Color.HSVToColor(new float[] { (hue + 25) % 360, 0.70f, 0.14f });
        p.setShader(new LinearGradient(0, 0, 0, H, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);

        // diagonal stripes
        p.setColor(0x14FFFFFF);
        for (int i = -H; i < W; i += 70) {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(i, H);
            path.lineTo(i + 26, H);
            path.lineTo(i + 26 + H, 0);
            path.lineTo(i + H, 0);
            path.close();
            c.drawPath(path, p);
        }

        String letters = initials(base);
        p.setColor(0xE6FFFFFF);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        float size = letters.length() > 2 ? 190f : 250f;
        p.setTextSize(size);
        Rect r = new Rect();
        p.getTextBounds(letters, 0, letters.length(), r);
        c.drawText(letters, W / 2f, H / 2f + r.height() / 2f, p);
        return out;
    }

    private static String initials(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (sb.length() >= 3) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
