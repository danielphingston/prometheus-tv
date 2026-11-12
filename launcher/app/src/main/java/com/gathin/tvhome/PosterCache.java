package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Continue watching posters: the image URIs apps publish with their rows (https for Stremio and Prime Video,
 * content:// for some). Downloaded once, kept on disk in the app's cache (Android may clear it), decoded at the card's
 * size in RGB_565 and held in a small memory cache. Only these URIs are fetched; nothing else goes online.
 */
final class PosterCache {
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int TIMEOUT_MS = 8000;
    /** Files older than this are fetched again, so a changed poster shows up eventually. */
    private static final long MAX_FILE_AGE_MS = 14L * 24 * 60 * 60 * 1000;

    private final Context context;
    private final File dir;
    private final int width, height;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    PosterCache(Context context, int width, int height) {
        this.context = context.getApplicationContext();
        this.dir = new File(context.getCacheDir(), "posters");
        this.width = width;
        this.height = height;
    }

    /** The decoded poster from memory, or null. */
    Bitmap cached(String uri) {
        return uri == null ? null : memory.get(uri);
    }

    /** Loads the poster off the main thread and hands it (or null) to done on the main thread. */
    void load(String uri, Consumer<Bitmap> done) {
        if (uri == null || uri.isEmpty()) {
            done.accept(null);
            return;
        }
        Bitmap hit = memory.get(uri);
        if (hit != null) {
            done.accept(hit);
            return;
        }
        pool.execute(() -> {
            Bitmap bitmap = null;
            try {
                byte[] bytes = bytes(uri);
                if (bytes != null) bitmap = decode(bytes);
            } catch (IOException | RuntimeException e) {
                Log.w(ContinueWatching.TAG, "poster " + uri + ": " + e);
            }
            if (bitmap != null) memory.put(uri, bitmap);
            Bitmap result = bitmap;
            main.post(() -> done.accept(result));
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private byte[] bytes(String uri) throws IOException {
        String scheme = Uri.parse(uri).getScheme();
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) return download(uri);
        if ("content".equalsIgnoreCase(scheme) || "android.resource".equalsIgnoreCase(scheme)
                || "file".equalsIgnoreCase(scheme)) {
            try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
                return in == null ? null : readAll(in);
            }
        }
        return null;
    }

    private byte[] download(String uri) throws IOException {
        File file = new File(dir, key(uri));
        if (file.isFile() && System.currentTimeMillis() - file.lastModified() < MAX_FILE_AGE_MS) {
            return Files.readAllBytes(file.toPath());
        }
        HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) {
                // Offline or gone: an old copy is better than nothing.
                return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
            }
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                bytes = readAll(in);
            }
            if (bytes == null) return null;
            if (dir.isDirectory() || dir.mkdirs()) {
                File tmp = new File(dir, file.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                }
                if (!tmp.renameTo(file)) tmp.delete();
            }
            return bytes;
        } catch (IOException e) {
            if (file.isFile()) return Files.readAllBytes(file.toPath());
            throw e;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) return null;
        }
        return out.toByteArray();
    }

    private Bitmap decode(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, d);
    }

    private static String key(String uri) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : h) s.append(String.format("%02x", b));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(uri.hashCode());
        }
    }
}
