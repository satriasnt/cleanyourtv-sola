package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.time.LocalDate;

/**
 * A small month grid in the style of the iOS calendar widget: the month's name in red, Monday-first
 * weekday initials, and today's date in a red disc. Drawn once per day change; nothing animates.
 */
public final class MonthView extends View {

    static final String[] MONTHS = {"JANUARI", "FEBRUARI", "MARET", "APRIL", "MEI", "JUNI", "JULI",
            "AGUSTUS", "SEPTEMBER", "OKTOBER", "NOVEMBER", "DESEMBER"};
    private static final String[] WEEKDAYS = {"S", "S", "R", "K", "J", "S", "M"};
    static final int RED = 0xFFFF453A;

    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint weekday = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint day = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private LocalDate date = LocalDate.now();

    public MonthView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        float sp = getResources().getDisplayMetrics().scaledDensity;
        title.setColor(RED);
        title.setTextSize(9.5f * sp);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setLetterSpacing(0.12f);
        weekday.setColor(0x80FFFFFF);
        weekday.setTextSize(8f * sp);
        weekday.setTextAlign(Paint.Align.CENTER);
        weekday.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        day.setTextSize(8.5f * sp);
        day.setTextAlign(Paint.Align.CENTER);
        day.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        disc.setColor(RED);
        float shadow = 2.5f * density;
        title.setShadowLayer(shadow, 0, density * 0.8f, 0x8C000000);
        weekday.setShadowLayer(shadow, 0, density * 0.8f, 0x8C000000);
        day.setShadowLayer(shadow, 0, density * 0.8f, 0x8C000000);
        setWillNotDraw(false);
    }

    void setDate(LocalDate d) {
        if (d.equals(date)) return;
        date = d;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float titleH = 14 * density;
        float weekH = 12 * density;
        c.drawText(MONTHS[date.getMonthValue() - 1], 0, title.getTextSize(), title);

        float cellW = w / 7f;
        for (int i = 0; i < 7; i++) {
            weekday.setColor(i == 6 ? 0xB3FF6B6B : 0x80FFFFFF);
            c.drawText(WEEKDAYS[i], cellW * i + cellW / 2, titleH + weekday.getTextSize(), weekday);
        }

        LocalDate first = date.withDayOfMonth(1);
        int offset = first.getDayOfWeek().getValue() - 1;   // Monday = 0
        int days = date.lengthOfMonth();
        int weeks = (offset + days + 6) / 7;
        float top = titleH + weekH + 2 * density;
        float cellH = (h - top) / weeks;
        float textOffset = (day.descent() + day.ascent()) / 2;
        float radius = Math.min(cellW, cellH) * 0.46f;
        for (int d = 1; d <= days; d++) {
            int index = offset + d - 1;
            int col = index % 7;
            int row = index / 7;
            float cx = cellW * col + cellW / 2;
            float cy = top + cellH * row + cellH / 2;
            boolean today = d == date.getDayOfMonth();
            if (today) {
                c.drawCircle(cx, cy, radius, disc);
                day.setColor(0xFFFFFFFF);
                day.setFakeBoldText(true);
            } else {
                day.setColor(col == 6 ? 0xCCFF6B6B : 0xD9FFFFFF);
                day.setFakeBoldText(false);
            }
            c.drawText(String.valueOf(d), cx, cy - textOffset, day);
        }
    }
}
