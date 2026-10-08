package bg.dmd.carhome.nav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import bg.dmd.carhome.App;

/** Web-Mercator projection, distances, Google encoded polylines and localised distance text. */
public final class Geo {
    private Geo() { }

    public static final double R = 6371008.8;

    /** World pixel X at zoom z for tile size ts. */
    public static double x(double lon, int z, int ts) { return (lon + 180.0) / 360.0 * (1 << z) * ts; }

    public static double y(double lat, int z, int ts) {
        double s = Math.sin(Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat))));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * (1 << z) * ts;
    }

    public static double lon(double x, int z, int ts) { return x / ((1 << z) * (double) ts) * 360.0 - 180.0; }

    public static double lat(double y, int z, int ts) {
        double n = Math.PI - 2.0 * Math.PI * y / ((1 << z) * (double) ts);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    public static double dist(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2);
        double dp = p2 - p1, dl = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static double bearing(double la1, double lo1, double la2, double lo2) {
        double p1 = Math.toRadians(la1), p2 = Math.toRadians(la2), dl = Math.toRadians(lo2 - lo1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Decodes a Google encoded polyline into [lat, lng] pairs. */
    public static List<double[]> decode(String enc) {
        List<double[]> out = new ArrayList<double[]>();
        int i = 0, lat = 0, lng = 0;
        while (i < enc.length()) {
            int[] r = next(enc, i); lat += r[0]; i = r[1];
            r = next(enc, i); lng += r[0]; i = r[1];
            out.add(new double[]{lat / 1e5, lng / 1e5});
        }
        return out;
    }

    private static int[] next(String s, int i) {
        int result = 0, shift = 0, b;
        do {
            b = s.charAt(i++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new int[]{(result & 1) != 0 ? ~(result >> 1) : (result >> 1), i};
    }

    /** Closest point on segment a→b to p, in a local metric plane. Returns {distance m, t in [0,1]}. */
    public static double[] toSegment(double[] p, double[] a, double[] b) {
        double k = Math.cos(Math.toRadians(p[0])) * 111320.0, m = 110540.0;
        double ax = (a[1] - p[1]) * k, ay = (a[0] - p[0]) * m;
        double bx = (b[1] - p[1]) * k, by = (b[0] - p[0]) * m;
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double cx = ax + t * dx, cy = ay + t * dy;
        return new double[]{Math.sqrt(cx * cx + cy * cy), t};
    }

    public static String distText(double m) { return dist(m, false); }

    /** The same distance with the units spelled out, for the voice. */
    public static String distSpoken(double m) { return dist(m, true); }

    private static String dist(double m, boolean spoken) {
        if (m < 50) return App.s(bg.dmd.carhome.R.string.dist_now);
        if (m < 1000) {
            int r = m < 200 ? 10 : 50;
            return App.s(spoken ? bg.dmd.carhome.R.string.dist_m_spoken : bg.dmd.carhome.R.string.dist_m, (int) (Math.round(m / r) * r));
        }
        String km = m < 10000 ? String.format(Locale.getDefault(), "%.1f", m / 1000.0) : String.valueOf(Math.round(m / 1000.0));
        return App.s(spoken ? bg.dmd.carhome.R.string.dist_km_spoken : bg.dmd.carhome.R.string.dist_km, km);
    }

    public static String durText(double sec) {
        int min = (int) Math.round(sec / 60.0);
        if (min < 60) return App.s(bg.dmd.carhome.R.string.dur_min, min);
        return App.s(bg.dmd.carhome.R.string.dur_h_min, min / 60, min % 60);
    }
}
