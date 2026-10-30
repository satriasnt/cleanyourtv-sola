package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * The look of the home widget cards (Launcher settings → Widget Home):
 * <ul>
 * <li>Kaca buram: the wallpaper behind the card, blurred, under a dark veil, like iOS widgets.</li>
 * <li>Gelap: a dark translucent card.</li>
 * <li>Terang: the light frosted card of the first version.</li>
 * <li>Otomatis: dark on a bright wallpaper, light on a dark one.</li>
 * </ul>
 * The blur comes from a small snapshot of the layers under the widget row (sky and wallpaper), taken
 * when the wallpaper may have changed, never per frame.
 */
final class WidgetStyle {

    static final String PREF_STYLE = "widget_style";
    static final String PREF_SHADE = "widget_shade";
    static final String BLUR = "blur", DARK = "dark", LIGHT = "light", AUTO = "auto";
    static final String[] STYLES = {BLUR, DARK, LIGHT, AUTO};
    static final String[] STYLE_NAMES = {"Kaca buram", "Gelap", "Terang", "Otomatis"};
    static final String[] SHADE_NAMES = {"Tipis", "Sedang", "Tebal"};
    /** The dark veil over the blur, and the dark card, per shade. */
    private static final int[] VEIL = {0x38, 0x5C, 0x85};
    private static final int[] DARK_FILL = {0x73, 0x99, 0xC2};
    /** The snapshot's scale: a fifth of the screen is plenty under a blur. */
    private static final float SCALE = 0.2f;

    private WidgetStyle() {
    }

    static int index(String style) {
        for (int i = 0; i < STYLES.length; i++) if (STYLES[i].equals(style)) return i;
        return 0;
    }

    /**
     * The layers of {@code root} that sit under {@code row} (wallpaper, sky), drawn small, blurred and read
     * back into a software bitmap; null where the platform cannot (the caller falls back to Gelap).
     */
    static Bitmap backdrop(ViewGroup root, View row) {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0 || android.os.Build.VERSION.SDK_INT < 28) return null;
        try {
            int sw = Math.max(1, Math.round(w * SCALE)), sh = Math.max(1, Math.round(h * SCALE));
            Picture picture = new Picture();
            Canvas c = picture.beginRecording(sw, sh);
            c.scale(SCALE, SCALE);
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child == row) break;                       // only what lies under the widgets
                if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) continue;
                int save = c.save();
                c.translate(child.getX(), child.getY());
                if (child.getAlpha() < 1f) {
                    c.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), Math.round(child.getAlpha() * 255));
                }
                child.draw(c);
                c.restoreToCount(save);
            }
            picture.endRecording();
            // The wallpaper is a GPU bitmap: render the recording on the GPU, then read it back once.
            Bitmap gpu = Bitmap.createBitmap(picture);
            Bitmap soft = gpu.copy(Bitmap.Config.ARGB_8888, true);
            gpu.recycle();
            if (soft == null) return null;
            blur(soft, Math.max(2, Math.round(sw / 90f)));
            return soft;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Three box-blur passes in place, close enough to a Gaussian. */
    private static void blur(Bitmap b, int radius) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        int[] tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, radius, true);
            box(tmp, px, w, h, radius, false);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h;
        for (int line = 0; line < lines; line++) {
            int sr = 0, sg = 0, sb = 0, n = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[at(line, Math.max(0, Math.min(len - 1, i)), w, horizontal)];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
                n++;
            }
            for (int i = 0; i < len; i++) {
                out[at(line, i, w, horizontal)] = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                int add = in[at(line, Math.min(len - 1, i + r + 1), w, horizontal)];
                int del = in[at(line, Math.max(0, i - r), w, horizontal)];
                sr += ((add >> 16) & 0xFF) - ((del >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((del >> 8) & 0xFF);
                sb += (add & 0xFF) - (del & 0xFF);
            }
        }
    }

    private static int at(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    /** The average brightness (0..1) of the backdrop under a card. */
    static float brightness(Bitmap backdrop, RectF card) {
        if (backdrop == null) return 0f;
        int x0 = clamp((int) (card.left * SCALE), backdrop.getWidth() - 1);
        int y0 = clamp((int) (card.top * SCALE), backdrop.getHeight() - 1);
        int x1 = clamp((int) (card.right * SCALE), backdrop.getWidth());
        int y1 = clamp((int) (card.bottom * SCALE), backdrop.getHeight());
        long sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                int p = backdrop.getPixel(x, y);
                sum += (((p >> 16) & 0xFF) * 299 + ((p >> 8) & 0xFF) * 587 + (p & 0xFF) * 114) / 1000;
                n++;
            }
        }
        return n == 0 ? 0f : sum / (255f * n);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /** The background for a card at {@code card} (root coordinates). */
    static Drawable background(View card, String style, int shade, Bitmap backdrop, RectF where) {
        float d = card.getResources().getDisplayMetrics().density;
        shade = Math.max(0, Math.min(2, shade));
        String s = style;
        if (AUTO.equals(s)) s = brightness(backdrop, where) > 0.5f ? DARK : LIGHT;
        if (BLUR.equals(s) && backdrop != null) return new Frosted(backdrop, where, 20 * d, VEIL[shade] << 24, d);
        if (LIGHT.equals(s)) return card.getResources().getDrawable(R.drawable.widget_card_bg, null);
        GradientDrawable dark = new GradientDrawable();
        dark.setColor(DARK_FILL[shade] << 24 | 0x0E1014);
        dark.setCornerRadius(20 * d);
        dark.setStroke(Math.max(1, Math.round(d)), 0x29FFFFFF);
        return dark;
    }

    /** The blurred backdrop clipped to a rounded card, under a dark veil and a hairline rim. */
    private static final class Frosted extends Drawable {
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final BitmapShader shader;
        private final RectF where;
        private final float radius;
        private final RectF rect = new RectF();

        Frosted(Bitmap backdrop, RectF where, float radius, int veilColor, float density) {
            this.where = new RectF(where);
            this.radius = radius;
            shader = new BitmapShader(backdrop, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            image.setShader(shader);
            veil.setColor(veilColor);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, density));
            rim.setColor(0x33FFFFFF);
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            // Map the small backdrop so the part under this card fills it.
            Matrix m = new Matrix();
            m.setScale(1f / SCALE, 1f / SCALE);
            m.postTranslate(-where.left + rect.left, -where.top + rect.top);
            shader.setLocalMatrix(m);
            canvas.drawRoundRect(rect, radius, radius, image);
            canvas.drawRoundRect(rect, radius, radius, veil);
            rect.inset(rim.getStrokeWidth() / 2, rim.getStrokeWidth() / 2);
            canvas.drawRoundRect(rect, radius, radius, rim);
        }

        @Override
        public void setAlpha(int alpha) {
            image.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            image.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
