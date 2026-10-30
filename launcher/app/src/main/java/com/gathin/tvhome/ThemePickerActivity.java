package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Launcher settings → Tema: the themes in the repo's themes/ folder, with their screenshots. Opening the
 * menu shows the list saved on the TV; "Segarkan daftar" reads the repo again, at most once a day (a
 * failed attempt does not count). Applying a theme downloads its config.json and wallpapers into the
 * launcher's theme folder and keeps this TV's own keys (remote buttons, Live TV app) in the new config.
 */
public final class ThemePickerActivity extends Activity {

    static final String PREF_APPLIED = "theme_applied";
    private static final String PREF_INDEX = "themes_index";
    private static final String PREF_REFRESH_DAY = "themes_refresh_day";
    private static final String PREF_REFRESHED_AT = "themes_refreshed_at";

    private static final String REPO = "Gathin23/cleanyourtv";
    private static final String BRANCH = "main";
    private static final String TREE = "https://api.github.com/repos/" + REPO + "/git/trees/" + BRANCH + "?recursive=1";
    private static final String RAW = "https://raw.githubusercontent.com/" + REPO + "/" + BRANCH + "/";

    /** Keys that belong to this TV rather than to a look: every theme change keeps them. */
    private static final String[] KEEP = {"app_keys", "settings_key_keycodes", "settings_key_panel",
            "restore_key_services", "input_key_keycodes", "key_filter", "handle_view_inputs", "stock_home_redirect",
            "live_tv_package", "live_tv_action", "live_tv_input_id", "vpn_app", "greeting_name",
            "tile_menu", "app_library", "library_button"};

    private static final Map<String, String> ABOUT = new LinkedHashMap<>();
    static {
        ABOUT.put("default", "Tampilan bawaan launcher");
        ABOUT.put("aurora", "Wallpaper aurora gelap, toska dan ungu");
        ABOUT.put("glass", "Dock kaca buram, tombol ikon, cahaya warna aplikasi");
        ABOUT.put("scenes", "14 pemandangan ilustrasi, berganti tiap jam");
        ABOUT.put("six-columns", "6 tile per baris, satu baris di bawah dock");
        ABOUT.put("dussehra", "Tema festival Dussehra (sudah lewat)");
    }

    private SharedPreferences prefs;
    private TextView info;
    private LinearLayout rows;
    private boolean busy;
    private float density;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_theme_picker);
        prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        info = findViewById(R.id.themeInfo);
        rows = findViewById(R.id.themeRows);
        findViewById(R.id.themeRefresh).setOnClickListener(v -> {
            if (busy) return;
            if (LocalDate.now().toString().equals(prefs.getString(PREF_REFRESH_DAY, ""))) {
                Toast.makeText(this, "Daftar sudah diperbarui hari ini. Coba lagi besok.", Toast.LENGTH_LONG).show();
                return;
            }
            refresh();
        });
        findViewById(R.id.themeReset).setOnClickListener(v -> {
            if (busy) return;
            new AlertDialog.Builder(this)
                    .setTitle("Kembali ke tampilan bawaan?")
                    .setMessage("Tema dan wallpaper-nya dihapus. Pengaturan tombol remote tetap disimpan.")
                    .setPositiveButton("Kembalikan", (d, w) -> apply(null))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        render();
        // The very first visit has no list yet: fetch it once (that is today's refresh).
        if (prefs.getString(PREF_INDEX, "").isEmpty()) refresh();
    }

    // ---- list -------------------------------------------------------------------------------------

    private void refresh() {
        busy = true;
        info.setText("Mengambil daftar tema…");
        new Thread(() -> {
            String index = null;
            try {
                index = readTree(HomeWidgets.get(TREE));
            } catch (Exception e) {
                index = null;
            }
            final String result = index;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing()) return;
                if (result == null) {
                    render();
                    info.setText(info.getText() + "\nGagal mengambil daftar (cek internet). Jatah hari ini belum terpakai.");
                    return;
                }
                prefs.edit().putString(PREF_INDEX, result).putString(PREF_REFRESH_DAY, LocalDate.now().toString())
                        .putLong(PREF_REFRESHED_AT, System.currentTimeMillis()).apply();
                render();
            });
        }, "themes").start();
    }

    /** The repo tree reduced to {theme: {relative path: sha}} for folders that hold a config.json. */
    private static String readTree(String json) throws Exception {
        JSONArray tree = new JSONObject(json).getJSONArray("tree");
        Map<String, JSONObject> themes = new LinkedHashMap<>();
        for (int i = 0; i < tree.length(); i++) {
            JSONObject e = tree.getJSONObject(i);
            if (!"blob".equals(e.optString("type"))) continue;
            String path = e.optString("path");
            if (!path.startsWith("themes/")) continue;
            String rest = path.substring("themes/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) continue;
            String name = rest.substring(0, slash);
            String file = rest.substring(slash + 1);
            boolean wanted = file.equals("config.json") || file.equals("wallpaper.jpg") || file.equals("screenshot.jpg")
                    || file.matches("wallpapers/[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)");
            if (!name.matches("[A-Za-z0-9_.-]+") || !wanted) continue;
            JSONObject files = themes.get(name);
            if (files == null) {
                files = new JSONObject();
                themes.put(name, files);
            }
            files.put(file, e.optString("sha"));
        }
        JSONObject out = new JSONObject();
        for (Map.Entry<String, JSONObject> t : themes.entrySet()) {
            if (t.getValue().has("config.json")) out.put(t.getKey(), t.getValue());
        }
        if (out.length() == 0) throw new IllegalStateException("no themes");
        return out.toString();
    }

    private void render() {
        long at = prefs.getLong(PREF_REFRESHED_AT, 0);
        String applied = prefs.getString(PREF_APPLIED, "");
        String when = at == 0 ? "belum pernah" : Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("EEEE d MMM, HH.mm", new Locale("id", "ID")));
        boolean usedToday = LocalDate.now().toString().equals(prefs.getString(PREF_REFRESH_DAY, ""));
        info.setText("Terakhir diperbarui: " + when + (usedToday ? " · bisa disegarkan lagi besok" : "")
                + "\nTema terpasang: " + (applied.isEmpty() ? "bawaan" : pretty(applied)));
        rows.removeAllViews();
        JSONObject index;
        try {
            index = new JSONObject(prefs.getString(PREF_INDEX, "{}"));
        } catch (Exception e) {
            index = new JSONObject();
        }
        for (Iterator<String> it = index.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject files = index.optJSONObject(name);
            if (files != null) rows.addView(row(name, files, name.equals(applied)));
        }
    }

    private View row(String name, JSONObject files, boolean applied) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.settings_choice_bg);
        row.setFocusable(true);
        row.setClickable(true);
        int pad = (int) (12 * density);
        row.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * density);
        row.setLayoutParams(lp);

        ImageView shot = new ImageView(this);
        shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shot.setBackgroundColor(0x1FFFFFFF);
        row.addView(shot, new LinearLayout.LayoutParams((int) (160 * density), (int) (90 * density)));
        if (files.has("screenshot.jpg")) loadShot(shot, name, files.optString("screenshot.jpg"));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        tp.leftMargin = (int) (18 * density);
        row.addView(text, tp);

        TextView title = new TextView(this);
        title.setText(pretty(name) + (applied ? "   ✓ Terpasang" : ""));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        text.addView(title);
        TextView about = new TextView(this);
        String desc = ABOUT.containsKey(name) ? ABOUT.get(name) : "Tema baru dari repo";
        int walls = 0;
        for (Iterator<String> it = files.keys(); it.hasNext(); ) if (it.next().startsWith("wallpapers/")) walls++;
        if (walls > 0) desc += " · " + walls + " wallpaper";
        else if (files.has("wallpaper.jpg")) desc += " · dengan wallpaper";
        about.setText(desc);
        about.setTextColor(0xFFACB8C1);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.addView(about);

        row.setOnClickListener(v -> {
            if (busy) return;
            new AlertDialog.Builder(this)
                    .setTitle("Pasang tema " + pretty(name) + "?")
                    .setMessage("Home akan dimuat ulang dengan tampilan baru. Pengaturan tombol remote tetap disimpan.")
                    .setPositiveButton("Pasang", (d, w) -> apply(name))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
        return row;
    }

    private void loadShot(ImageView view, String name, String sha) {
        File file = new File(new File(getCacheDir(), "theme_shots"), sha.replaceAll("[^A-Za-z0-9]", "") + ".jpg");
        new Thread(() -> {
            try {
                if (!file.isFile()) download(RAW + "themes/" + name + "/screenshot.jpg", file, 4 << 20);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), o);
                if (bitmap != null) runOnUiThread(() -> view.setImageBitmap(bitmap));
            } catch (Exception ignored) {
                // No screenshot: the grey box stays.
            }
        }, "theme-shot").start();
    }

    // ---- apply ------------------------------------------------------------------------------------

    /** Applies theme {@code name}, or with null goes back to the built-in look. */
    private void apply(String name) {
        File dir = Config.dir(this);
        if (dir == null) {
            Toast.makeText(this, "Folder tema tidak tersedia", Toast.LENGTH_LONG).show();
            return;
        }
        JSONObject files = null;
        if (name != null) {
            try {
                files = new JSONObject(prefs.getString(PREF_INDEX, "{}")).optJSONObject(name);
            } catch (Exception ignored) {
                files = null;
            }
            if (files == null) return;
        }
        final JSONObject themeFiles = files;
        busy = true;
        info.setText(name == null ? "Mengembalikan tampilan bawaan…" : "Memasang " + pretty(name) + "…");
        new Thread(() -> {
            String error = null;
            try {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File config = new File(dir, Config.FILE_NAME);
                JSONObject kept = new JSONObject();
                if (config.isFile()) {
                    try {
                        JSONObject old = new JSONObject(read(config));
                        for (String k : KEEP) if (old.has(k)) kept.put(k, old.get(k));
                    } catch (Exception ignored) {
                        // An unreadable config keeps nothing.
                    }
                }
                JSONObject next = name == null ? new JSONObject()
                        : new JSONObject(HomeWidgets.get(RAW + "themes/" + name + "/config.json"));
                for (Iterator<String> it = kept.keys(); it.hasNext(); ) {
                    String k = it.next();
                    next.put(k, kept.get(k));
                }

                // Wallpapers first, the config last: Home never reads a config whose images are missing.
                File wallpaper = new File(dir, "wallpaper.jpg");
                if (themeFiles != null && themeFiles.has("wallpaper.jpg")) {
                    say("Mengunduh wallpaper…");
                    download(RAW + "themes/" + name + "/wallpaper.jpg", wallpaper, 16 << 20);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    wallpaper.delete();
                }
                File walls = new File(dir, ThemeFiles.WALLPAPERS);
                File[] old = walls.listFiles();
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                if (themeFiles != null) {
                    List<String> list = new ArrayList<>();
                    for (Iterator<String> it = themeFiles.keys(); it.hasNext(); ) {
                        String f = it.next();
                        if (f.startsWith("wallpapers/")) list.add(f);
                    }
                    for (int i = 0; i < list.size(); i++) {
                        say("Mengunduh wallpaper " + (i + 1) + "/" + list.size() + "…");
                        //noinspection ResultOfMethodCallIgnored
                        walls.mkdirs();
                        download(RAW + "themes/" + name + "/" + list.get(i),
                                new File(walls, list.get(i).substring("wallpapers/".length())), 16 << 20);
                    }
                }

                if (next.length() == 0) {
                    //noinspection ResultOfMethodCallIgnored
                    config.delete();
                } else {
                    write(config, next.toString(2));
                }
                // A new revision tells Config the theme changed, so on-TV layout choices give way to it,
                // as after tools/theme.sh.
                write(new File(dir, "theme_revision"), UUID.randomUUID().toString());
            } catch (Exception e) {
                error = "Gagal memasang tema (cek internet).";
            }
            final String message = error;
            runOnUiThread(() -> {
                busy = false;
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    render();
                    return;
                }
                SharedPreferences.Editor edit = prefs.edit();
                if (name == null) edit.remove(PREF_APPLIED);
                else edit.putString(PREF_APPLIED, name);
                // Home rebuilds when it next comes to the front.
                edit.putLong(Config.SETTINGS_REVISION_KEY, prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1);
                edit.apply();
                setResult(RESULT_OK);
                Toast.makeText(this, name == null ? "Tampilan bawaan dikembalikan" : "Tema " + pretty(name) + " terpasang",
                        Toast.LENGTH_SHORT).show();
                render();
            });
        }, "theme-apply").start();
    }

    private void say(String text) {
        runOnUiThread(() -> info.setText(text));
    }

    private static String pretty(String name) {
        String s = name.replace('-', ' ').replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (!tmp.renameTo(f)) throw new java.io.IOException("rename " + f);
    }

    private static void download(String url, File target, int limit) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        target.getParentFile().mkdirs();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "cleanyourtv-themes");
        File tmp = new File(target.getPath() + ".part");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] b = new byte[16384];
                int n, total = 0;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > limit) throw new java.io.IOException("too large");
                    out.write(b, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!tmp.renameTo(target)) throw new java.io.IOException("rename " + target);
        } finally {
            c.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
