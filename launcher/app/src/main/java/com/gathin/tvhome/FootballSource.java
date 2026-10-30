package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the football card's fixtures come from (Launcher settings → Sumber jadwal):
 * <ul>
 * <li>FotMob: the team's whole season (league and cups) in one request to FotMob's public web API.</li>
 * <li>Gemini: a question to Google's Gemini with Google Search, using the owner's own free API key;
 *     the answer is checked before it is kept.</li>
 * </ul>
 * Both produce the same small JSON ({"events": [...]}, TheSportsDB-style field names) that HomeWidgets
 * caches and draws. Everything here runs off the UI thread.
 */
final class FootballSource {

    static final String FOTMOB = "fotmob";
    static final String GEMINI = "gemini";

    static final String PREF_SOURCE = "football_source";
    static final String PREF_TEAM_ID = "football_fm_id";
    static final String PREF_TEAM_NAME = "football_fm_name";
    static final String PREF_TEAM_LEAGUE = "football_fm_league";
    static final String PREF_GEMINI_KEY = "gemini_key";
    static final String PREF_GEMINI_MODEL = "gemini_model";
    static final String PREF_ERROR = "football_error";
    private static final String PREF_LOGOS = "football_logos";

    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final String FOTMOB_API = "https://www.fotmob.com/api/data/";
    private static final String GEMINI_API = "https://generativelanguage.googleapis.com/v1beta/";
    static final String DEFAULT_MODEL = "gemini-2.5-flash";

    private FootballSource() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static String source(Context c) {
        return GEMINI.equals(prefs(c).getString(PREF_SOURCE, FOTMOB)) ? GEMINI : FOTMOB;
    }

    static String teamId(Context c) {
        String id = prefs(c).getString(PREF_TEAM_ID, "");
        return id.isEmpty() ? c.getString(R.string.football_team_id).trim() : id;
    }

    static String teamName(Context c) {
        String name = prefs(c).getString(PREF_TEAM_NAME, "");
        return name.isEmpty() ? c.getString(R.string.football_team_name).trim() : name;
    }

    static String teamLeague(Context c) {
        String league = prefs(c).getString(PREF_TEAM_LEAGUE, "");
        return league.isEmpty() && prefs(c).getString(PREF_TEAM_ID, "").isEmpty() ? "Super League Indonesia" : league;
    }

    static String badge(String fotmobTeamId) {
        return fotmobTeamId == null || !fotmobTeamId.matches("\\d+") ? ""
                : "https://images.fotmob.com/image_resources/logo/teamlogo/" + fotmobTeamId + "_small.png";
    }

    /** The fixtures for the chosen source, or an exception with a message fit for the settings screen. */
    static String fetch(Context c, String source, String teamId, String teamName) throws Exception {
        return GEMINI.equals(source) ? gemini(c, teamId, teamName) : fotmob(teamId);
    }

    // ---- FotMob -----------------------------------------------------------------------------------

    private static String fotmob(String teamId) throws Exception {
        JSONObject team;
        try {
            team = new JSONObject(get(FOTMOB_API + "teams?id=" + teamId + "&ccode3=IDN", null, 4 << 20));
        } catch (Exception e) {
            throw new Exception("FotMob tidak bisa dihubungi");
        }
        JSONObject fixtures = team.optJSONObject("fixtures");
        JSONObject all = fixtures == null ? null : fixtures.optJSONObject("allFixtures");
        JSONArray list = all == null ? null : all.optJSONArray("fixtures");
        if (list == null) throw new Exception("FotMob tidak memberi jadwal untuk tim ini");
        Instant since = Instant.now().minus(Duration.ofHours(36));
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.getJSONObject(i);
            JSONObject status = m.optJSONObject("status");
            JSONObject home = m.optJSONObject("home");
            JSONObject away = m.optJSONObject("away");
            if (status == null || home == null || away == null || status.optBoolean("cancelled")) continue;
            Instant start;
            try {
                start = Instant.parse(status.optString("utcTime"));
            } catch (RuntimeException e) {
                continue;
            }
            if (start.isBefore(since)) continue;
            JSONObject e = new JSONObject();
            e.put("idEvent", "fm-" + m.optString("id"));
            e.put("strTimestamp", start.toString());
            String homeId = home.optString("id");
            String awayId = away.optString("id");
            e.put("idHomeTeam", homeId.equals(teamId) ? teamId : "fm-" + homeId);
            e.put("idAwayTeam", awayId.equals(teamId) ? teamId : "fm-" + awayId);
            e.put("strHomeTeam", home.optString("name"));
            e.put("strAwayTeam", away.optString("name"));
            e.put("strHomeTeamBadge", badge(homeId));
            e.put("strAwayTeamBadge", badge(awayId));
            JSONObject tournament = m.optJSONObject("tournament");
            e.put("strLeague", tournament == null ? "" : tournament.optString("name"));
            if (status.optBoolean("started")) {
                String[] score = status.optString("scoreStr").split("-");
                if (score.length == 2) {
                    e.put("intHomeScore", score[0].trim());
                    e.put("intAwayScore", score[1].trim());
                } else {
                    e.put("intHomeScore", home.optString("score"));
                    e.put("intAwayScore", away.optString("score"));
                }
            }
            out.add(e);
            if (out.size() >= 12) break;
        }
        return wrap(out);
    }

    /** Teams whose name matches {@code term}: {id, name, league} each. */
    static List<String[]> searchTeams(String term) throws Exception {
        String url = FOTMOB_API + "search/suggest?term=" + URLEncoder.encode(term, "UTF-8").replace("+", "%20");
        JSONArray groups = new JSONArray(get(url, null, 1 << 20));
        List<String[]> out = new ArrayList<>();
        for (int g = 0; g < groups.length(); g++) {
            JSONArray suggestions = groups.getJSONObject(g).optJSONArray("suggestions");
            for (int i = 0; suggestions != null && i < suggestions.length(); i++) {
                JSONObject s = suggestions.getJSONObject(i);
                if (!"team".equals(s.optString("type"))) continue;
                String id = s.optString("id");
                boolean seen = false;
                for (String[] t : out) seen |= t[0].equals(id);
                if (!seen && id.matches("\\d+")) out.add(new String[]{id, s.optString("name"), s.optString("leagueName")});
            }
        }
        return out;
    }

    // ---- Gemini -----------------------------------------------------------------------------------

    private static String gemini(Context c, String teamId, String teamName) throws Exception {
        SharedPreferences p = prefs(c);
        String key = p.getString(PREF_GEMINI_KEY, "");
        if (key.isEmpty()) throw new Exception("Key Gemini belum dimasukkan");
        String model = p.getString(PREF_GEMINI_MODEL, DEFAULT_MODEL);
        LocalDate today = LocalDate.now(WIB);
        String league = teamLeague(c);
        String prompt = "You are a football fixtures lookup. Use Google Search to find the official schedule.\n"
                + "Club: \"" + teamName + "\"" + (league.isEmpty() ? "" : " (" + league + ")") + ".\n"
                + "Today in Indonesia (Asia/Jakarta, WIB) is "
                + today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd, EEEE", Locale.ENGLISH)) + ".\n"
                + "Task: list this club's next 5 official matches from today onward, in all competitions "
                + "(league, domestic cup, AFC competitions, ASEAN Club Championship / Shopee Cup). "
                + "Also include a match played today, if any, with its final score.\n"
                + "Rules:\n"
                + "- Only include matches you found in the search results with a confirmed date. Never guess or invent.\n"
                + "- Kick-off time in WIB (UTC+7), 24-hour \"HH:MM\"; use \"\" if it is not announced yet.\n"
                + "- Reply with ONLY a JSON array, no markdown and no other text. Each item:\n"
                + "{\"date\":\"YYYY-MM-DD\",\"time\":\"HH:MM\",\"competition\":\"...\",\"home\":\"...\",\"away\":\"...\",\"score\":\"\"}\n"
                + "- \"score\" only for a finished match, as \"home-away\" like \"2-0\"; otherwise \"\".\n"
                + "- If you cannot find reliable fixtures, reply [].";
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)))))
                .put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())))
                .put("generationConfig", new JSONObject().put("temperature", 0));
        String reply;
        try {
            reply = post(GEMINI_API + "models/" + model + ":generateContent", body.toString(), key);
        } catch (HttpError e) {
            throw new Exception(geminiError(e.code));
        } catch (Exception e) {
            throw new Exception("Gemini tidak bisa dihubungi");
        }
        StringBuilder text = new StringBuilder();
        JSONArray candidates = new JSONObject(reply).optJSONArray("candidates");
        if (candidates != null && candidates.length() > 0) {
            JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text"));
        }
        JSONArray items = jsonArray(text.toString());
        if (items == null) throw new Exception("Jawaban Gemini tidak terbaca");

        // Check every line before it is kept: a real date in range, the club on one side.
        String token = keyWord(teamName);
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < items.length() && out.size() < 6; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            LocalDate date;
            try {
                date = LocalDate.parse(it.optString("date").trim());
            } catch (RuntimeException e) {
                continue;
            }
            if (date.isBefore(today.minusDays(1)) || date.isAfter(today.plusDays(120))) continue;
            String home = HomeWidgets.clean(it.optString("home"));
            String away = HomeWidgets.clean(it.optString("away"));
            boolean ourHome = plain(home).contains(token);
            boolean ourAway = plain(away).contains(token);
            if (ourHome == ourAway || home.isEmpty() || away.isEmpty()) continue;   // the club must be on exactly one side
            String time = it.optString("time").trim();
            boolean noTime = !time.matches("\\d{1,2}[:.]\\d{2}");
            LocalTime t = noTime ? LocalTime.NOON : LocalTime.parse(pad(time.replace('.', ':')));
            Instant start = LocalDateTime.of(date, t).atZone(WIB).toInstant();
            JSONObject e = new JSONObject();
            e.put("idEvent", "ai-" + date + "-" + plain(ourHome ? away : home));
            e.put("strTimestamp", start.toString());
            e.put("noTime", noTime);
            e.put("idHomeTeam", ourHome ? teamId : "ai-home");
            e.put("idAwayTeam", ourAway ? teamId : "ai-away");
            e.put("strHomeTeam", ourHome ? teamName : home);
            e.put("strAwayTeam", ourAway ? teamName : away);
            e.put("strHomeTeamBadge", ourHome ? badge(teamId) : logoFor(c, home));
            e.put("strAwayTeamBadge", ourAway ? badge(teamId) : logoFor(c, away));
            e.put("strLeague", HomeWidgets.clean(it.optString("competition")));
            Matcher score = Pattern.compile("^\\s*(\\d+)\\s*[-:]\\s*(\\d+)\\s*$").matcher(it.optString("score"));
            if (score.matches()) {
                e.put("intHomeScore", score.group(1));
                e.put("intAwayScore", score.group(2));
            }
            out.add(e);
        }
        Collections.sort(out, (a, b) -> a.optString("strTimestamp").compareTo(b.optString("strTimestamp")));
        return wrap(out);
    }

    private static String pad(String hhmm) {
        return hhmm.length() == 4 ? "0" + hhmm : hhmm;
    }

    private static String geminiError(int code) {
        if (code == 400 || code == 401 || code == 403) return "Key Gemini ditolak (salah atau dicabut)";
        if (code == 404) return "Model Gemini tidak tersedia; masukkan ulang key";
        if (code == 429) return "Kuota gratis Gemini habis, dicoba lagi nanti";
        return "Gemini error " + code;
    }

    /**
     * Checks a key and picks the newest Flash model it may use. Returns the model name, or throws with a
     * message for the person.
     */
    static String checkKey(String key) throws Exception {
        String reply;
        try {
            reply = get(GEMINI_API + "models?pageSize=200", key, 2 << 20);
        } catch (HttpError e) {
            throw new Exception(geminiError(e.code));
        } catch (Exception e) {
            throw new Exception("Gemini tidak bisa dihubungi. Cek internet.");
        }
        JSONArray models = new JSONObject(reply).optJSONArray("models");
        String best = null;
        double bestVersion = -1;
        Pattern version = Pattern.compile("gemini-(\\d+(?:\\.\\d+)?)-flash$");
        for (int i = 0; models != null && i < models.length(); i++) {
            JSONObject m = models.getJSONObject(i);
            String name = m.optString("name").replace("models/", "");
            JSONArray methods = m.optJSONArray("supportedGenerationMethods");
            if (methods == null || !methods.toString().contains("generateContent")) continue;
            Matcher v = version.matcher(name);
            if (!v.find()) continue;
            double ver = Double.parseDouble(v.group(1));
            if (ver > bestVersion) {
                bestVersion = ver;
                best = name;
            }
        }
        return best != null ? best : DEFAULT_MODEL;
    }

    /** An opponent's badge for the Gemini source, looked up once per name on FotMob and remembered. */
    private static String logoFor(Context c, String name) {
        SharedPreferences p = prefs(c);
        String k = plain(name);
        try {
            JSONObject cache = new JSONObject(p.getString(PREF_LOGOS, "{}"));
            if (cache.has(k)) return cache.optString(k);
            String logo = "";
            try {
                List<String[]> teams = searchTeams(name);
                if (!teams.isEmpty()) logo = badge(teams.get(0)[0]);
            } catch (Exception ignored) {
                return "";   // try again next time
            }
            cache.put(k, logo);
            p.edit().putString(PREF_LOGOS, cache.toString()).apply();
            return logo;
        } catch (Exception e) {
            return "";
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static String wrap(List<JSONObject> events) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject e : events) a.put(e);
        return new JSONObject().put("events", a).toString();
    }

    /** The first JSON array in a model's reply (it may come wrapped in a code fence or a sentence). */
    private static JSONArray jsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        try {
            return new JSONArray(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    static String plain(String s) {
        return java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** The word of a club's name most likely to appear in any spelling of it: its longest word. */
    private static String keyWord(String team) {
        String best = "";
        for (String w : team.split("[^\\p{L}\\p{N}]+")) {
            String p = plain(w);
            if (p.equals("fc") || p.equals("club") || p.equals("united")) continue;
            if (p.length() > best.length()) best = p;
        }
        return best.isEmpty() ? plain(team) : best;
    }

    static final class HttpError extends Exception {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private static String get(String url, String geminiKey, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) cleanyourtv-home-widgets");
        if (geminiKey != null) c.setRequestProperty("x-goog-api-key", geminiKey);
        return read(c, limit);
    }

    private static String post(String url, String body, String geminiKey) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(90_000);   // a search-grounded answer can take a while
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", geminiKey);
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(c, 2 << 20);
    }

    private static String read(HttpURLConnection c, int limit) throws Exception {
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    buf.write(b, 0, n);
                    if (buf.size() > limit) throw new java.io.IOException("reply too large");
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
