package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Adzan reminders (Launcher settings → Pengingat adzan). An alarm is set for the next enabled prayer;
 * when it fires, a banner shows over whatever is on screen, with no sound, a short chime, or the adzan
 * from a file the owner sends to the TV (adzan.mp3, and optionally adzan_subuh.mp3 for Subuh). Nothing
 * happens while the TV is in standby. Pressing Home stops a playing adzan.
 */
final class Adzan {

    static final String PREF_MODE = "adzan_mode";
    static final String PREF_PRAYER = "adzan_prayer_";   // + index 0..4 (Subuh..Isya), default on
    static final int OFF = 0, BANNER = 1, CHIME = 2, SOUND = 3;
    static final String[] MODES = {"Mati", "Banner saja", "Banner + nada", "Banner + suara adzan"};
    static final String FILE = "adzan.mp3";
    static final String FILE_SUBUH = "adzan_subuh.mp3";

    private static final String EXTRA_PRAYER = "prayer";
    private static final String EXTRA_AT = "at";
    private static final long BANNER_MS = 25_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static MediaPlayer player;
    private static AudioFocusRequest focus;
    private static Context audioContext;
    private static View banner;
    private static long lastScheduled;

    private Adzan() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static int mode(Context c) {
        return prefs(c).getInt(PREF_MODE, OFF);
    }

    static boolean enabled(Context c, int index) {
        return prefs(c).getBoolean(PREF_PRAYER + index, true);
    }

    static File file(Context c, boolean subuh) {
        File dir = Config.dir(c);
        if (dir == null) return null;
        File special = new File(dir, FILE_SUBUH);
        if (subuh && special.isFile()) return special;
        File f = new File(dir, FILE);
        return f.isFile() ? f : null;
    }

    static boolean canShowBanner(Context c) {
        return Settings.canDrawOverlays(c);
    }

    // ---- scheduling -------------------------------------------------------------------------------

    /** Sets (or clears) the alarm for the next enabled prayer. Cheap; skips when nothing changed. */
    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        Intent intent = new Intent(c, AdzanReceiver.class).setAction(AdzanReceiver.ACTION);
        if (mode(c) == OFF) {
            PendingIntent old = PendingIntent.getBroadcast(c, 0, intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (old != null) alarms.cancel(old);
            lastScheduled = 0;
            return;
        }
        SharedPreferences p = prefs(c);
        double lat = Double.longBitsToDouble(p.getLong(HomeWidgets.PREF_LAT, Double.doubleToLongBits(HomeWidgets.DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(p.getLong(HomeWidgets.PREF_LON, Double.doubleToLongBits(HomeWidgets.DEFAULT_LON)));
        double elev = Double.longBitsToDouble(p.getLong(HomeWidgets.PREF_ELEV, Double.doubleToLongBits(HomeWidgets.DEFAULT_ELEV)));
        ZonedDateTime now = ZonedDateTime.now();
        long at = 0;
        int which = -1;
        for (int day = 0; day < 2 && which < 0; day++) {
            LocalDate date = now.toLocalDate().plusDays(day);
            ZonedDateTime noon = date.atTime(12, 0).atZone(ZoneId.systemDefault());
            int[] times = PrayerTimes.compute(date, lat, lon, elev, noon.getOffset().getTotalSeconds() / 3600.0);
            for (int i = 0; i < PrayerTimes.PRAYERS.length; i++) {
                if (!enabled(c, i)) continue;
                int minute = times[PrayerTimes.PRAYERS[i]];
                ZonedDateTime t = LocalDateTime.of(date, java.time.LocalTime.of(minute / 60, minute % 60))
                        .atZone(ZoneId.systemDefault());
                if (t.isAfter(now)) {
                    at = t.toInstant().toEpochMilli();
                    which = i;
                    break;
                }
            }
        }
        if (which < 0) return;
        if (at == lastScheduled) return;
        intent.putExtra(EXTRA_PRAYER, which).putExtra(EXTRA_AT, at);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            // Without the exact-alarm permission: within a minute of the time.
            alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000, pi);
        }
        lastScheduled = at;
    }

    /** The alarm went off: announce if it is still on time, then set the next one. */
    static void fired(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        lastScheduled = 0;
        schedule(c);
        int index = intent.getIntExtra(EXTRA_PRAYER, -1);
        long at = intent.getLongExtra(EXTRA_AT, 0);
        int mode = mode(c);
        if (index < 0 || index > 4 || mode == OFF || !enabled(c, index)) return;
        if (Math.abs(System.currentTimeMillis() - at) > 10 * 60_000L) return;   // a stale alarm after a long sleep
        android.os.PowerManager power = c.getSystemService(android.os.PowerManager.class);
        if (power != null && !power.isInteractive()) return;                     // TV in standby
        announce(c, index, mode);
    }

    /** Shows the banner and plays the chosen sound now (also used by the settings' test row). */
    static void announce(Context c, int index, int mode) {
        String name = PrayerTimes.NAMES[PrayerTimes.PRAYERS[index]];
        boolean sound = false;
        if (mode == SOUND) {
            File f = file(c, index == 0);
            sound = f != null && play(c, f);
            if (!sound) chime(c);   // no file yet: at least a chime
        } else if (mode == CHIME) {
            chime(c);
        }
        if (mode != OFF) showBanner(c, index, name, sound);
    }

    // ---- sound ------------------------------------------------------------------------------------

    private static boolean play(Context c, File f) {
        stop();
        try {
            AudioAttributes attrs = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            AudioManager audio = c.getSystemService(AudioManager.class);
            audioContext = c.getApplicationContext();
            if (audio != null) {
                // Most players pause while the adzan plays and carry on after it.
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build();
                audio.requestAudioFocus(focus);
            }
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(attrs);
            mp.setDataSource(f.getPath());
            mp.setOnCompletionListener(m -> stop());
            mp.setOnErrorListener((m, w, e) -> {
                stop();
                return true;
            });
            mp.prepare();
            mp.start();
            player = mp;
            return true;
        } catch (Exception e) {
            stop();
            return false;
        }
    }

    private static void chime(Context c) {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 85);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400);
            MAIN.postDelayed(() -> tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400), 900);
            MAIN.postDelayed(tone::release, 2500);
        } catch (RuntimeException ignored) {
            // No tone generator on this firmware: the banner alone.
        }
    }

    /** Stops a playing adzan (Home pressed, or the file ended). */
    static void stop() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
            mp.release();
        }
        if (focus != null && audioContext != null) {
            AudioManager audio = audioContext.getSystemService(AudioManager.class);
            if (audio != null) audio.abandonAudioFocusRequest(focus);
        }
        focus = null;
        hideBanner();
    }

    static boolean playing() {
        return player != null;
    }

    // ---- banner -----------------------------------------------------------------------------------

    private static void showBanner(Context c, int index, String name, boolean sound) {
        if (!canShowBanner(c)) return;
        hideBanner();
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (18 * d);
        box.setPadding(pad, (int) (14 * d), (int) (24 * d), (int) (14 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6121418);
        bg.setCornerRadius(20 * d);
        bg.setStroke((int) d, 0x557FE0A0);
        box.setBackground(bg);

        ImageView icon = new ImageView(c);
        int[] icons = {R.drawable.ic_prayer_sunrise, R.drawable.ic_prayer_sun, R.drawable.ic_prayer_sun,
                R.drawable.ic_prayer_moon, R.drawable.ic_prayer_moon};
        int[] tints = {0xFFE6E6EA, 0xFFFFC83D, 0xFFFF9A3C, 0xFFFF5A5A, 0xFF7C86FF};
        icon.setImageResource(icons[index]);
        icon.setColorFilter(tints[index]);
        box.addView(icon, new LinearLayout.LayoutParams((int) (34 * d), (int) (34 * d)));

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = (int) (14 * d);
        box.addView(text, tp);
        TextView title = new TextView(c);
        title.setText("Waktunya " + name);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        text.addView(title);
        TextView sub = new TextView(c);
        String place = prefs(c).getString(HomeWidgets.PREF_NAME, HomeWidgets.DEFAULT_NAME);
        String time = ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH.mm", Locale.ROOT));
        sub.setText(place + " · " + time + (sound ? "\nTekan Home untuk menghentikan adzan" : ""));
        sub.setTextColor(0xFFACB8C1);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.addView(sub);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = (int) (40 * d);
        lp.y = (int) (32 * d);
        try {
            c.getSystemService(WindowManager.class).addView(box, lp);
            banner = box;
            View shown = box;
            MAIN.postDelayed(() -> {
                if (banner == shown && player == null) hideBanner();
            }, BANNER_MS);
            // While the adzan plays the banner stays; it goes when the sound stops.
        } catch (RuntimeException ignored) {
            // Overlay refused on this firmware: the sound still plays.
        }
    }

    private static void hideBanner() {
        View b = banner;
        banner = null;
        if (b == null) return;
        try {
            b.getContext().getSystemService(WindowManager.class).removeView(b);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
    }
}
