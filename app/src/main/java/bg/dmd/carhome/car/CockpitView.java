package bg.dmd.carhome.car;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;


/**
 * The cabin seen from above, darkened like at night, with the four footwells lit in the chosen colour:
 * the photo itself is relit there (floor and seat bases pick up the colour). The photo comes from the
 * brand pack (drawable "cabin", front of the car on the right); without one, only the four light pools
 * are drawn on a dark panel.
 */
public class CockpitView extends View {
    // footwells in photo coordinates (0..1), front of the car on the right
    private static final float[][] WELLS = {{0.84f, 0.44f}, {0.89f, 0.79f}, {0.565f, 0.40f}, {0.565f, 0.81f}};

    private final Bitmap photo;
    private final Paint dark = new Paint(Paint.FILTER_BITMAP_FLAG), lit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG), add = new Paint(Paint.ANTI_ALIAS_FLAG), p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private int from = 0x2F7BFF, to = 0x2F7BFF, litColor = -1;
    private long colorAt, waveAt = -10000;
    private float level = 1f, shown = 1f;
    private boolean on = true;

    public CockpitView(Context c) {
        super(c);
        int id = bg.dmd.carhome.App.optionalDrawable("cabin");
        photo = id == 0 ? null : BitmapFactory.decodeResource(c.getResources(), id);
        ColorMatrix dm = new ColorMatrix();
        dm.setSaturation(0.2f);
        dm.postConcat(new ColorMatrix(new float[]{0.30f, 0, 0, 0, 0, 0, 0.31f, 0, 0, 0, 0, 0, 0.34f, 0, 0, 0, 0, 0, 1, 0}));
        dark.setColorFilter(new ColorMatrixColorFilter(dm));
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    public void setColor(int rgb) {
        rgb &= 0xFFFFFF;
        if (rgb == to) return;
        from = current();
        to = rgb;
        colorAt = waveAt = SystemClock.uptimeMillis();
    }

    public void setLevel(float l, boolean on) { level = l; this.on = on; }

    private int current() {
        float t = Math.min(1, (SystemClock.uptimeMillis() - colorAt) / 700f);
        return (Integer) argb.evaluate(t, 0xFF000000 | from, 0xFF000000 | to) & 0xFFFFFF;
    }

    private static int a(int rgb, float alpha) {
        return (Math.max(0, Math.min(255, (int) (alpha * 255))) << 24) | (rgb & 0xFFFFFF);
    }

    /** The photo as it looks under a light of this colour: grey upholstery takes on the hue. */
    private void tintFor(int col) {
        if (col == litColor) return;
        litColor = col;
        float r = ((col >> 16) & 255) / 255f, g = ((col >> 8) & 255) / 255f, b = (col & 255) / 255f;
        float k = 1.9f;
        ColorMatrix gray = new ColorMatrix();
        gray.setSaturation(0f);
        gray.postConcat(new ColorMatrix(new float[]{r * k, 0, 0, 0, 0, 0, g * k, 0, 0, 0, 0, 0, b * k, 0, 0, 0, 0, 0, 1, 0}));
        lit.setColorFilter(new ColorMatrixColorFilter(gray));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        int col = current();
        shown += ((on ? level : 0f) - shown) * 0.12f;
        float lv = shown;
        boolean day = bg.dmd.carhome.Theme.day;
        c.drawColor(bg.dmd.carhome.Theme.PAGE);
        // by day the night-lit cabin sits in its own dark rounded screen instead of melting into the light page
        int pg = day ? 0xFF07090C : bg.dmd.carhome.Theme.PAGE | 0xFF000000, clear = pg & 0xFFFFFF;

        // photo placement: left part of the screen, under the title
        float aspect = photo == null ? 0.5f : photo.getHeight() / (float) photo.getWidth();
        float pw = w * 0.52f, ph = pw * aspect;
        float px = w * 0.005f, py = h * 0.30f;
        if (py + ph > h * 0.97f) { ph = h * 0.67f; pw = ph / aspect; }
        RectF dst = new RectF(px, py, px + pw, py + ph);

        if (photo == null) { drawPools(c, dst, col, lv, now, pg); postInvalidateOnAnimation(); return; }

        int frame = -1;
        if (day) {
            float dd = getResources().getDisplayMetrics().density, rr = 24 * dd;
            p.setColor(0x26000000);
            c.drawRoundRect(new RectF(dst.left, dst.top + 4 * dd, dst.right, dst.bottom + 6 * dd), rr, rr, p);
            Path clip = new Path();
            clip.addRoundRect(dst, rr, rr, Path.Direction.CW);
            frame = c.save();
            c.clipPath(clip);
            c.drawColor(pg);
        }
        c.drawBitmap(photo, null, dst, dark);

        // relight each footwell: tinted photo, masked by a soft radial falloff
        tintFor(col);
        float wave = (now - waveAt) / 900f;
        for (int i = 0; i < WELLS.length; i++) {
            float x = dst.left + WELLS[i][0] * pw, y = dst.top + WELLS[i][1] * ph;
            float boost = 0, start = i < 2 ? 0f : 0.3f;
            if (wave > start && wave < start + 0.6f) boost = (float) Math.sin((wave - start) / 0.6f * Math.PI) * 0.35f;
            float r = pw * 0.17f;
            float alpha = Math.min(1f, (0.95f + boost) * lv);
            if (alpha <= 0.01f) continue;
            RectF box = new RectF(x - r, y - r, x + r, y + r);
            int sc = c.saveLayer(box, null, Canvas.ALL_SAVE_FLAG);
            c.clipRect(dst);
            c.drawBitmap(photo, null, dst, lit);
            mask.setShader(new RadialGradient(x, y, r, new int[]{a(0xFFFFFF, alpha), a(0xFFFFFF, alpha * 0.45f), 0},
                    new float[]{0, 0.45f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(box, mask);
            c.restoreToCount(sc);
            // bloom right at the LED
            add.setShader(new RadialGradient(x, y, r * 0.55f, a(col, 0.45f * alpha), 0, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r * 0.55f, add);
        }
        mask.setShader(null);
        add.setShader(null);

        // fade the photo edges into the page
        p.setShader(new LinearGradient(dst.left, 0, dst.left + pw * 0.12f, 0, pg, clear, Shader.TileMode.CLAMP));
        c.drawRect(dst.left, dst.top, dst.left + pw * 0.12f, dst.bottom, p);
        p.setShader(new LinearGradient(dst.right - pw * 0.10f, 0, dst.right, 0, clear, pg, Shader.TileMode.CLAMP));
        c.drawRect(dst.right - pw * 0.10f, dst.top, dst.right + 1, dst.bottom, p);
        p.setShader(new LinearGradient(0, dst.top, 0, dst.top + ph * 0.15f, pg, clear, Shader.TileMode.CLAMP));
        c.drawRect(dst.left, dst.top, dst.right, dst.top + ph * 0.15f, p);
        p.setShader(new LinearGradient(0, dst.bottom - ph * 0.15f, 0, dst.bottom, clear, pg, Shader.TileMode.CLAMP));
        c.drawRect(dst.left, dst.bottom - ph * 0.15f, dst.right, dst.bottom + 1, p);
        p.setShader(null);
        if (frame >= 0) c.restoreToCount(frame);

        postInvalidateOnAnimation();
    }

    /** No cabin photo: a dark rounded panel with the four footwell light pools. */
    private void drawPools(Canvas c, RectF dst, int col, float lv, long now, int pg) {
        float dd = getResources().getDisplayMetrics().density, rr = 24 * dd;
        p.setShader(null);
        p.setColor(0xFF07090C);
        c.drawRoundRect(dst, rr, rr, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dd);
        p.setColor(0x22FFFFFF);
        c.drawRoundRect(dst, rr, rr, p);
        p.setStyle(Paint.Style.FILL);
        float wave = (now - waveAt) / 900f;
        for (int i = 0; i < WELLS.length; i++) {
            float x = dst.left + WELLS[i][0] * dst.width(), y = dst.top + WELLS[i][1] * dst.height();
            float boost = 0, start = i < 2 ? 0f : 0.3f;
            if (wave > start && wave < start + 0.6f) boost = (float) Math.sin((wave - start) / 0.6f * Math.PI) * 0.35f;
            float alpha = Math.min(1f, (0.85f + boost) * lv);
            if (alpha <= 0.01f) continue;
            float r = dst.width() * 0.16f;
            add.setShader(new RadialGradient(x, y, r, new int[]{a(col, 0.75f * alpha), a(col, 0.25f * alpha), 0},
                    new float[]{0, 0.4f, 1}, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, r, add);
        }
        add.setShader(null);
    }
}
