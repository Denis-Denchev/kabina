package bg.dmd.carhome.nav;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Big white turn arrow for a Routes API maneuver name. */
public class ManeuverIcon extends Drawable {
    private String maneuver = "STRAIGHT";
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ManeuverIcon() {
        p.setColor(bg.dmd.carhome.Theme.TEXT);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    public void set(String m) { maneuver = m == null ? "STRAIGHT" : m; invalidateSelf(); }

    private static float angle(String m) {
        if (m.contains("SHARP")) return m.contains("LEFT") ? -135 : 135;
        if (m.contains("SLIGHT") || m.contains("RAMP") || m.contains("FORK") || m.contains("MERGE")) return m.contains("LEFT") ? -45 : 45;
        if (m.contains("LEFT")) return -90;
        if (m.contains("RIGHT")) return 90;
        return 0;
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 100f;
        c.save();
        c.translate(b.centerX() - 50 * s, b.centerY() - 50 * s);
        c.scale(s, s);
        p.setStrokeWidth(9);
        Path path = new Path();
        if (maneuver.startsWith("UTURN")) {
            boolean left = maneuver.contains("LEFT");
            c.save();
            if (!left) c.scale(-1, 1, 50, 50);
            Path u = new Path();
            u.moveTo(66, 92);
            u.lineTo(66, 42);
            u.arcTo(new RectF(34, 26, 66, 58), 0, -180);
            u.lineTo(34, 64);
            c.drawPath(u, p);
            arrowHead(c, 34, 70, 180);
            c.restore();
        } else if (maneuver.startsWith("ROUNDABOUT")) {
            c.drawCircle(50, 46, 16, p);
            float a = angle(maneuver);
            c.drawLine(50, 92, 50, 62, p);
            double r = Math.toRadians(a - 90);
            float ex = (float) (50 + 16 * Math.cos(r)), ey = (float) (46 + 16 * Math.sin(r));
            float tx = (float) (50 + 40 * Math.cos(r)), ty = (float) (46 + 40 * Math.sin(r));
            c.drawLine(ex, ey, tx, ty, p);
            arrowHead(c, tx, ty, a);
        } else if (maneuver.equals("DESTINATION")) {
            c.drawCircle(50, 40, 18, p);
            c.drawLine(50, 58, 50, 92, p);
        } else {
            float a = angle(maneuver);
            path.moveTo(50, 92);
            path.lineTo(50, 58);
            double r = Math.toRadians(a - 90);
            float tx = (float) (50 + 44 * Math.cos(r)), ty = (float) (58 + 44 * Math.sin(r));
            path.lineTo(tx, ty);
            c.drawPath(path, p);
            arrowHead(c, tx, ty, a);
        }
        c.restore();
    }

    private void arrowHead(Canvas c, float x, float y, float angle) {
        c.save();
        c.translate(x, y);
        c.rotate(angle);
        Path h = new Path();
        h.moveTo(-16, 12);
        h.lineTo(0, -6);
        h.lineTo(16, 12);
        c.drawPath(h, p);
        c.restore();
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
