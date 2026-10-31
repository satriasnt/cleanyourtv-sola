package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.view.ViewGroup;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The row of three widgets above the dock: the next prayer, an iOS-style month calendar with the Hijri
 * date, and the favourite football team's next match. Prayer times and the calendar are computed on the
 * TV; only the match list is fetched (TheSportsDB, a few times a day, while Home is in front).
 */
final class HomeWidgets {

    // Prayer location, chosen in Launcher settings (PrayerLocationPicker). Default: Kota Bandung.
    static final String PREF_LAT = "prayer_lat";
    static final String PREF_LON = "prayer_lon";
    static final String PREF_ELEV = "prayer_elev";
    static final String PREF_NAME = "prayer_name";
    static final double DEFAULT_LAT = -6.9175;
    static final double DEFAULT_LON = 107.6191;
    static final double DEFAULT_ELEV = 768;
    static final String DEFAULT_NAME = "Kota Bandung";

    private static final Locale ID = new Locale("id", "ID");
    private static final String[] HIJRI_MONTHS = {"Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir",
            "Jumadil Awal", "Jumadil Akhir", "Rajab", "Sya'ban", "Ramadhan", "Syawal", "Dzulqa'dah", "Dzulhijjah"};
    private static final String[] DAYS = {"SENIN", "SELASA", "RABU", "KAMIS", "JUMAT", "SABTU", "MINGGU"};

    private final Activity activity;
    private final View row;
    private final SharedPreferences prefs;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "home-widgets");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean fetching;
    private boolean faded;

    private final TextView prayerPlace, prayerDate, prayerCountdown;
    private final View[] prayerCols = new View[5];
    private final android.widget.ImageView[] prayerIcons = new android.widget.ImageView[5];
    private final TextView[] prayerNames = new TextView[5];
    private final TextView[] prayerTimes = new TextView[5];
    private final TextView calWeekday, calDay, calHijri;
    private final MonthView calMonth;
    private final TextView ballKicker, ballEmpty;
    private final android.widget.ImageView ballTeamLogo;
    private final View[] ballRows = new View[4];
    private final TextView[] ballDates = new TextView[4], ballOpponents = new TextView[4], ballTags = new TextView[4];
    private final android.widget.ImageView[] ballLogos = new android.widget.ImageView[4];
    private final View calCard, prayerCard, ballCard;

    /** The widget row, or null when home_widgets is off. */
    static HomeWidgets create(Activity activity) {
        Resources res = activity.getResources();
        View row = activity.findViewById(R.id.widgetRow);
        if (row == null) return null;
        SharedPreferences p = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        boolean any = p.getBoolean(PREF_SHOW_PRAYER, true) || p.getBoolean(PREF_SHOW_CALENDAR, true)
                || p.getBoolean(PREF_SHOW_FOOTBALL, true);
        if (!res.getBoolean(R.bool.home_widgets) || !any) {
            // Every card switched off: no row at all, and the dock sits where the original launcher has it.
            row.setVisibility(View.GONE);
            return null;
        }
        return new HomeWidgets(activity, row);
    }

    private HomeWidgets(Activity activity, View row) {
        this.activity = activity;
        this.row = row;
        Resources res = activity.getResources();
        this.prefs = activity.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        this.density = res.getDisplayMetrics().density;
        prayerPlace = row.findViewById(R.id.prayerPlace);
        prayerDate = row.findViewById(R.id.prayerDate);
        prayerCountdown = row.findViewById(R.id.prayerCountdown);
        int[] cols = {R.id.prayerCol0, R.id.prayerCol1, R.id.prayerCol2, R.id.prayerCol3, R.id.prayerCol4};
        int[] icons = {R.id.prayerIcon0, R.id.prayerIcon1, R.id.prayerIcon2, R.id.prayerIcon3, R.id.prayerIcon4};
        int[] names = {R.id.prayerName0, R.id.prayerName1, R.id.prayerName2, R.id.prayerName3, R.id.prayerName4};
        int[] times = {R.id.prayerTime0, R.id.prayerTime1, R.id.prayerTime2, R.id.prayerTime3, R.id.prayerTime4};
        for (int i = 0; i < 5; i++) {
            prayerCols[i] = row.findViewById(cols[i]);
            prayerIcons[i] = row.findViewById(icons[i]);
            prayerNames[i] = row.findViewById(names[i]);
            prayerTimes[i] = row.findViewById(times[i]);
        }
        calWeekday = row.findViewById(R.id.calWeekday);
        calDay = row.findViewById(R.id.calDay);
        calHijri = row.findViewById(R.id.calHijri);
        calMonth = row.findViewById(R.id.calMonth);
        ballKicker = row.findViewById(R.id.ballKicker);
        ballEmpty = row.findViewById(R.id.ballEmpty);
        ballTeamLogo = row.findViewById(R.id.ballTeamLogo);
        int[][] ids = {{R.id.ballRow0, R.id.ballDate0, R.id.ballLogo0, R.id.ballOpponent0, R.id.ballTag0},
                {R.id.ballRow1, R.id.ballDate1, R.id.ballLogo1, R.id.ballOpponent1, R.id.ballTag1},
                {R.id.ballRow2, R.id.ballDate2, R.id.ballLogo2, R.id.ballOpponent2, R.id.ballTag2},
                {R.id.ballRow3, R.id.ballDate3, R.id.ballLogo3, R.id.ballOpponent3, R.id.ballTag3}};
        for (int i = 0; i < 4; i++) {
            ballRows[i] = row.findViewById(ids[i][0]);
            ballDates[i] = row.findViewById(ids[i][1]);
            ballLogos[i] = row.findViewById(ids[i][2]);
            ballOpponents[i] = row.findViewById(ids[i][3]);
            ballTags[i] = row.findViewById(ids[i][4]);
        }
        calCard = row.findViewById(R.id.calCard);
        prayerCard = row.findViewById(R.id.prayerCard);
        ballCard = row.findViewById(R.id.ballCard);
        shadowTexts(row, density);
        row.setVisibility(View.VISIBLE);
    }

    /** Called on every return to Home and once a minute with the clock. */
    void update() {
        ZonedDateTime now = ZonedDateTime.now();
        boolean prayer = prefs.getBoolean(PREF_SHOW_PRAYER, true);
        boolean football = prefs.getBoolean(PREF_SHOW_FOOTBALL, true);
        prayerCard.setVisibility(prayer ? View.VISIBLE : View.GONE);
        ballCard.setVisibility(football ? View.VISIBLE : View.GONE);
        if (prayer) {
            try {
                updatePrayer(now);
            } catch (RuntimeException e) {
                prayerCountdown.setText("Jadwal sholat");
            }
        }
        updateCalendar(now);
        if (football) {
            updateFootball(now.toInstant());
            maybeFetch(now.toInstant());   // a hidden card fetches nothing
        }
        arrangeCards();
        Adzan.schedule(activity);          // reminders run whether or not the prayer card shows
        // The wallpaper may have moved on (rotation, theme): refresh the frosted glass after this layout.
        row.post(this::styleCards);
        row.postDelayed(this::styleCards, 1500);   // again once a newly loaded wallpaper is on screen
    }

    // ---- look -------------------------------------------------------------------------------------

    static final String PREF_SHOW_PRAYER = "widget_prayer";
    static final String PREF_SHOW_FOOTBALL = "widget_football";

    static final String PREF_STRETCH = "widget_stretch";
    static final String PREF_ALIGN = "widget_align";
    static final String PREF_ORDER = "widget_order";

    /** Kiri, tengah, kanan: where the cards sit when they are not stretched. */
    static final String[] ALIGN_NAMES = {"Kiri", "Tengah", "Kanan"};
    /** Every order of the three cards: p = jadwal sholat, k = kalender, b = jadwal bola. */
    static final String[] ORDERS = {"pkb", "pbk", "kpb", "kbp", "bpk", "bkp"};
    static final String DEFAULT_ORDER = "pkb";

    static String orderOf(SharedPreferences prefs) {
        String o = prefs.getString(PREF_ORDER, DEFAULT_ORDER);
        for (String known : ORDERS) if (known.equals(o)) return o;
        return DEFAULT_ORDER;
    }

    static String orderLabel(String order) {
        StringBuilder b = new StringBuilder();
        for (char c : order.toCharArray()) {
            if (b.length() > 0) b.append(" \u00B7 ");
            b.append(c == 'p' ? "Sholat" : c == 'k' ? "Kalender" : "Bola");
        }
        return b.toString();
    }

    /**
     * Puts the cards in the chosen order, then either stretches them over the row (the first visible one
     * at the row's edge, a gap before the others) or keeps each a third of the row wide, as if all three
     * showed, and slides the group left, to the middle or right.
     */
    private void arrangeCards() {
        android.widget.LinearLayout r = (android.widget.LinearLayout) row;
        String order = orderOf(prefs);
        View[] cards = new View[3];
        for (int i = 0; i < 3; i++) {
            char c = order.charAt(i);
            cards[i] = c == 'p' ? prayerCard : c == 'k' ? calCard : ballCard;
        }
        for (int i = 0; i < 3; i++) {
            if (r.getChildAt(i) != cards[i]) {
                r.removeView(cards[i]);
                r.addView(cards[i], i);
            }
        }
        boolean stretch = prefs.getBoolean(PREF_STRETCH, true);
        int align = Math.max(0, Math.min(2, prefs.getInt(PREF_ALIGN, 0)));
        int gap = (int) (14 * density);
        int rowWidth = r.getWidth() > 0 ? r.getWidth()
                : activity.getResources().getDisplayMetrics().widthPixels - (int) (132 * density);
        int third = Math.max(0, (rowWidth - 2 * gap) / 3);
        boolean first = true;
        for (View card : cards) {
            if (card.getVisibility() != View.VISIBLE) continue;
            android.widget.LinearLayout.LayoutParams lp = (android.widget.LinearLayout.LayoutParams) card.getLayoutParams();
            int start = first ? 0 : gap;
            int width = stretch ? 0 : third;
            float weight = stretch ? 1f : 0f;
            if (lp.getMarginStart() != start || lp.width != width || lp.weight != weight) {
                lp.setMarginStart(start);
                lp.width = width;
                lp.weight = weight;
                card.setLayoutParams(lp);
            }
            first = false;
        }
        int gravity = stretch || align == 0 ? android.view.Gravity.START
                : align == 1 ? android.view.Gravity.CENTER_HORIZONTAL : android.view.Gravity.END;
        if (r.getGravity() != (gravity | android.view.Gravity.TOP)) r.setGravity(gravity | android.view.Gravity.TOP);
    }

    private String lastStyleKey = "";

    /** Puts each visible card's background on: frosted glass, dark, light or chosen by the wallpaper. */
    private void styleCards() {
        if (row.getWidth() == 0 || activity.isFinishing()) return;
        String style = prefs.getString(WidgetStyle.PREF_STYLE, WidgetStyle.BLUR);
        int shade = prefs.getInt(WidgetStyle.PREF_SHADE, 1);
        ViewGroup root = (ViewGroup) row.getParent();
        boolean needsBackdrop = WidgetStyle.BLUR.equals(style) || WidgetStyle.AUTO.equals(style);
        Bitmap backdrop = needsBackdrop ? WidgetStyle.backdrop(root, row) : null;
        if (WidgetStyle.BLUR.equals(style) && backdrop == null) style = WidgetStyle.DARK;   // no snapshot here
        int[] rootAt = new int[2];
        root.getLocationInWindow(rootAt);
        for (View card : new View[]{prayerCard, calCard, ballCard}) {
            if (card.getVisibility() != View.VISIBLE || card.getWidth() == 0) continue;
            int[] at = new int[2];
            card.getLocationInWindow(at);
            float x = at[0] - rootAt[0], y = at[1] - rootAt[1];
            // Where the card sits, in root coordinates (the row's fade does not move it).
            RectF where = new RectF(x, y, x + card.getWidth(), y + card.getHeight());
            card.setBackground(WidgetStyle.background(card, style, shade, backdrop, where));
        }
        if (backdrop != null) backdropBitmap = backdrop;   // kept alive by the shaders; the old one is dropped
    }

    private Bitmap backdropBitmap;

    /** A soft shadow under every text on the cards, so they read on any wallpaper. */
    private static void shadowTexts(View v, float density) {
        if (v instanceof TextView) {
            ((TextView) v).setShadowLayer(3 * density, 0, density, 0x8C000000);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) shadowTexts(g.getChildAt(i), density);
        }
    }

    boolean anyVisible() {
        return row.getVisibility() == View.VISIBLE;
    }

    /** Fades the row out while the grid is scrolled down, so tiles never sit over it. */
    void onGridScrolled(int offsetPx) {
        boolean away = offsetPx > 4 * density;
        if (away == faded) return;
        faded = away;
        row.animate().cancel();
        row.animate().alpha(away ? 0f : 1f).setDuration(160).start();
    }

    /** Where the row's bottom edge sits, in root coordinates, once it is placed under the header. */
    int bottomInRoot() {
        return row.getVisibility() == View.VISIBLE ? (int) (row.getY() + row.getHeight()) : 0;
    }

    /** Places the row a little under the header (clock and top buttons). */
    void placeUnder(View header, View root) {
        int[] r = new int[2];
        int[] h = new int[2];
        root.getLocationInWindow(r);
        header.getLocationInWindow(h);
        float y = h[1] - r[1] + header.getHeight() + 14 * density;
        if (Math.abs(row.getY() - y) > 0.5f) row.setY(y);
    }

    /** The row's height in px, for the grid's top padding before it is laid out. */
    int heightPx() {
        return row.getLayoutParams().height > 0 ? row.getLayoutParams().height : row.getHeight();
    }

    void shutdown() {
        io.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    // ---- prayer -----------------------------------------------------------------------------------

    private static final int[] PRAYER_ICONS = {R.drawable.ic_prayer_sunrise, R.drawable.ic_prayer_sun,
            R.drawable.ic_prayer_sun, R.drawable.ic_prayer_moon, R.drawable.ic_prayer_moon};
    private static final int[] PRAYER_TINTS = {0xFFE6E6EA, 0xFFFFC83D, 0xFFFF9A3C, 0xFFFF5A5A, 0xFF7C86FF};

    private void updatePrayer(ZonedDateTime now) {
        double lat = Double.longBitsToDouble(prefs.getLong(PREF_LAT, Double.doubleToLongBits(DEFAULT_LAT)));
        double lon = Double.longBitsToDouble(prefs.getLong(PREF_LON, Double.doubleToLongBits(DEFAULT_LON)));
        double elev = Double.longBitsToDouble(prefs.getLong(PREF_ELEV, Double.doubleToLongBits(DEFAULT_ELEV)));
        String place = prefs.getString(PREF_NAME, DEFAULT_NAME);
        LocalDate today = now.toLocalDate();
        double tz = now.getOffset().getTotalSeconds() / 3600.0;
        int[] times = PrayerTimes.compute(today, lat, lon, elev, tz);
        int nowMin = now.getHour() * 60 + now.getMinute();

        int next = -1;
        for (int p : PrayerTimes.PRAYERS) {
            if (times[p] > nowMin) {
                next = p;
                break;
            }
        }
        int nextMinute;
        String nextName;
        if (next >= 0) {
            nextMinute = times[next];
            nextName = PrayerTimes.NAMES[next];
        } else {
            int[] tomorrow = PrayerTimes.compute(today.plusDays(1), lat, lon, elev, tz);
            nextMinute = tomorrow[PrayerTimes.SUBUH] + 1440;
            nextName = PrayerTimes.NAMES[PrayerTimes.SUBUH];
        }
        // The prayer whose time began most recently, for the "telah masuk" line.
        int current = -1;
        for (int p : PrayerTimes.PRAYERS) if (times[p] <= nowMin) current = p;

        prayerPlace.setText(place);
        prayerDate.setText(today.format(DateTimeFormatter.ofPattern("d MMM yyyy", ID)) + "  ·  " + hijriText(now, false));
        if (current >= 0 && nowMin - times[current] < 15) {
            prayerCountdown.setText("Telah masuk waktu " + PrayerTimes.NAMES[current]);
            prayerCountdown.setTextColor(0xFF7FE0A0);
        } else {
            SpannableStringBuilder line = new SpannableStringBuilder(nextName);
            line.setSpan(new ForegroundColorSpan(0xFFFFFFFF), 0, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            int s = line.length();
            line.append(" dalam ").append(duration(nextMinute - nowMin));
            line.setSpan(new ForegroundColorSpan(0xCCFFFFFF), s, line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            prayerCountdown.setText(line);
            prayerCountdown.setTextColor(0xFFFFFFFF);
        }

        // After Isya the next prayer is tomorrow's Subuh: highlight the Subuh column then.
        int highlight = next >= 0 ? next : PrayerTimes.SUBUH;
        for (int i = 0; i < PrayerTimes.PRAYERS.length; i++) {
            int p = PrayerTimes.PRAYERS[i];
            boolean isNext = p == highlight;
            boolean past = next >= 0 && times[p] <= nowMin;
            float alpha = isNext ? 1f : past ? 0.45f : 0.85f;
            prayerCols[i].setBackgroundResource(isNext ? R.drawable.prayer_next_bg : 0);
            prayerIcons[i].setImageResource(PRAYER_ICONS[i]);
            prayerIcons[i].setColorFilter(PRAYER_TINTS[i]);
            prayerIcons[i].setAlpha(alpha);
            prayerNames[i].setText(PrayerTimes.NAMES[p]);
            prayerNames[i].setAlpha(alpha);
            prayerTimes[i].setText(hhmm(times[p]));
            prayerTimes[i].setAlpha(alpha);
        }
    }

    /** "17 Jumadil Awal 1448 H", the day as the TV's clock reads it (Umm al-Qura). */
    private static String hijriText(ZonedDateTime now, boolean twoLines) {
        try {
            android.icu.util.IslamicCalendar hijri = new android.icu.util.IslamicCalendar();
            hijri.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
            hijri.setTime(java.util.Date.from(now.toLocalDate().atTime(LocalTime.NOON).atZone(now.getZone()).toInstant()));
            int d = hijri.get(android.icu.util.Calendar.DAY_OF_MONTH);
            int m = hijri.get(android.icu.util.Calendar.MONTH);
            int y = hijri.get(android.icu.util.Calendar.YEAR);
            return d + " " + HIJRI_MONTHS[Math.max(0, Math.min(11, m))] + (twoLines ? "\n" : " ") + y + " H";
        } catch (RuntimeException e) {
            return "";
        }
    }

    // ---- calendar ---------------------------------------------------------------------------------

    /** Launcher settings: show or hide the calendar card. */
    static final String PREF_SHOW_CALENDAR = "widget_calendar";

    private void updateCalendar(ZonedDateTime now) {
        boolean show = prefs.getBoolean(PREF_SHOW_CALENDAR, true);
        calCard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        LocalDate today = now.toLocalDate();
        calWeekday.setText(DAYS[today.getDayOfWeek().getValue() - 1]);
        calDay.setText(String.valueOf(today.getDayOfMonth()));
        calMonth.setDate(today);
        calHijri.setText(hijriText(now, true));
    }

    // ---- football ---------------------------------------------------------------------------------

    private static final String PREF_MATCHES = "football_events_v2";
    private static final String PREF_MATCHES_KEY = "football_events_key";
    private static final String PREF_FETCHED_AT = "football_fetched_v2";
    private static final String PREF_TRIED_AT = "football_tried_v2";
    /** FotMob: every 6 hours, and every 15 minutes around a match to follow the score. */
    private static final long FOTMOB_EVERY_MS = 6 * 3600_000L;
    private static final long NEAR_MATCH_MS = 15 * 60_000L;
    /** Gemini: once a day, and once after each match has ended (to pick up the result and what is next). */
    private static final long GEMINI_EVERY_MS = 24 * 3600_000L;
    private static final long RETRY_MS = 30 * 60_000L;
    private static final Duration LIVE = Duration.ofMinutes(115);
    /** How many lines the fixture list shows. */
    private static final int ROWS = 4;

    private static final class Match {
        final Instant start;
        final boolean home;          // the favourite team plays at home
        final boolean noTime;        // Gemini found the date but not the kick-off time
        final String homeName, awayName, homeBadge, awayBadge;
        final String league;
        final String score;

        Match(Instant start, boolean home, boolean noTime, String homeName, String awayName, String homeBadge,
              String awayBadge, String league, String score) {
            this.start = start;
            this.home = home;
            this.noTime = noTime;
            this.homeName = homeName;
            this.awayName = awayName;
            this.homeBadge = homeBadge;
            this.awayBadge = awayBadge;
            this.league = league;
            this.score = score;
        }

        String opponent() {
            return home ? awayName : homeName;
        }
    }

    /** Source and team the cache belongs to: a different pair means fetch again. */
    private String cacheKey() {
        return FootballSource.source(activity) + ":" + FootballSource.teamId(activity);
    }

    private List<Match> cachedMatches() {
        if (!cacheKey().equals(prefs.getString(PREF_MATCHES_KEY, ""))) return Collections.emptyList();
        return parse(prefs.getString(PREF_MATCHES, ""), FootballSource.teamId(activity));
    }

    /** Drops the cached fixtures; the next update fetches them again (team, source or key changed). */
    static void forgetMatches(Context context) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .remove(PREF_MATCHES).remove(PREF_MATCHES_KEY).remove(PREF_FETCHED_AT).remove(PREF_TRIED_AT)
                .remove(FootballSource.PREF_ERROR).apply();
    }

    /** When the fixtures were last fetched, for the settings screen (0: never). */
    static long fetchedAt(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getLong(PREF_FETCHED_AT, 0);
    }

    /** Shorter names for the competitions the card shows most. */
    static String shortLeague(String league) {
        String l = league.toLowerCase(Locale.ROOT);
        if (l.contains("super league") && !l.contains("chinese") || l.equals("liga 1") || l.contains("bri liga")
                || l.contains("liga super")) return "Super League";
        if (l.contains("champions league two")) return "ACL Two";
        if (l.contains("champions league elite")) return "ACL Elite";
        if (l.contains("asean club championship") || l.contains("shopee cup")) return "Shopee Cup";
        return league;
    }

    /** A finished match stays listed, with its score, until midnight after the day it ended. */
    private static boolean stillListed(Match m, Instant now) {
        Instant end = m.start.plus(LIVE);
        if (end.isAfter(now)) return true;
        java.time.ZonedDateTime midnight = end.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault());
        return midnight.toInstant().isAfter(now);
    }

    private void updateFootball(Instant now) {
        String teamId = FootballSource.teamId(activity);
        ballKicker.setText("JADWAL " + FootballSource.teamName(activity).toUpperCase(ID));
        Badges.show(ballTeamLogo, FootballSource.badge(teamId), io, main, false);
        List<Match> list = new ArrayList<>();
        for (Match m : cachedMatches()) {
            if (stillListed(m, now)) list.add(m);
        }
        if (list.isEmpty()) {
            boolean never = !cacheKey().equals(prefs.getString(PREF_MATCHES_KEY, ""));
            String error = prefs.getString(FootballSource.PREF_ERROR, "");
            for (View r : ballRows) r.setVisibility(View.GONE);
            ballEmpty.setVisibility(View.VISIBLE);
            ballEmpty.setText(!error.isEmpty() && never ? error : never ? "Memuat jadwal…" : "Jadwal berikutnya belum rilis");
            return;
        }
        ballEmpty.setVisibility(View.GONE);
        DateTimeFormatter withTime = DateTimeFormatter.ofPattern("EEE d/M HH.mm", ID);
        DateTimeFormatter dateOnly = DateTimeFormatter.ofPattern("EEE d/M", ID);
        for (int i = 0; i < ROWS; i++) {
            if (i >= list.size()) {
                ballRows[i].setVisibility(View.GONE);
                continue;
            }
            Match m = list.get(i);
            ballRows[i].setVisibility(View.VISIBLE);
            java.time.ZonedDateTime local = m.start.atZone(ZoneId.systemDefault());
            ballDates[i].setText(m.noTime ? local.format(dateOnly) + " –" : local.format(withTime));
            ballOpponents[i].setText((m.home ? "vs " : "@ ") + m.opponent());
            Badges.show(ballLogos[i], m.home ? m.awayBadge : m.homeBadge, io, main, true);
            boolean over = !m.start.plus(LIVE).isAfter(now);
            boolean live = !now.isBefore(m.start) && !over && !m.noTime;
            if (over && !m.score.isEmpty()) {
                ballTags[i].setText(m.score);
                ballTags[i].setTextColor(0xFF7FE0A0);
            } else if (live) {
                ballTags[i].setText(m.score.isEmpty() ? "● LIVE" : "● " + m.score);
                ballTags[i].setTextColor(MonthView.RED);
            } else {
                ballTags[i].setText(tag(m.league));
                ballTags[i].setTextColor(0x8CFFFFFF);
            }
            // Played matches step back a little; the eye goes to what is next.
            float alpha = over ? 0.6f : 1f;
            ballDates[i].setAlpha(alpha);
            ballOpponents[i].setAlpha(alpha);
            ballLogos[i].setAlpha(alpha);
        }
    }

    /** The competition as a short tag at the end of a line: SL, ACL2, Shopee, or the name's initials. */
    static String tag(String league) {
        String s = shortLeague(league);
        if (s.equals("Super League")) return "SL";
        if (s.equals("ACL Two")) return "ACL2";
        if (s.equals("ACL Elite")) return "ACLE";
        if (s.equals("Shopee Cup")) return "SHOPEE";
        StringBuilder initials = new StringBuilder();
        for (String w : s.split("\\s+")) if (!w.isEmpty() && initials.length() < 5) initials.append(Character.toUpperCase(w.charAt(0)));
        return initials.toString();
    }

    private void maybeFetch(Instant now) {
        if (fetching) return;
        String key = cacheKey();
        String source = FootballSource.source(activity);
        long t = now.toEpochMilli();
        boolean ours = key.equals(prefs.getString(PREF_MATCHES_KEY, ""));
        long fetchedAt = ours ? prefs.getLong(PREF_FETCHED_AT, 0) : 0;
        long triedAt = prefs.getLong(PREF_TRIED_AT, 0);
        if (t - triedAt < RETRY_MS && triedAt > fetchedAt) return;   // the last attempt failed: back off
        boolean due;
        if (fetchedAt == 0) {
            due = true;
        } else if (FootballSource.GEMINI.equals(source)) {
            due = t - fetchedAt >= GEMINI_EVERY_MS;
            for (Match m : cachedMatches()) {
                // Half an hour after a match ends, ask once more for its result and what comes next.
                long after = m.start.plus(LIVE).plusSeconds(1800).toEpochMilli();
                if (after <= t && after > fetchedAt) due = true;
            }
        } else {
            long every = FOTMOB_EVERY_MS;
            for (Match m : cachedMatches()) {
                if (m.start.isBefore(now.plus(Duration.ofHours(3))) && m.start.plus(LIVE).isAfter(now)) every = NEAR_MATCH_MS;
            }
            due = t - fetchedAt >= every;
        }
        if (!due) return;
        fetching = true;
        prefs.edit().putLong(PREF_TRIED_AT, t).apply();
        String teamId = FootballSource.teamId(activity);
        String teamName = FootballSource.teamName(activity);
        Context app = activity.getApplicationContext();
        io.execute(() -> {
            String body = null;
            String error = null;
            try {
                body = FootballSource.fetch(app, source, teamId, teamName);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Gagal memuat jadwal" : e.getMessage();
            }
            final String result = body;
            final String message = error;
            main.post(() -> {
                fetching = false;
                if (!key.equals(cacheKey())) return;   // team or source changed meanwhile
                if (result != null) {
                    prefs.edit().putString(PREF_MATCHES, result).putString(PREF_MATCHES_KEY, key)
                            .putLong(PREF_FETCHED_AT, System.currentTimeMillis())
                            .remove(FootballSource.PREF_ERROR).apply();
                } else {
                    prefs.edit().putString(FootballSource.PREF_ERROR, message).apply();
                }
                if (!activity.isFinishing()) updateFootball(Instant.now());
            });
        });
    }

    private static List<Match> parse(String json, String teamId) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        List<Match> out = new ArrayList<>();
        try {
            JSONArray events = new JSONObject(json).optJSONArray("events");
            if (events == null) return out;
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.getJSONObject(i);
                Instant start = instant(e);
                if (start == null) continue;
                boolean home = teamId.equals(e.optString("idHomeTeam"));
                String hs = clean(e.optString("intHomeScore"));
                String as = clean(e.optString("intAwayScore"));
                String score = hs.isEmpty() || as.isEmpty() ? "" : hs + " - " + as;
                out.add(new Match(start, home, e.optBoolean("noTime"), clean(e.optString("strHomeTeam")),
                        clean(e.optString("strAwayTeam")), clean(e.optString("strHomeTeamBadge")),
                        clean(e.optString("strAwayTeamBadge")), clean(e.optString("strLeague")), score));
            }
        } catch (Exception ignored) {
            // A malformed cache shows as "no schedule" until the next fetch replaces it.
        }
        Collections.sort(out, (a, b) -> a.start.compareTo(b.start));
        return out;
    }

    private static Instant instant(JSONObject e) {
        try {
            String ts = clean(e.optString("strTimestamp"));
            if (!ts.isEmpty()) {
                if (ts.endsWith("Z")) return Instant.parse(ts);
                if (ts.contains("+")) return java.time.OffsetDateTime.parse(ts).toInstant();
                return LocalDateTime.parse(ts).toInstant(ZoneOffset.UTC);
            }
            String date = clean(e.optString("dateEvent"));
            String time = clean(e.optString("strTime"));
            if (date.isEmpty()) return null;
            if (time.isEmpty()) time = "00:00:00";
            if (time.length() > 8) time = time.substring(0, 8);
            return LocalDateTime.parse(date + "T" + time).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static String clean(String s) {
        return s == null || s.equals("null") ? "" : s.trim();
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "cleanyourtv-home-widgets");
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > 2 * 1024 * 1024) throw new java.io.IOException("reply too large");
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- text -------------------------------------------------------------------------------------

    private static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%02d.%02d", minutes / 60, minutes % 60);
    }

    private static String duration(int minutes) {
        if (minutes < 60) return minutes + " menit";
        int h = minutes / 60;
        int m = minutes % 60;
        return m == 0 ? h + " jam" : h + " jam " + m + " menit";
    }
}
