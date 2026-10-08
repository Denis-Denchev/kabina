package bg.dmd.carhome.nav;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dark raster slippy map with rotation, follow mode, route line and the car arrow. */
public class MapView extends View {
    static final int TS = 512;                              // tiles are drawn as 512 px (hi-dpi)

    /** Where tiles come from: Google Map Tiles (dark style) when a key/session exists, else OpenStreetMap darkened. */
    public interface TileSource {
        String url(int z, int x, int y);       // null = not ready yet
        String id();                           // cache folder name
        boolean darken();                      // OSM tiles are light; invert them
    }

    public static final TileSource OSM = new TileSource() {
        public String url(int z, int x, int y) { return "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png"; }
        public String id() { return "osm"; }
        public boolean darken() { return true; }
    };

    private TileSource source = OSM;

    public void setSource(TileSource s) {
        source = s;
        mem.evictAll();
        synchronized (loading) { failed.clear(); }
        invalidate();
    }

    public interface Listener { void onUserMoved(); }

    // camera
    double lat = 42.6977, lon = 23.3219;                    // the author's home town (Sofia) until GPS answers
    double zoom = 15;
    float bearing = 0;                                      // map rotation (deg, course-up when navigating)
    float anchorY = 0.5f;                                   // where the car sits vertically (0.68 when navigating)
    boolean follow = true;

    // overlays
    double carLat = Double.NaN, carLon, carHeading;
    List<double[]> route;
    double[] dest;

    private Listener listener;
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final Set<String> loading = new HashSet<String>();
    private final java.util.Map<String, Long> failed = new java.util.HashMap<String, Long>();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File disk;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint darkPaint = new Paint(Paint.FILTER_BITMAP_FLAG), dayPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean paintedDay;
    private final Paint routeCasing = new Paint(Paint.ANTI_ALIAS_FLAG), routeLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint car = new Paint(Paint.ANTI_ALIAS_FLAG), carGlow = new Paint(Paint.ANTI_ALIAS_FLAG), pin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attrib = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float d;
    private final ScaleGestureDetector scaler;
    private final GestureDetector pan;

    public MapView(Context c) {
        super(c);
        Net.init(c);
        d = c.getResources().getDisplayMetrics().density;
        disk = new File(c.getCacheDir(), "tiles");
        // invert + desaturate + tint blue-grey: turns light OSM tiles into a night map
        android.graphics.ColorMatrix inv = new android.graphics.ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,  0, -1, 0, 0, 255,  0, 0, -1, 0, 255,  0, 0, 0, 1, 0});
        android.graphics.ColorMatrix sat = new android.graphics.ColorMatrix();
        sat.setSaturation(0.15f);
        android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix(new float[]{
                0.55f, 0, 0, 0, 2,  0, 0.6f, 0, 0, 4,  0, 0, 0.7f, 0, 8,  0, 0, 0, 1, 0});
        inv.postConcat(sat);
        inv.postConcat(dim);
        darkPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(inv));
        // by day: OSM as is, slightly calmed down so the blue route stands out
        android.graphics.ColorMatrix calm = new android.graphics.ColorMatrix();
        calm.setSaturation(0.55f);
        dayPaint.setColorFilter(new android.graphics.ColorMatrixColorFilter(calm));
        routeCasing.setStyle(Paint.Style.STROKE);
        routeCasing.setStrokeCap(Paint.Cap.ROUND);
        routeCasing.setStrokeJoin(Paint.Join.ROUND);
        routeCasing.setColor(0xFF0A2A4D);
        routeCasing.setStrokeWidth(12 * d);
        routeLine.set(routeCasing);
        routeLine.setColor(0xFF4AA3FF);
        routeLine.setStrokeWidth(7 * d);
        car.setColor(Color.WHITE);
        carGlow.setColor(0x664AA3FF);
        pin.setColor(0xFF4AA3FF);
        attrib.setColor(0x80FFFFFF);
        attrib.setTextSize(9 * d);
        attrib.setTextAlign(Paint.Align.RIGHT);

        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector g) {
                setZoom(zoom + Math.log(g.getScaleFactor()) / Math.log(2));
                userMoved();
                return true;
            }
        });
        pan = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                panBy(dx, dy);
                userMoved();
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e) {
                setZoom(zoom + 1);
                return true;
            }
        });
    }

    public void setListener(Listener l) { listener = l; }

    private void userMoved() {
        if (follow) { follow = false; if (listener != null) listener.onUserMoved(); }
    }

    public void setZoom(double z) { zoom = Math.max(3, Math.min(18.5, z)); invalidate(); }

    private void panBy(float dx, float dy) {
        double r = Math.toRadians(bearing);
        double wx = dx * Math.cos(r) - dy * Math.sin(r);
        double wy = dx * Math.sin(r) + dy * Math.cos(r);
        int zi = (int) Math.floor(zoom);
        double s = Math.pow(2, zoom - zi);
        double cx = Geo.x(lon, zi, TS) + wx / s, cy = Geo.y(lat, zi, TS) + wy / s;
        lon = Geo.lon(cx, zi, TS);
        lat = Geo.lat(cy, zi, TS);
        invalidate();
    }

    public void setCar(double la, double lo, double heading) {
        carLat = la; carLon = lo; carHeading = heading;
        if (follow) { lat = la; lon = lo; }
        invalidate();
    }

    public void recenter() { follow = true; if (!Double.isNaN(carLat)) { lat = carLat; lon = carLon; } invalidate(); }

    /** Zooms out so the whole route fits on screen. */
    public void fit(List<double[]> pts) {
        double n = -90, s = 90, e = -180, w = 180;
        for (double[] p : pts) { n = Math.max(n, p[0]); s = Math.min(s, p[0]); e = Math.max(e, p[1]); w = Math.min(w, p[1]); }
        lat = (n + s) / 2; lon = (e + w) / 2; bearing = 0; anchorY = 0.5f; follow = false;
        double z = 17;
        while (z > 4) {
            int zi = (int) z;
            double dx = Geo.x(e, zi, TS) - Geo.x(w, zi, TS), dy = Geo.y(s, zi, TS) - Geo.y(n, zi, TS);
            if (dx < getWidth() * 0.75 && dy < getHeight() * 0.6) break;
            z -= 0.25;
        }
        setZoom(z);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) pan.onTouchEvent(e);
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        int zi = (int) Math.floor(zoom);
        double s = Math.pow(2, zoom - zi);
        double cx = Geo.x(lon, zi, TS), cy = Geo.y(lat, zi, TS);
        float ax = w / 2f, ay = h * anchorY;

        c.save();
        c.translate(ax, ay);
        c.rotate(-bearing);
        c.scale((float) s, (float) s);

        double radius = Math.hypot(w, h) / s;
        int n = 1 << zi;
        int x0 = (int) Math.floor((cx - radius) / TS), x1 = (int) Math.floor((cx + radius) / TS);
        int y0 = Math.max(0, (int) Math.floor((cy - radius) / TS)), y1 = Math.min(n - 1, (int) Math.floor((cy + radius) / TS));
        RectF dst = new RectF();
        if (paintedDay != bg.dmd.carhome.Theme.day || getBackground() == null) themeColors();
        Paint tp = !source.darken() ? tilePaint : paintedDay ? dayPaint : darkPaint;
        for (int ty = y0; ty <= y1; ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                int wx = ((tx % n) + n) % n;
                float left = (float) (tx * TS - cx), top = (float) (ty * TS - cy);
                dst.set(left, top, left + TS, top + TS);
                Bitmap b = tile(zi, wx, ty, true);
                if (b != null) { c.drawBitmap(b, null, dst, tp); continue; }
                // fall back to a scaled piece of the parent tile while this one loads
                if (zi > 0) {
                    Bitmap p = tile(zi - 1, wx / 2, ty / 2, false);
                    if (p != null) {
                        int half = p.getWidth() / 2;
                        Rect src = new Rect((wx % 2) * half, (ty % 2) * half, (wx % 2) * half + half, (ty % 2) * half + half);
                        c.drawBitmap(p, src, dst, tp);
                    }
                }
            }
        }

        float inv = (float) (1 / s);
        if (route != null && route.size() > 1) {
            Path path = new Path();
            for (int i = 0; i < route.size(); i++) {
                double[] p = route.get(i);
                float px = (float) (Geo.x(p[1], zi, TS) - cx), py = (float) (Geo.y(p[0], zi, TS) - cy);
                if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
            }
            routeCasing.setStrokeWidth(12 * d * inv);
            routeLine.setStrokeWidth(7 * d * inv);
            c.drawPath(path, routeCasing);
            c.drawPath(path, routeLine);
        }
        if (dest != null) {
            float px = (float) (Geo.x(dest[1], zi, TS) - cx), py = (float) (Geo.y(dest[0], zi, TS) - cy);
            c.drawCircle(px, py, 11 * d * inv, routeCasing);
            c.drawCircle(px, py, 8 * d * inv, pin);
            c.drawCircle(px, py, 3 * d * inv, car);
        }
        if (!Double.isNaN(carLat)) {
            float px = (float) (Geo.x(carLon, zi, TS) - cx), py = (float) (Geo.y(carLat, zi, TS) - cy);
            c.save();
            c.translate(px, py);
            c.scale(inv, inv);
            c.drawCircle(0, 0, 26 * d, carGlow);
            c.rotate((float) carHeading);
            Path a = new Path();
            a.moveTo(0, -16 * d);
            a.lineTo(12 * d, 13 * d);
            a.lineTo(0, 6 * d);
            a.lineTo(-12 * d, 13 * d);
            a.close();
            c.drawPath(a, car);
            c.restore();
        }
        c.restore();
        c.drawText(source.darken() ? "© OpenStreetMap contributors" : "Map data © Google", w - 6 * d, h - 6 * d, attrib);
    }

    /** Overlay colours follow the theme live, even mid-route. */
    private void themeColors() {
        boolean day = paintedDay = bg.dmd.carhome.Theme.day;
        setBackgroundColor(day ? 0xFFE9EDF2 : 0xFF0E1013);
        routeCasing.setColor(day ? 0xFF003E8A : 0xFF0A2A4D);
        routeLine.setColor(day ? 0xFF1A73E8 : 0xFF4AA3FF);
        pin.setColor(day ? 0xFF1A73E8 : 0xFF4AA3FF);
        car.setColor(day ? 0xFF0B0F16 : Color.WHITE);
        carGlow.setColor(day ? 0x551A73E8 : 0x664AA3FF);
        attrib.setColor(day ? 0x990B1220 : 0x80FFFFFF);
    }

    private Bitmap tile(final int z, final int x, final int y, boolean fetch) {
        final TileSource src = source;
        final String url = src.url(z, x, y);
        final String key = src.id() + "/" + z + "/" + x + "/" + y;
        Bitmap b = mem.get(key);
        if (b != null || !fetch || url == null) return b;
        synchronized (loading) {
            if (loading.contains(key)) return null;
            Long f = failed.get(key);
            if (f != null && System.currentTimeMillis() - f < 15000) return null;
            loading.add(key);
        }
        pool.execute(new Runnable() {
            public void run() {
                Bitmap bm = null;
                try {
                    File f = new File(disk, key + ".png");
                    if (f.exists()) bm = BitmapFactory.decodeFile(f.getPath());
                    if (bm == null) {
                        byte[] data = Net.get(url);
                        bm = BitmapFactory.decodeByteArray(data, 0, data.length);
                        f.getParentFile().mkdirs();
                        FileOutputStream o = new FileOutputStream(f);
                        o.write(data);
                        o.close();
                    }
                } catch (Exception ignored) { }
                final Bitmap res = bm;
                ui.post(new Runnable() {
                    public void run() {
                        synchronized (loading) {
                            loading.remove(key);
                            if (res == null) failed.put(key, System.currentTimeMillis()); else failed.remove(key);
                        }
                        if (res != null) { mem.put(key, res); invalidate(); }
                    }
                });
            }
        });
        return null;
    }
}
