package bg.dmd.carhome.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.view.View;

/** i-Cockpit style rev counter: thin 260° arc, glowing value arc, big digital speed in the middle. */
public class Gauge extends View {
    private float value = 0, shown = 0;       // rpm
    private float max = 5000, red = 4200;
    private String center = "—", unit = "km/h", sub = "";
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG), small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;

    public Gauge(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(bg.dmd.carhome.Theme.TRACK);
        arc.set(track);
        tick.setColor(bg.dmd.carhome.Theme.TICK);
        tick.setStrokeWidth(2 * d);
        num.setColor(bg.dmd.carhome.Theme.DIM);
        num.setTextAlign(Paint.Align.CENTER);
        num.setTypeface(bg.dmd.carhome.Theme.font("sans-serif-light"));
        big.setColor(bg.dmd.carhome.Theme.TEXT);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTypeface(bg.dmd.carhome.Theme.font("sans-serif-thin"));
        small.set(num);
    }

    public void set(float rpm, String center, String sub) {
        value = Math.max(0, rpm);
        this.center = center;
        this.sub = sub;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = Math.min(w, h) / 2f - 18 * d;
        float cx = w / 2f, cy = h / 2f + 8 * d;
        RectF box = new RectF(cx - r, cy - r, cx + r, cy + r);
        float start = 140, sweep = 260;

        float sw = 10 * d;
        track.setStrokeWidth(sw);
        c.drawArc(box, start, sweep, false, track);

        shown += (value - shown) * 0.35f;      // smooth needle
        float frac = Math.min(1, shown / max);
        int[] cols = bg.dmd.carhome.Theme.day ? new int[]{0xFF0A3D8F, 0xFF0062D6, 0xFF3D8BFF, 0xFFD12A1F} : new int[]{0xFF2B6CB0, 0xFF4AA3FF, 0xFF9BD1FF, 0xFFFF5A4E};
        float[] pos = {0, 0.55f, 0.78f, 0.95f};
        SweepGradient g = new SweepGradient(cx, cy, cols, pos);
        Matrix m = new Matrix();
        m.setRotate(start - 5, cx, cy);
        g.setLocalMatrix(m);
        arc.setShader(g);
        arc.setStrokeWidth(sw);
        if (frac > 0.002f) c.drawArc(box, start, sweep * frac, false, arc);

        // ticks every 1000 rpm, red zone ticks red
        num.setTextSize(14 * d);
        for (int k = 0; k <= max / 1000; k++) {
            double a = Math.toRadians(start + sweep * (k * 1000f / max));
            float c1 = r - sw - 6 * d, c2 = c1 - 10 * d;
            tick.setColor(k * 1000 >= red ? bg.dmd.carhome.Theme.ERR : bg.dmd.carhome.Theme.TICK);
            c.drawLine(cx + (float) Math.cos(a) * c1, cy + (float) Math.sin(a) * c1,
                    cx + (float) Math.cos(a) * c2, cy + (float) Math.sin(a) * c2, tick);
            float tr = c2 - 14 * d;
            c.drawText(String.valueOf(k), cx + (float) Math.cos(a) * tr, cy + (float) Math.sin(a) * tr + 5 * d, num);
        }

        big.setTextSize(r * 0.62f);
        c.drawText(center, cx, cy + r * 0.2f, big);
        small.setTextSize(15 * d);
        c.drawText(unit, cx, cy + r * 0.2f + 26 * d, small);
        small.setTextSize(13 * d);
        c.drawText(sub, cx, cy + r * 0.72f, small);
        if (Math.abs(value - shown) > 5) postInvalidateOnAnimation();
    }
}
