package bg.dmd.carhome.car;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** Hue ring with a glowing centre: drag on the ring to pick a colour, tap the centre to switch on/off. */
public class ColorRing extends View {
    public interface Listener { void onColor(int rgb); void onCenterTap(); }

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG), glow = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private float hue = 210;
    private int color = 0x4AA3FF;
    private boolean on = true;
    private Listener listener;
    private boolean dragging;

    public ColorRing(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        knob.setStyle(Paint.Style.STROKE);
        knob.setColor(Color.WHITE);
        knob.setStrokeWidth(4 * d);
        label.setColor(0xCC000000);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(18 * d);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
    }

    public void setListener(Listener l) { listener = l; }

    public void set(int rgb, boolean on) {
        color = rgb & 0xFFFFFF;
        this.on = on;
        float[] hsv = new float[3];
        Color.colorToHSV(0xFF000000 | color, hsv);
        if (hsv[1] > 0.1f) hue = hsv[0];
        invalidate();
    }

    private float radius() { return Math.min(getWidth(), getHeight()) / 2f - 24 * d; }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
        float sw = 34 * d;
        int[] hues = new int[13];
        for (int i = 0; i <= 12; i++) hues[i] = Color.HSVToColor(new float[]{i * 30f, 1f, 1f});
        ring.setShader(new SweepGradient(cx, cy, hues, null));
        ring.setStrokeWidth(sw);
        ring.setAlpha(on ? 255 : 90);
        c.drawCircle(cx, cy, r, ring);

        double a = Math.toRadians(hue);
        float kx = cx + (float) Math.cos(a) * r, ky = cy + (float) Math.sin(a) * r;
        c.drawCircle(kx, ky, sw / 2f + 2 * d, knob);

        float cr = r - sw - 22 * d;
        int full = 0xFF000000 | color;
        glow.setColor(on ? (full & 0x00FFFFFF) | 0x55000000 : 0x22FFFFFF);
        c.drawCircle(cx, cy, cr + 14 * d, glow);
        center.setColor(on ? full : 0xFF1E232B);
        c.drawCircle(cx, cy, cr, center);
        label.setColor(on ? (luma(color) > 140 ? 0xCC000000 : 0xDDFFFFFF) : 0x99F2F4F7);
        c.drawText(bg.dmd.carhome.App.s(on ? bg.dmd.carhome.R.string.ring_on : bg.dmd.carhome.R.string.ring_off), cx, cy + 6 * d, label);
    }

    private static int luma(int c) { return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float dx = e.getX() - cx, dy = e.getY() - cy;
        float dist = (float) Math.hypot(dx, dy), r = radius();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                dragging = dist > r - 40 * d && dist < r + 40 * d;
                if (!dragging && dist < r - 60 * d) {
                    if (listener != null) listener.onCenterTap();
                    return true;
                }
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    hue = (float) ((Math.toDegrees(Math.atan2(dy, dx)) + 360) % 360);
                    color = Color.HSVToColor(new float[]{hue, 1f, 1f}) & 0xFFFFFF;
                    on = true;
                    invalidate();
                    if (listener != null) listener.onColor(color);
                }
                return true;
            default:
                dragging = false;
                return true;
        }
    }
}
