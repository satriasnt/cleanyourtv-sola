package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Launcher settings: type a postal code, pick the village (desa/kelurahan) from the list, and its
 * coordinates become the prayer-time location. The lookup uses the free kodepos API once; the times
 * themselves are computed on the TV.
 */
final class PrayerLocationPicker {

    private PrayerLocationPicker() {
    }

    /** The saved place's name, for the settings row. */
    static String currentName(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString(HomeWidgets.PREF_NAME, HomeWidgets.DEFAULT_NAME);
    }

    static void show(Activity activity, Runnable onChanged) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Contoh: 40294");
        input.setSingleLine(true);
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(activity)
                .setTitle("Lokasi jadwal sholat")
                .setMessage("Masukkan kode pos, lalu pilih desa atau kelurahan.")
                .setView(box)
                .setPositiveButton("Cari", (d, w) -> search(activity, input.getText().toString().trim(), onChanged))
                .setNeutralButton("Pakai Kota Bandung", (d, w) -> {
                    save(activity, HomeWidgets.DEFAULT_NAME, HomeWidgets.DEFAULT_LAT, HomeWidgets.DEFAULT_LON,
                            HomeWidgets.DEFAULT_ELEV);
                    onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
    }

    private static final class Place {
        final String label;
        final String place;
        final double lat, lon, elev;

        Place(String label, String place, double lat, double lon, double elev) {
            this.label = label;
            this.place = place;
            this.lat = lat;
            this.lon = lon;
            this.elev = elev;
        }
    }

    private static void search(Activity activity, String code, Runnable onChanged) {
        if (!code.matches("\\d{5}")) {
            Toast.makeText(activity, "Kode pos harus 5 angka", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "Mencari " + code + "…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            List<Place> places = new ArrayList<>();
            String error = null;
            try {
                JSONObject reply = new JSONObject(HomeWidgets.get("https://kodepos.vercel.app/search/?q=" + code));
                JSONArray data = reply.optJSONArray("data");
                for (int i = 0; data != null && i < data.length() && places.size() < 60; i++) {
                    JSONObject p = data.getJSONObject(i);
                    if (!code.equals(String.valueOf(p.optInt("code")))) continue;
                    if (!p.has("latitude") || !p.has("longitude")) continue;
                    String village = p.optString("village");
                    String label = village + ", " + p.optString("district") + ", " + p.optString("regency");
                    String regency = p.optString("regency");
                    String place = regency.isEmpty() ? village : village + ", " + regency;
                    places.add(new Place(label, place, p.getDouble("latitude"), p.getDouble("longitude"),
                            p.optDouble("elevation", 0)));
                }
            } catch (Exception e) {
                error = "Gagal mengambil data. Cek koneksi internet.";
            }
            final String message = error;
            main.post(() -> {
                if (activity.isFinishing()) return;
                if (message != null) {
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                } else if (places.isEmpty()) {
                    Toast.makeText(activity, "Kode pos " + code + " tidak ditemukan", Toast.LENGTH_LONG).show();
                } else {
                    choose(activity, code, places, onChanged);
                }
            });
        }, "kodepos");
        t.setDaemon(true);
        t.start();
    }

    private static void choose(Activity activity, String code, List<Place> places, Runnable onChanged) {
        String[] labels = new String[places.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = places.get(i).label;
        new AlertDialog.Builder(activity)
                .setTitle("Pilih desa/kelurahan (" + code + ")")
                .setItems(labels, (d, which) -> {
                    Place p = places.get(which);
                    save(activity, p.place, p.lat, p.lon, Double.isNaN(p.elev) ? 0 : p.elev);
                    Toast.makeText(activity, "Lokasi sholat: " + p.place, Toast.LENGTH_SHORT).show();
                    onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void save(Context context, String name, double lat, double lon, double elev) {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(HomeWidgets.PREF_NAME, name)
                .putLong(HomeWidgets.PREF_LAT, Double.doubleToLongBits(lat))
                .putLong(HomeWidgets.PREF_LON, Double.doubleToLongBits(lon))
                .putLong(HomeWidgets.PREF_ELEV, Double.doubleToLongBits(elev))
                .apply();
        Adzan.schedule(context);
    }
}
