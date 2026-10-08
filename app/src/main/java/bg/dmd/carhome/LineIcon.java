package bg.dmd.carhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Thin monochrome line icons, drawn in a 100x100 box and scaled to bounds. */
public class LineIcon extends Drawable {
    public static final int NAV = 0, CARPLAY = 1, VIDEO = 2, MUSIC = 3, APPS = 4,
            PREV = 5, PLAY = 6, NEXT = 7, SETTINGS = 8, WIFI = 9, GAUGE = 10, LIGHT = 11, MOON = 12;

    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LineIcon(int kind, int color) {
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(color);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        float s = Math.min(b.width(), b.height()) / 100f;
        c.save();
        c.translate(b.centerX() - 50 * s, b.centerY() - 50 * s);
        c.scale(s, s);
        stroke.setStrokeWidth(5f);
        Path p = new Path();
        switch (kind) {
            case NAV:
                p.moveTo(50, 8);
                p.lineTo(84, 90);
                p.lineTo(50, 72);
                p.lineTo(16, 90);
                p.close();
                c.drawPath(p, stroke);
                break;
            case CARPLAY:
                c.drawRoundRect(new RectF(14, 14, 86, 86), 18, 18, stroke);
                fill.setAlpha(255);
                p.moveTo(40, 34);
                p.lineTo(68, 50);
                p.lineTo(40, 66);
                p.close();
                c.drawPath(p, fill);
                break;
            case VIDEO:
                c.drawRoundRect(new RectF(8, 22, 92, 78), 20, 20, stroke);
                p.moveTo(42, 36);
                p.lineTo(64, 50);
                p.lineTo(42, 64);
                p.close();
                c.drawPath(p, fill);
                break;
            case MUSIC:
                c.drawCircle(30, 74, 12, stroke);
                c.drawCircle(74, 64, 12, stroke);
                p.moveTo(42, 74);
                p.lineTo(42, 18);
                p.lineTo(86, 10);
                p.lineTo(86, 64);
                c.drawPath(p, stroke);
                c.drawLine(42, 32, 86, 24, stroke);
                break;
            case APPS:
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++)
                        c.drawCircle(22 + i * 28, 22 + j * 28, 7, fill);
                break;
            case PREV:
                c.drawLine(22, 22, 22, 78, stroke);
                p.moveTo(80, 22);
                p.lineTo(34, 50);
                p.lineTo(80, 78);
                p.close();
                c.drawPath(p, fill);
                break;
            case PLAY:
                p.moveTo(34, 18);
                p.lineTo(82, 50);
                p.lineTo(34, 82);
                p.close();
                c.drawPath(p, fill);
                break;
            case NEXT:
                c.drawLine(78, 22, 78, 78, stroke);
                p.moveTo(20, 22);
                p.lineTo(66, 50);
                p.lineTo(20, 78);
                p.close();
                c.drawPath(p, fill);
                break;
            case SETTINGS:
                c.drawCircle(50, 50, 14, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    c.drawLine((float) (50 + 26 * Math.cos(a)), (float) (50 + 26 * Math.sin(a)),
                            (float) (50 + 40 * Math.cos(a)), (float) (50 + 40 * Math.sin(a)), stroke);
                }
                c.drawCircle(50, 50, 28, stroke);
                break;
            case GAUGE:
                c.drawArc(new RectF(10, 18, 90, 98), 150, 240, false, stroke);
                c.drawLine(50, 58, 72, 36, stroke);
                c.drawCircle(50, 58, 6, fill);
                break;
            case MOON:                                   // crescent: big arc minus an offset arc
                p.addCircle(50, 50, 34, Path.Direction.CW);
                Path cut = new Path();
                cut.addCircle(66, 38, 28, Path.Direction.CW);
                p.op(cut, Path.Op.DIFFERENCE);
                c.drawPath(p, stroke);
                break;
            case LIGHT:
                c.drawCircle(50, 50, 16, stroke);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    c.drawLine((float) (50 + 26 * Math.cos(a)), (float) (50 + 26 * Math.sin(a)),
                            (float) (50 + 38 * Math.cos(a)), (float) (50 + 38 * Math.sin(a)), stroke);
                }
                break;
            case WIFI:
                RectF r1 = new RectF(10, 30, 90, 110);
                RectF r2 = new RectF(26, 46, 74, 94);
                c.drawArc(r1, 225, 90, false, stroke);
                c.drawArc(r2, 225, 90, false, stroke);
                c.drawCircle(50, 78, 6, fill);
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { stroke.setAlpha(a); fill.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
