package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    static final String EXTRA_REORDER = "com.gathin.tvhome.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView themeChoice;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        themeChoice = findViewById(R.id.themeChoice);
        themeChoice.setOnClickListener(v -> startActivity(new Intent(this, ThemePickerActivity.class)));
        setUpWidgets();
        TextView prayerLocation = findViewById(R.id.prayerLocationChoice);
        prayerLocation.setText("Lokasi jadwal sholat: " + PrayerLocationPicker.currentName(this));
        prayerLocation.setOnClickListener(v -> PrayerLocationPicker.show(this, () ->
                prayerLocation.setText("Lokasi jadwal sholat: " + PrayerLocationPicker.currentName(this))));
        setUpPersonal();
        setUpAdzan();
        setUpFootball();
        TextView team = findViewById(R.id.teamChoice);
        team.setText("Tim favorit: " + TeamPicker.currentName(this));
        team.setOnClickListener(v -> TeamPicker.show(this, () ->
                team.setText("Tim favorit: " + TeamPicker.currentName(this))));
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        refresh();
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == images.size()) {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, "none").apply();
                    } else {
                        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY,
                                images.get(which).getName()).apply();
                    }
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    /** Widget Home: the cards' look, and which cards show. */
    private void setUpWidgets() {
        TextView style = findViewById(R.id.widgetStyleChoice);
        TextView shade = findViewById(R.id.widgetShadeChoice);
        Runnable labels = () -> {
            int i = WidgetStyle.index(choices.getString(WidgetStyle.PREF_STYLE, WidgetStyle.BLUR));
            style.setText("Gaya kartu: " + WidgetStyle.STYLE_NAMES[i]);
            shade.setText("Ketebalan latar: " + WidgetStyle.SHADE_NAMES[Math.max(0, Math.min(2,
                    choices.getInt(WidgetStyle.PREF_SHADE, 1)))]);
        };
        labels.run();
        style.setOnClickListener(v -> {
            int i = WidgetStyle.index(choices.getString(WidgetStyle.PREF_STYLE, WidgetStyle.BLUR));
            choices.edit().putString(WidgetStyle.PREF_STYLE, WidgetStyle.STYLES[(i + 1) % WidgetStyle.STYLES.length]).apply();
            labels.run();
        });
        shade.setOnClickListener(v -> {
            choices.edit().putInt(WidgetStyle.PREF_SHADE, (choices.getInt(WidgetStyle.PREF_SHADE, 1) + 1) % 3).apply();
            labels.run();
        });
        cardSwitch(R.id.showPrayerChoice, HomeWidgets.PREF_SHOW_PRAYER, "Kartu jadwal sholat");
        cardSwitch(R.id.calendarChoice, HomeWidgets.PREF_SHOW_CALENDAR, "Kartu kalender");
        cardSwitch(R.id.showFootballChoice, HomeWidgets.PREF_SHOW_FOOTBALL, "Kartu jadwal bola");
    }

    private void cardSwitch(int id, String pref, String label) {
        TextView row = findViewById(id);
        Runnable text = () -> row.setText(label + ": " + (choices.getBoolean(pref, true) ? "Tampil" : "Sembunyi"));
        text.run();
        row.setOnClickListener(v -> {
            choices.edit().putBoolean(pref, !choices.getBoolean(pref, true)).apply();
            changed();   // Home rebuilds: the dock moves when the row comes or goes
            text.run();
        });
    }

    private TextView tileMenuChoice;
    private TextView appLibraryChoice;

    /** Menu tile and App library: switches kept in the TV's settings, so no theme or config push clears them. */
    private void setUpPersonal() {
        tileMenuChoice = findViewById(R.id.tileMenuChoice);
        appLibraryChoice = findViewById(R.id.appLibraryChoice);
        tileMenuChoice.setOnClickListener(v -> {
            choices.edit().putBoolean(Config.PREF_TILE_MENU, !Config.load(this).tileMenu).apply();
            changed();
            refreshPersonal();
        });
        appLibraryChoice.setOnClickListener(v -> {
            choices.edit().putBoolean(Config.PREF_APP_LIBRARY, !Config.load(this).appLibrary).apply();
            changed();
            refreshPersonal();
        });
        refreshPersonal();
    }

    private void refreshPersonal() {
        Config now = Config.load(this);
        tileMenuChoice.setText("Menu tile (tahan OK: pindah, sembunyikan): " + (now.tileMenu ? "Aktif" : "Mati"));
        appLibraryChoice.setText("App library (daftar semua aplikasi): " + (now.appLibrary ? "Aktif" : "Mati"));
    }

    private TextView sourceChoice;
    private TextView geminiKeyChoice;
    private TextView footballHint;

    private void setUpFootball() {
        sourceChoice = findViewById(R.id.sourceChoice);
        geminiKeyChoice = findViewById(R.id.geminiKeyChoice);
        footballHint = findViewById(R.id.footballHint);
        Runnable refresh = this::refreshFootball;
        sourceChoice.setOnClickListener(v -> {
            if (FootballSource.GEMINI.equals(FootballSource.source(this))) {
                choices.edit().putString(FootballSource.PREF_SOURCE, FootballSource.FOTMOB).apply();
                HomeWidgets.forgetMatches(this);
                refreshFootball();
            } else if (choices.getString(FootballSource.PREF_GEMINI_KEY, "").isEmpty()) {
                GeminiSetup.intro(this, refresh);
            } else {
                choices.edit().putString(FootballSource.PREF_SOURCE, FootballSource.GEMINI).apply();
                HomeWidgets.forgetMatches(this);
                refreshFootball();
            }
        });
        geminiKeyChoice.setOnClickListener(v -> GeminiSetup.manage(this, refresh));
        findViewById(R.id.footballRefresh).setOnClickListener(v -> {
            HomeWidgets.forgetMatches(this);
            Toast.makeText(this, "Jadwal diambil ulang saat kembali ke Home", Toast.LENGTH_SHORT).show();
            refreshFootball();
        });
        refreshFootball();
    }

    private void refreshFootball() {
        boolean gemini = FootballSource.GEMINI.equals(FootballSource.source(this));
        sourceChoice.setText("Sumber jadwal: " + (gemini ? "Gemini" : "FotMob"));
        String key = choices.getString(FootballSource.PREF_GEMINI_KEY, "");
        geminiKeyChoice.setText("Key Gemini: " + (key.isEmpty() ? "belum ada" : "tersimpan (…"
                + key.substring(Math.max(0, key.length() - 4)) + ")"));
        long at = HomeWidgets.fetchedAt(this);
        String error = choices.getString(FootballSource.PREF_ERROR, "");
        StringBuilder hint = new StringBuilder(at == 0 ? "Jadwal belum diambil."
                : "Jadwal terakhir diambil " + java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMM, HH.mm", new java.util.Locale("id", "ID"))) + ".");
        if (!error.isEmpty()) hint.append("\nPercobaan terakhir gagal: ").append(error);
        if (gemini) hint.append("\nGemini menjawab berdasarkan pencarian Google; sesekali bisa keliru.");
        footballHint.setText(hint);
    }

    private TextView adzanChoice;
    private TextView adzanHint;
    private final TextView[] adzanPrayers = new TextView[5];

    private void setUpAdzan() {
        adzanChoice = findViewById(R.id.adzanChoice);
        adzanHint = findViewById(R.id.adzanHint);
        adzanChoice.setOnClickListener(v -> {
            choices.edit().putInt(Adzan.PREF_MODE, (Adzan.mode(this) + 1) % Adzan.MODES.length).apply();
            Adzan.schedule(this);
            refreshAdzan();
        });
        int[] ids = {R.id.adzan0, R.id.adzan1, R.id.adzan2, R.id.adzan3, R.id.adzan4};
        for (int i = 0; i < ids.length; i++) {
            final int index = i;
            adzanPrayers[i] = findViewById(ids[i]);
            adzanPrayers[i].setOnClickListener(v -> {
                choices.edit().putBoolean(Adzan.PREF_PRAYER + index, !Adzan.enabled(this, index)).apply();
                Adzan.schedule(this);
                refreshAdzan();
            });
        }
        findViewById(R.id.adzanTest).setOnClickListener(v -> {
            int mode = Adzan.mode(this);
            Adzan.announce(this, 3, mode == Adzan.OFF ? Adzan.BANNER : mode);
            if (!Adzan.canShowBanner(this)) {
                Toast.makeText(this, "Banner belum bisa tampil: izin tampil di atas aplikasi belum diberikan",
                        Toast.LENGTH_LONG).show();
            }
        });
        refreshAdzan();
    }

    private void refreshAdzan() {
        int mode = Adzan.mode(this);
        adzanChoice.setText("Pengingat adzan: " + Adzan.MODES[mode]);
        for (int i = 0; i < adzanPrayers.length; i++) {
            boolean on = Adzan.enabled(this, i);
            adzanPrayers[i].setText(PrayerTimes.NAMES[PrayerTimes.PRAYERS[i]] + (on ? " ✓" : ""));
            adzanPrayers[i].setSelected(on);
            adzanPrayers[i].setAlpha(mode == Adzan.OFF ? 0.5f : 1f);
        }
        StringBuilder hint = new StringBuilder("Pilih waktu sholat yang diingatkan. Tekan Home untuk menghentikan adzan.");
        if (mode != Adzan.OFF && !Adzan.canShowBanner(this)) {
            hint.append("\nIzin \"tampil di atas aplikasi lain\" belum diberikan, jadi banner belum bisa muncul.");
        }
        if (mode == Adzan.SOUND) {
            hint.append(Adzan.file(this, false) != null ? "\nFile adzan.mp3 ditemukan."
                    : "\nFile adzan.mp3 belum ada di TV; sementara diganti nada singkat.");
            if (Adzan.file(this, true) != null && Adzan.file(this, true).getName().equals(Adzan.FILE_SUBUH)) {
                hint.append(" Subuh memakai adzan_subuh.mp3.");
            }
        }
        adzanHint.setText(hint);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adzanChoice != null) refreshAdzan();
        if (sourceChoice != null) refreshFootball();
        String applied = choices.getString(ThemePickerActivity.PREF_APPLIED, "");
        themeChoice.setText("Tema: " + (applied.isEmpty() ? "Bawaan" : applied));
    }

    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
