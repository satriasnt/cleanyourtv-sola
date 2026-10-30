package com.gathin.tvhome;

import java.time.LocalDate;

/**
 * Prayer times computed offline with the Kemenag RI parameters: Subuh at the sun 20 degrees below the
 * horizon, Isya at 18 degrees, Ashar by the Shafi'i shadow rule, sunrise and sunset corrected for the
 * place's elevation, and ihtiyat (2 minutes, 3 for Dzuhur; taken off sunrise). Checked against the Kemenag
 * schedule for Kota Bandung: within a minute. The astronomy follows the PrayTimes.org formulas.
 */
final class PrayerTimes {

    static final String[] NAMES = {"Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya"};
    static final int SUBUH = 0, TERBIT = 1, DZUHUR = 2, ASHAR = 3, MAGHRIB = 4, ISYA = 5;
    /** The five prayers, without sunrise. */
    static final int[] PRAYERS = {SUBUH, DZUHUR, ASHAR, MAGHRIB, ISYA};

    private static final double FAJR_ANGLE = 20.0;
    private static final double ISHA_ANGLE = 18.0;
    private static final int IHTIYAT_MIN = 2;

    private PrayerTimes() {
    }

    /**
     * Minutes after local midnight for Subuh, Terbit, Dzuhur, Ashar, Maghrib and Isya on {@code date}.
     *
     * @param tzHours the local UTC offset in hours on that date, e.g. 7 for WIB
     */
    static int[] compute(LocalDate date, double lat, double lng, double elevation, double tzHours) {
        double jd = julian(date.getYear(), date.getMonthValue(), date.getDayOfMonth()) - lng / (15 * 24.0);
        double riseAngle = 0.833 + 0.0347 * Math.sqrt(Math.max(0, elevation));
        double[] t = {5, 6, 12, 13, 18, 18};
        for (int pass = 0; pass < 2; pass++) {
            double[] p = new double[6];
            for (int k = 0; k < 6; k++) p[k] = t[k] / 24.0;
            t[SUBUH] = sunAngleTime(jd, FAJR_ANGLE, p[SUBUH], true, lat);
            t[TERBIT] = sunAngleTime(jd, riseAngle, p[TERBIT], true, lat);
            t[DZUHUR] = midDay(jd, p[DZUHUR]);
            t[ASHAR] = asrTime(jd, 1, p[ASHAR], lat);
            t[MAGHRIB] = sunAngleTime(jd, riseAngle, p[MAGHRIB], false, lat);
            t[ISYA] = sunAngleTime(jd, ISHA_ANGLE, p[ISYA], false, lat);
        }
        int[] out = new int[6];
        for (int k = 0; k < 6; k++) {
            double minutes = (t[k] + tzHours - lng / 15.0) * 60.0;
            if (k == TERBIT) {
                out[k] = (int) Math.floor(minutes - IHTIYAT_MIN);
            } else {
                int ihtiyat = k == DZUHUR ? IHTIYAT_MIN + 1 : IHTIYAT_MIN;
                out[k] = (int) Math.ceil(minutes + ihtiyat - 1e-6);
            }
            out[k] = ((out[k] % 1440) + 1440) % 1440;
        }
        return out;
    }

    // ---- astronomy --------------------------------------------------------------------------------

    /** {declination in degrees, equation of time in hours} at Julian day {@code jd}. */
    private static double[] sunPosition(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = fixHour(darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0);
        double eqt = q / 15.0 - ra;
        double decl = darcsin(dsin(e) * dsin(l));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jd, double t) {
        return fixHour(12 - sunPosition(jd + t)[1]);
    }

    private static double sunAngleTime(double jd, double angle, double t, boolean beforeNoon, double lat) {
        double decl = sunPosition(jd + t)[0];
        double noon = midDay(jd, t);
        double cos = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat));
        double hours = darccos(Math.max(-1, Math.min(1, cos))) / 15.0;
        return noon + (beforeNoon ? -hours : hours);
    }

    private static double asrTime(double jd, double factor, double t, double lat) {
        double decl = sunPosition(jd + t)[0];
        double angle = -Math.toDegrees(Math.atan(1 / (factor + dtan(Math.abs(lat - decl)))));
        return sunAngleTime(jd, angle, t, false, lat);
    }

    private static double julian(int year, int month, int day) {
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4.0);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    private static double dsin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double dcos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double dtan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double darcsin(double x) { return Math.toDegrees(Math.asin(x)); }
    private static double darccos(double x) { return Math.toDegrees(Math.acos(x)); }
    private static double darctan2(double y, double x) { return Math.toDegrees(Math.atan2(y, x)); }
    private static double fixAngle(double a) { a = a - 360.0 * Math.floor(a / 360.0); return a < 0 ? a + 360 : a; }
    private static double fixHour(double a) { a = a - 24.0 * Math.floor(a / 24.0); return a < 0 ? a + 24 : a; }
}
