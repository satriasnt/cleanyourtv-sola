package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.Executor;

/**
 * Team badges for the football card (FotMob's small renditions), downloaded once into the launcher's
 * cache folder and kept in memory while Home runs. Only known sports-image hosts load.
 */
final class Badges {

    private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(24);

    private Badges() {
    }

    /**
     * Shows the badge at {@code url} in {@code view}. With no badge (or none that loads) the view is hidden,
     * or, with {@code placeholder}, shows a plain round placeholder so the layout keeps its shape.
     */
    static void show(ImageView view, String url, Executor io, Handler main, boolean placeholder) {
        if (url == null || url.isEmpty() || !url.startsWith("https://")
                || !(url.startsWith("https://images.fotmob.com/") || url.contains("thesportsdb.com/")
                || url.startsWith("https://a.espncdn.com/"))) {
            none(view, placeholder);
            return;
        }
        if (url.equals(view.getTag()) && view.getDrawable() != null) return;
        view.setTag(url);
        Bitmap cached = MEMORY.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setVisibility(View.VISIBLE);
            return;
        }
        File dir = new File(view.getContext().getCacheDir(), "badges");
        io.execute(() -> {
            Bitmap bitmap = load(dir, url);
            main.post(() -> {
                if (!url.equals(view.getTag())) return;   // the card moved on to another match
                if (bitmap == null) {
                    none(view, placeholder);
                    return;
                }
                MEMORY.put(url, bitmap);
                view.setImageBitmap(bitmap);
                view.setVisibility(View.VISIBLE);
            });
        });
    }

    private static void none(ImageView view, boolean placeholder) {
        view.setTag(null);
        if (placeholder) {
            view.setImageResource(R.drawable.badge_placeholder);
            view.setVisibility(View.VISIBLE);
        } else {
            view.setImageDrawable(null);
            view.setVisibility(View.GONE);
        }
    }

    /** A small rendition: TheSportsDB's "/tiny", ESPN's image combiner at 100 px. */
    private static String small(String url) {
        if (url.startsWith("https://a.espncdn.com/i/")) {
            return "https://a.espncdn.com/combiner/i?img=" + url.substring("https://a.espncdn.com".length()) + "&w=100&h=100";
        }
        if (url.startsWith("https://images.fotmob.com/")) return url;   // already the small rendition
        return url.endsWith("/tiny") ? url : url + "/tiny";
    }

    private static Bitmap load(File dir, String url) {
        try {
            File file = new File(dir, sha1(url) + ".png");
            if (!file.isFile()) {
                byte[] bytes = download(small(url));
                if (bytes == null) bytes = download(url);
                if (bytes == null) return null;
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(bytes);
                }
            }
            BitmapFactory.Options size = new BitmapFactory.Options();
            size.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), size);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, Math.min(size.outWidth, size.outHeight) / 96);
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bitmap == null) Files.deleteIfExists(file.toPath());
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("User-Agent", "cleanyourtv-home-widgets");
            try {
                if (c.getResponseCode() != 200) return null;
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) {
                        buf.write(b, 0, n);
                        if (buf.size() > 1024 * 1024) return null;
                    }
                    return buf.toByteArray();
                }
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : d) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
