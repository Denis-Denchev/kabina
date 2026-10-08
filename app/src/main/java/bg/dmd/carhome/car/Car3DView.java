package bg.dmd.carhome.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Glowing wireframe of a compact van that slowly turns on a lit floor; drag to spin it.
 * Live values hang off the car on leader lines pointing at where they come from.
 */
public class Car3DView extends View {
    // model space: x = length (front 0 → rear 4.38 m), y = width (+ left), z = height
    private final List<float[]> edges = new ArrayList<float[]>();   // {x1,y1,z1,x2,y2,z2}
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG), card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint t1 = new Paint(Paint.ANTI_ALIAS_FLAG), t2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float yaw = -35, lastX;
    private long touchAt;
    private boolean alert;

    /** Callouts: anchor point on the car, title, value. */
    private static final float[][] ANCHORS = {{0.55f, 0.0f, 1.0f}, {0.35f, 0.55f, 0.95f}, {0.02f, 0.0f, 0.5f}, {2.1f, 0.3f, 1.25f}, {3.45f, -0.75f, 0.55f}};
    private final String[] titles = {s(bg.dmd.carhome.R.string.callout_engine), s(bg.dmd.carhome.R.string.callout_battery),
            s(bg.dmd.carhome.R.string.callout_intake), s(bg.dmd.carhome.R.string.callout_load), s(bg.dmd.carhome.R.string.callout_fuel)};

    private static String s(int id) { return bg.dmd.carhome.App.s(id); }
    private final String[] values = {"—", "—", "—", "—", "—"};
    private final boolean[] warn = new boolean[5];

    public Car3DView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        glow.set(line);
        t1.setColor(bg.dmd.carhome.Theme.DIM);
        t1.setTextSize(10.5f * d);
        t1.setLetterSpacing(0.2f);
        t1.setTypeface(bg.dmd.carhome.Theme.font("sans-serif-light"));
        t2.setColor(bg.dmd.carhome.Theme.TEXT);
        t2.setTextSize(22 * d);
        t2.setTypeface(bg.dmd.carhome.Theme.font("sans-serif-light"));
        buildVan();
    }

    public void set(int i, String value, boolean warning) { values[i] = value; warn[i] = warning; }

    private float[] external;   // when set: overlay mode, only callouts, anchors come from the GL renderer
    public void useExternalAnchors(float[] screenPts) { external = screenPts; }
    public void setAlert(boolean a) { alert = a; }

    private void e(float x1, float y1, float z1, float x2, float y2, float z2) { edges.add(new float[]{x1, y1, z1, x2, y2, z2}); }

    private void buildVan() {
        // side profile (front → over the roof → rear → under), with half-widths
        float[][] prof = {
                {0.00f, 0.30f, 0.86f}, {0.00f, 0.62f, 0.88f}, {0.14f, 0.88f, 0.88f}, {0.95f, 1.03f, 0.86f},
                {1.72f, 1.76f, 0.78f}, {4.24f, 1.82f, 0.80f}, {4.38f, 1.74f, 0.84f}, {4.38f, 0.34f, 0.88f},
                {4.18f, 0.26f, 0.88f}, {0.22f, 0.26f, 0.86f}};
        int n = prof.length;
        for (int i = 0; i < n; i++) {
            float[] a = prof[i], b = prof[(i + 1) % n];
            for (int s = -1; s <= 1; s += 2) e(a[0], s * a[2], a[1], b[0], s * b[2], b[1]);
            e(a[0], -a[2], a[1], a[0], a[2], a[1]);                       // cross members
        }
        // belt line along each side
        for (int s = -1; s <= 1; s += 2) {
            float y = s * 0.87f;
            e(0.95f, y, 1.03f, 4.38f, s * 0.86f, 1.08f);
            // side windows: front door, sliding door, rear quarter
            e(1.05f, s * 0.84f, 1.08f, 1.68f, s * 0.79f, 1.70f);
            e(1.68f, s * 0.79f, 1.70f, 2.55f, s * 0.79f, 1.72f);
            e(2.55f, s * 0.79f, 1.72f, 2.55f, s * 0.85f, 1.10f);
            e(2.70f, s * 0.80f, 1.73f, 3.60f, s * 0.80f, 1.74f);
            e(3.60f, s * 0.80f, 1.74f, 3.60f, s * 0.86f, 1.10f);
            e(2.70f, s * 0.80f, 1.73f, 2.70f, s * 0.86f, 1.10f);
            e(3.75f, s * 0.81f, 1.74f, 4.22f, s * 0.81f, 1.76f);
            e(4.22f, s * 0.81f, 1.76f, 4.22f, s * 0.86f, 1.12f);
            e(3.75f, s * 0.81f, 1.74f, 3.75f, s * 0.86f, 1.12f);
            // sliding door rail + door cut lines
            e(2.62f, s * 0.88f, 0.30f, 2.62f, s * 0.87f, 1.08f);
            e(3.66f, s * 0.88f, 0.30f, 3.66f, s * 0.87f, 1.08f);
            e(2.62f, s * 0.89f, 0.95f, 4.30f, s * 0.89f, 0.95f);
            // wheels + arches
            wheel(0.86f, s * 0.89f);
            wheel(3.55f, s * 0.89f);
            // headlight + tail light
            e(0.06f, s * 0.55f, 0.74f, 0.22f, s * 0.82f, 0.86f);
            e(0.22f, s * 0.82f, 0.86f, 0.42f, s * 0.84f, 0.90f);
            e(4.38f, s * 0.80f, 0.85f, 4.38f, s * 0.80f, 1.35f);
        }
        // rear doors split + grille
        e(4.38f, 0f, 0.34f, 4.38f, 0f, 1.74f);
        e(0.0f, -0.45f, 0.50f, 0.0f, 0.45f, 0.50f);
        e(0.02f, -0.40f, 0.72f, 0.02f, 0.40f, 0.72f);
        // windscreen frame + roof bars
        e(0.95f, -0.86f, 1.03f, 0.95f, 0.86f, 1.03f);
        e(1.72f, -0.78f, 1.76f, 1.72f, 0.78f, 1.76f);
        for (int s = -1; s <= 1; s += 2) e(1.9f, s * 0.6f, 1.86f, 4.1f, s * 0.6f, 1.90f);
    }

    private void wheel(float cx, float y) {
        int seg = 18;
        float r = 0.31f, cz = 0.31f;
        for (int i = 0; i < seg; i++) {
            double a1 = 2 * Math.PI * i / seg, a2 = 2 * Math.PI * (i + 1) / seg;
            e(cx + (float) Math.cos(a1) * r, y, cz + (float) Math.sin(a1) * r, cx + (float) Math.cos(a2) * r, y, cz + (float) Math.sin(a2) * r);
            if (i % 3 == 0) e(cx, y, cz, cx + (float) Math.cos(a1) * r * 0.55f, y, cz + (float) Math.sin(a1) * r * 0.55f);
        }
        for (int i = 0; i <= 8; i++) {                 // arch
            double a1 = Math.PI * i / 9, a2 = Math.PI * (i + 1) / 9;
            float rr = 0.40f;
            e(cx + (float) Math.cos(a1) * rr, y, cz + (float) Math.sin(a1) * rr, cx + (float) Math.cos(a2) * rr, y, cz + (float) Math.sin(a2) * rr);
        }
    }

    // projection
    private float pw, ph, scale, cosY, sinY, cosP, sinP;
    private final float[] out = new float[3];

    private void project(float x, float y, float z) {
        float mx = x - 2.19f, my = y, mz = z - 0.9f;          // centre the van
        float rx = mx * cosY - my * sinY, ry = mx * sinY + my * cosY;
        float ry2 = ry * cosP - mz * sinP, rz2 = ry * sinP + mz * cosP;
        float depth = 9f + ry2;
        out[0] = pw / 2 + rx * scale * 9f / depth;
        out[1] = ph * 0.52f - rz2 * scale * 9f / depth;
        out[2] = depth;
    }

    @Override
    protected void onDraw(Canvas c) {
        pw = getWidth(); ph = getHeight();
        scale = Math.min(pw / 7.2f, ph / 3.9f);
        long now = SystemClock.uptimeMillis();
        if (now - touchAt > 2500) yaw += 0.12f;                // idle spin
        double yr = Math.toRadians(yaw), pr = Math.toRadians(-16);
        cosY = (float) Math.cos(yr); sinY = (float) Math.sin(yr);
        cosP = (float) Math.cos(pr); sinP = (float) Math.sin(pr);

        int base = alert ? ((now / 600) % 2 == 0 ? bg.dmd.carhome.Theme.WARN : bg.dmd.carhome.Theme.ERR) : bg.dmd.carhome.Theme.ACCENT;
        if (external != null) { drawCallouts(c); postInvalidateDelayed(33); return; }

        // lit floor
        project(2.19f, 0, 0);
        floor.setShader(new RadialGradient(out[0], out[1], scale * 2.6f, (base & 0xFFFFFF) | 0x33000000, 0, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(out[0] - scale * 2.8f, out[1] - scale * 0.7f, out[0] + scale * 2.8f, out[1] + scale * 0.7f), floor);
        floor.setShader(null);
        line.setStrokeWidth(1 * d);
        for (int k = 1; k <= 3; k++) {
            line.setColor((base & 0xFFFFFF) | (0x26 / k << 24));
            float rx = scale * 0.85f * k, rz = scale * 0.22f * k;
            c.drawOval(new RectF(out[0] - rx, out[1] - rz, out[0] + rx, out[1] + rz), line);
        }

        // edges: soft glow pass, then crisp core, both fading with depth
        for (int pass = 0; pass < 2; pass++) {
            Paint pp = pass == 0 ? glow : line;
            pp.setStrokeWidth(pass == 0 ? 6 * d : 1.5f * d);
            for (float[] e : edges) {
                project(e[0], e[1], e[2]);
                float x1 = out[0], y1 = out[1], d1 = out[2];
                project(e[3], e[4], e[5]);
                float near = Math.max(0.25f, Math.min(1f, (11.5f - (d1 + out[2]) / 2) / 4.5f));
                int alpha = (int) ((pass == 0 ? 40 : 235) * near);
                pp.setColor((alpha << 24) | (base & 0xFFFFFF));
                c.drawLine(x1, y1, out[0], out[1], pp);
            }
        }
        // badge
        project(0.0f, 0f, 0.82f);
        glow.setStrokeWidth(10 * d);
        glow.setColor(0x88FFFFFF);
        c.drawPoint(out[0], out[1], glow);

        drawCallouts(c);
        postInvalidateOnAnimation();
    }

    private void drawCallouts(Canvas c) {
        for (int i = 0; i < ANCHORS.length; i++) {
            float ax, ay;
            if (external != null) { ax = external[i * 2]; ay = external[i * 2 + 1]; }
            else { project(ANCHORS[i][0], ANCHORS[i][1], ANCHORS[i][2]); ax = out[0]; ay = out[1]; }
            boolean leftSide = i % 2 == 0;                 // alternate sides, stacked, clear of the van
            float lx = leftSide ? 0 : pw - 136 * d;
            int slot = i / 2;
            float ly = leftSide ? 10 * d + slot * (ph - 60 * d) / 2.2f : 40 * d + slot * (ph - 60 * d) / 1.6f;
            int col = warn[i] ? bg.dmd.carhome.Theme.WARN : bg.dmd.carhome.Theme.CALLOUT;
            line.setStrokeWidth(1 * d);
            line.setColor((col & 0xFFFFFF) | 0x88000000);
            float ex = leftSide ? lx + 136 * d : lx;
            c.drawLine(ax, ay, ex, ly + 22 * d, line);
            card.setColor(col);
            c.drawCircle(ax, ay, 3.5f * d, card);
            card.setColor(bg.dmd.carhome.Theme.CARD);
            c.drawRoundRect(new RectF(lx, ly, lx + 136 * d, ly + 48 * d), 12 * d, 12 * d, card);
            line.setColor(bg.dmd.carhome.Theme.STROKE);
            c.drawRoundRect(new RectF(lx, ly, lx + 136 * d, ly + 48 * d), 12 * d, 12 * d, line);
            c.drawText(titles[i], lx + 12 * d, ly + 16 * d, t1);
            t2.setColor(warn[i] ? bg.dmd.carhome.Theme.WARN : bg.dmd.carhome.Theme.TEXT);
            c.drawText(values[i], lx + 12 * d, ly + 40 * d, t2);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (external != null) return false;   // overlay: let touches fall through to the GL model
        touchAt = SystemClock.uptimeMillis();
        if (e.getAction() == MotionEvent.ACTION_MOVE) yaw -= (e.getX() - lastX) * 0.4f;
        lastX = e.getX();
        return true;
    }
}
