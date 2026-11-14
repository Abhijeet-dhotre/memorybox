package com.muse.memorybox.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.muse.memorybox.R;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal thumbnail loader: fetches the image from the stored thumbnail
 * LINK (the image file itself is never saved to the library, PRD §10),
 * keeps a small in-memory cache, and handles recycled list rows via a
 * URL tag on the view.
 */
public final class ThumbnailLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThumbnailLoader() {
    }

    public static void load(ImageView view, String url) {
        if (url == null || url.isEmpty()) {
            view.setTag(null);
            view.setImageResource(R.drawable.ic_launcher);
            return;
        }
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            view.setTag(url);
            view.setImageBitmap(cached);
            return;
        }
        view.setTag(url);
        view.setImageResource(R.drawable.ic_launcher);
        IO.execute(() -> {
            Bitmap bitmap = fetch(url);
            if (bitmap == null) return;
            CACHE.put(url, bitmap);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    private static Bitmap fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestProperty("User-Agent", "MemoryBox-Android/1.2");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(15_000);
            conn.setInstanceFollowRedirects(true);
            try (InputStream in = conn.getInputStream()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                byte[] data = readAll(in);
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                int width = bounds.outWidth;
                int sample = 1;
                while (width / sample > 720) sample *= 2;
                opts.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) buffer.write(buf, 0, n);
        return buffer.toByteArray();
    }
}
