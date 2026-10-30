package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

/** Launcher settings: how to get a free Gemini API key, entering it, checking it, removing it. */
final class GeminiSetup {

    private GeminiSetup() {
    }

    /** Shown when Gemini is chosen without a key: the steps, then the key field. */
    static void intro(Activity activity, Runnable onDone) {
        new AlertDialog.Builder(activity)
                .setTitle("Pakai Gemini untuk jadwal")
                .setMessage("Gemini butuh API key gratis milikmu sendiri:\n\n"
                        + "1. Di HP atau laptop, buka aistudio.google.com/apikey\n"
                        + "2. Login dengan akun Google mana saja\n"
                        + "3. Tekan \"Create API key\", lalu salin key-nya (diawali AIza…)\n"
                        + "4. Kembali ke sini, pilih Masukkan key, lalu tempel\n\n"
                        + "Tips: key-nya panjang. Pakai aplikasi Google TV di HP sebagai remote, "
                        + "lalu ketik atau tempel lewat keyboard HP.\n\n"
                        + "Jadwal ditanyakan sekali sehari dan setelah tiap laga selesai, jadi kuota gratisnya lebih dari cukup.")
                .setPositiveButton("Masukkan key", (d, w) -> enter(activity, onDone))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The key field; the key is checked before it is saved and Gemini becomes the source. */
    static void enter(Activity activity, Runnable onDone) {
        EditText field = new EditText(activity);
        field.setHint("AIza…");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(field);
        new AlertDialog.Builder(activity)
                .setTitle("Key Gemini")
                .setView(box)
                .setPositiveButton("Simpan", (d, w) -> check(activity, field.getText().toString().trim(), onDone))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        field.requestFocus();
    }

    /** The key row: change or remove a saved key. */
    static void manage(Activity activity, Runnable onDone) {
        String key = FootballSource.prefs(activity).getString(FootballSource.PREF_GEMINI_KEY, "");
        if (key.isEmpty()) {
            intro(activity, onDone);
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("Key Gemini")
                .setMessage("Key tersimpan: …" + key.substring(Math.max(0, key.length() - 4))
                        + "\nModel: " + FootballSource.prefs(activity).getString(FootballSource.PREF_GEMINI_MODEL,
                        FootballSource.DEFAULT_MODEL))
                .setPositiveButton("Ganti key", (d, w) -> enter(activity, onDone))
                .setNeutralButton("Hapus key", (d, w) -> {
                    FootballSource.prefs(activity).edit().remove(FootballSource.PREF_GEMINI_KEY)
                            .remove(FootballSource.PREF_GEMINI_MODEL)
                            .putString(FootballSource.PREF_SOURCE, FootballSource.FOTMOB).apply();
                    HomeWidgets.forgetMatches(activity);
                    Toast.makeText(activity, "Key dihapus. Sumber jadwal kembali ke FotMob.", Toast.LENGTH_LONG).show();
                    onDone.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void check(Activity activity, String key, Runnable onDone) {
        if (!key.matches("[A-Za-z0-9_-]{20,}")) {
            Toast.makeText(activity, "Key-nya kelihatan belum lengkap", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, "Memeriksa key…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            String model = null;
            String error = null;
            try {
                model = FootballSource.checkKey(key);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String found = model;
            final String message = error;
            main.post(() -> {
                if (activity.isFinishing()) return;
                if (found == null) {
                    Toast.makeText(activity, message == null ? "Key tidak bisa dipakai" : message, Toast.LENGTH_LONG).show();
                    return;
                }
                FootballSource.prefs(activity).edit().putString(FootballSource.PREF_GEMINI_KEY, key)
                        .putString(FootballSource.PREF_GEMINI_MODEL, found)
                        .putString(FootballSource.PREF_SOURCE, FootballSource.GEMINI).apply();
                HomeWidgets.forgetMatches(activity);
                Toast.makeText(activity, "Key berhasil · " + found + ". Jadwal diambil lewat Gemini.",
                        Toast.LENGTH_LONG).show();
                onDone.run();
            });
        }, "gemini-key");
        t.setDaemon(true);
        t.start();
    }
}
