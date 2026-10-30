package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.List;

/**
 * Launcher settings → Tim favorit: type a club's name, pick it from FotMob's matches (name and league).
 * The FotMob id serves both sources: FotMob reads its fixtures by id, Gemini is asked by name and league.
 */
final class TeamPicker {

    private TeamPicker() {
    }

    static String currentName(Context context) {
        return FootballSource.teamName(context);
    }

    static void show(Activity activity, Runnable onChanged) {
        EditText name = new EditText(activity);
        name.setHint("Nama tim (contoh: Persib)");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        FrameLayout box = new FrameLayout(activity);
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(name);
        new AlertDialog.Builder(activity)
                .setTitle("Tim favorit")
                .setMessage("Ketik nama tim, lalu pilih dari daftar.")
                .setView(box)
                .setPositiveButton("Cari", (d, w) -> search(activity, name.getText().toString().trim(), onChanged))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        name.requestFocus();
    }

    private static void search(Activity activity, String term, Runnable onChanged) {
        if (term.length() < 2) {
            Toast.makeText(activity, "Isi nama tim dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(activity, "Mencari " + term + "…", Toast.LENGTH_SHORT).show();
        Handler main = new Handler(Looper.getMainLooper());
        Thread t = new Thread(() -> {
            List<String[]> teams = null;
            try {
                teams = FootballSource.searchTeams(term);
            } catch (Exception ignored) {
                teams = null;
            }
            final List<String[]> found = teams;
            main.post(() -> {
                if (activity.isFinishing()) return;
                if (found == null) {
                    Toast.makeText(activity, "Gagal mencari. Cek koneksi internet.", Toast.LENGTH_LONG).show();
                } else if (found.isEmpty()) {
                    Toast.makeText(activity, "Tim \"" + term + "\" tidak ditemukan", Toast.LENGTH_LONG).show();
                } else {
                    choose(activity, found, onChanged);
                }
            });
        }, "team-search");
        t.setDaemon(true);
        t.start();
    }

    private static void choose(Activity activity, List<String[]> teams, Runnable onChanged) {
        String[] labels = new String[teams.size()];
        for (int i = 0; i < labels.length; i++) {
            String[] team = teams.get(i);
            labels[i] = team[1] + (team[2].isEmpty() ? "" : "  ·  " + team[2]);
        }
        new AlertDialog.Builder(activity)
                .setTitle("Pilih tim")
                .setItems(labels, (d, which) -> {
                    String[] team = teams.get(which);
                    HomeWidgets.forgetMatches(activity);
                    FootballSource.prefs(activity).edit()
                            .putString(FootballSource.PREF_TEAM_ID, team[0])
                            .putString(FootballSource.PREF_TEAM_NAME, team[1])
                            .putString(FootballSource.PREF_TEAM_LEAGUE, team[2])
                            .apply();
                    Toast.makeText(activity, "Tim favorit: " + team[1], Toast.LENGTH_SHORT).show();
                    onChanged.run();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
