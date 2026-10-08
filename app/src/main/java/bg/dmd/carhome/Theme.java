package bg.dmd.carhome;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;

import java.util.WeakHashMap;

/**
 * Day / night palette for every screen. Night is the original dark cockpit look; day is a light,
 * high-contrast look that stays readable in direct sun. AUTO follows the sun at the car's position,
 * overridden by the ambient light sensor (strong sun -> day, garage / tunnel -> night).
 * Screens read the colours while building; a switch recreates the visible screen.
 */
public final class Theme {
    public static final int AUTO = 0, DAY = 1, NIGHT = 2;

    public static boolean day;
    public static int version;

    public static int BG_A, BG_B, BG_C;          // page gradient (top-left -> bottom-right)
    public static int SURF_A, SURF_B, STROKE;    // cards / tiles
    public static int TEXT, DIM, FAINT;          // text: primary, secondary, tertiary
    public static int ACCENT, ON_ACCENT, CALLOUT;
    public static int CHIP, CHIP_STROKE;         // secondary buttons
    public static int TRACK, TICK;               // gauge
    public static int OK, WARN, ERR;
    public static int CARD;                      // translucent card over the 3D model / map
    public static int SCRIM;                     // full-screen panel over the map
    public static int PAGE;                      // solid page colour (photo fades into it)

    /** Layouts were drawn for an 8" landscape tablet (≥ 960 × 600 dp); smaller screens shrink everything to fit. */
    public static float scale = 1f;

    static { set(false); }

    private static void set(boolean d) {
        day = d;
        if (d) {
            BG_A = 0xFFF4F6F9; BG_B = 0xFFE9EDF2; BG_C = 0xFFDDE2E9;
            SURF_A = 0xFFFFFFFF; SURF_B = 0xFFF3F5F8; STROKE = 0x260B1220;
            TEXT = 0xFF0B0F16; DIM = 0xFF4B5563; FAINT = 0x990B1220;
            ACCENT = 0xFF0062D6; ON_ACCENT = 0xFFFFFFFF; CALLOUT = 0xFF0062D6;
            CHIP = 0x0F0B1220; CHIP_STROKE = 0x330B1220;
            TRACK = 0xFFD3D9E2; TICK = 0x990B1220;
            OK = 0xFF0B8A4A; WARN = 0xFFB86E00; ERR = 0xFFD12A1F;
            CARD = 0xEBFFFFFF; SCRIM = 0xF5EEF1F5; PAGE = 0xFFE9EDF2;
        } else {
            BG_A = 0xFF0B0E13; BG_B = 0xFF05070A; BG_C = 0xFF000000;
            SURF_A = 0xFF1A1F27; SURF_B = 0xFF0E1116; STROKE = 0x26FFFFFF;
            TEXT = 0xFFF2F4F7; DIM = 0x99F2F4F7; FAINT = 0x55FFFFFF;
            ACCENT = 0xFF4AA3FF; ON_ACCENT = 0xFF000000; CALLOUT = 0xFF9BD1FF;
            CHIP = 0x22FFFFFF; CHIP_STROKE = 0x33FFFFFF;
            TRACK = 0xFF1E232B; TICK = 0x66FFFFFF;
            OK = 0xFF6EE7A0; WARN = 0xFFFFB547; ERR = 0xFFFF5A4E;
            CARD = 0xCC0E1116; SCRIM = 0xF205070A; PAGE = 0xFF000000;
        }
    }

    // ---- shared builders ----

    public static GradientDrawable page() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{BG_A, BG_B, BG_C});
    }

    public static GradientDrawable surface(Context c, float radiusDp) {
        float d = c.getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{SURF_A, SURF_B});
        g.setCornerRadius(radiusDp * d);
        g.setStroke(Math.max(1, Math.round(d)), STROKE);
        return g;
    }

    /** Thin fonts vanish in sunlight: by day every weight moves one step heavier. */
    public static Typeface font(String family) {
        if (day) {
            if ("sans-serif-thin".equals(family)) family = "sans-serif-light";
            else if ("sans-serif-light".equals(family)) family = "sans-serif";
        }
        return Typeface.create(family, Typeface.NORMAL);
    }

    // ---- mode ----

    public static int mode(Context c) { return prefs(c).getInt("themeMode", AUTO); }

    public static void setMode(Context c, int m) {
        prefs(c).edit().putInt("themeMode", m).apply();
        evaluate(true);
    }

    public static String modeLabel(Context c) {
        int m = mode(c);
        return App.s(m == DAY ? R.string.theme_day : m == NIGHT ? R.string.theme_night : R.string.theme_auto);
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("carhome", Context.MODE_PRIVATE); }

    // ---- auto switching ----

    private static Application app;
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static float lux = -1;
    private static long brightSince, darkSince, lastFlip;
    private static Activity resumed;
    private static final WeakHashMap<Activity, Integer> built = new WeakHashMap<Activity, Integer>();

    /** Screens that must not be torn down right now (e.g. navigation in progress) implement this. */
    public interface Busy { boolean themeBusy(); }

    static void init(Application a) {
        app = a;
        android.util.DisplayMetrics m = a.getResources().getDisplayMetrics();
        float longDp = Math.max(m.widthPixels, m.heightPixels) / m.density, shortDp = Math.min(m.widthPixels, m.heightPixels) / m.density;
        scale = Math.max(0.6f, Math.min(1f, Math.min(longDp / 960f, shortDp / 600f)));
        set(computeDay(SystemClock.elapsedRealtime()));
        SensorManager sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        Sensor light = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light != null) sm.registerListener(new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) { onLux(e.values[0]); }
            public void onAccuracyChanged(Sensor s, int acc) { }
        }, light, SensorManager.SENSOR_DELAY_NORMAL);
        h.postDelayed(tick, 30000);

        a.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity act, Bundle b) { built.put(act, version); window(act); }
            public void onActivityResumed(final Activity act) {
                resumed = act;
                window(act);
                Integer v = built.get(act);
                if (v != null && v != version && !busy(act)) h.post(new Runnable() { public void run() { act.recreate(); } });
            }
            public void onActivityPaused(Activity act) { if (resumed == act) resumed = null; }
            public void onActivityStarted(Activity act) { }
            public void onActivityStopped(Activity act) { }
            public void onActivitySaveInstanceState(Activity act, Bundle b) { }
            public void onActivityDestroyed(Activity act) { built.remove(act); }
        });
    }

    private static boolean busy(Activity a) { return a instanceof Busy && ((Busy) a).themeBusy(); }

    /** Full backlight by day so the screen beats the sun; the system's own level at night. */
    private static void window(Activity a) {
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        float want = day ? 1f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want;
            a.getWindow().setAttributes(lp);
        }
    }

    private static final Runnable tick = new Runnable() {
        public void run() { evaluate(false); h.postDelayed(this, 30000); }
    };

    private static void onLux(float v) {
        long now = SystemClock.elapsedRealtime();
        lux = v;
        if (v >= 2500) { if (brightSince == 0) brightSince = now; } else brightSince = 0;
        if (v <= 8) { if (darkSince == 0) darkSince = now; } else darkSince = 0;
        evaluate(false);
    }

    private static boolean computeDay(long now) {
        int m = app == null ? AUTO : mode(app);
        if (m != AUTO) return m == DAY;
        if (brightSince != 0 && now - brightSince > 4000) return true;     // direct sun
        if (darkSince != 0 && now - darkSince > 60000) return false;       // garage / long tunnel
        return sunElevation(System.currentTimeMillis()) > 0;
    }

    static void evaluate(boolean manual) {
        long now = SystemClock.elapsedRealtime();
        boolean want = computeDay(now);
        if (want == day) return;
        if (!manual && now - lastFlip < 20000) return;                       // no flicker at the threshold
        lastFlip = now;
        set(want);
        version++;
        Activity a = resumed;
        if (a != null) {
            window(a);
            if (!busy(a)) a.recreate();
        }
    }

    // ---- sun position (good to well under a degree, plenty for dusk detection) ----

    private static double sunElevation(long ms) {
        // until a fix is known: longitude from the time zone, a mid-latitude guess
        double lat = 45, lon = java.util.TimeZone.getDefault().getRawOffset() / 3600000.0 * 15;
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (l != null) { lat = l.getLatitude(); lon = l.getLongitude(); }
        } catch (Exception ignored) { }   // no location permission yet
        double d = ms / 86400000.0 + 2440587.5 - 2451545.0;                  // days since J2000
        double g = Math.toRadians((357.529 + 0.98560028 * d) % 360);
        double q = (280.459 + 0.98564736 * d) % 360;
        double L = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.atan2(Math.cos(e) * Math.sin(L), Math.cos(L));
        double dec = Math.asin(Math.sin(e) * Math.sin(L));
        double gmst = (18.697374558 + 24.06570982441908 * d) % 24;
        double ha = Math.toRadians(gmst * 15 + lon) - ra;
        double la = Math.toRadians(lat);
        return Math.toDegrees(Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha)));
    }

    private Theme() { }
}
