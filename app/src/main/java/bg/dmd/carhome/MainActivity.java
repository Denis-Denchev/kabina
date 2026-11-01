package bg.dmd.carhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements LocationListener {
    static final String EXTRA_INTRO = "intro";
    @SuppressWarnings("deprecation")
    private static android.app.KeyguardManager.KeyguardLock keyguard;

    private FrameLayout root;

    private TextView clock, date, speed, status, engine;
    private bg.dmd.carhome.car.CarLink car;
    private final bg.dmd.carhome.car.CarLink.Listener carListener = new bg.dmd.carhome.car.CarLink.Listener() {
        public void onCarChanged() {
            if (car.obdConnected && car.speed >= 0) speed.setText(getString(R.string.speed_kmh, car.speed));
            StringBuilder sb = new StringBuilder();
            if (car.coolant != Integer.MIN_VALUE) sb.append(car.coolant).append(" °C   ");
            if (car.volts > 0) sb.append(String.format(Locale.ROOT, "%.1f V   ", car.volts));
            if (car.rpm >= 0) sb.append(getString(R.string.rpm_value, car.rpm));
            engine.setText(sb.toString().trim());
        }
    };
    private LocationManager lm;
    private final Handler handler = new Handler();

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateStatus(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        car = bg.dmd.carhome.car.CarLink.get(this);
        root = (FrameLayout) buildUi();
        setContentView(root);
        disableKeyguard();
        Perms.request(this);
        IntentFilter on = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenOn, on);

        // Play the logo intro once per boot (boot time is stable within ~a few seconds).
        long bootAt = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 10000;
        android.content.SharedPreferences sp = getSharedPreferences("carhome", MODE_PRIVATE);
        if (sp.getLong("introBoot", -1) != bootAt) {
            sp.edit().putLong("introBoot", bootAt).apply();
            playIntro();
        }
        // Hold the clock to replay the intro.
        clock.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { playIntro(); return true; }
        });
    }

    /** Brand "lock screen": every wake-up and every ignition-on plays the logo intro. */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { playIntro(); }
    };

    void playIntro() {
        for (int k = 0; k < root.getChildCount(); k++)
            if (root.getChildAt(k) instanceof Splash) return;
        root.addView(new Splash(this), new FrameLayout.LayoutParams(-1, -1));
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra(EXTRA_INTRO, false)) playIntro();
    }

    @SuppressWarnings("deprecation")
    private void disableKeyguard() {
        try {
            if (keyguard == null) {
                android.app.KeyguardManager km = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                keyguard = km.newKeyguardLock("carhome");
            }
            keyguard.disableKeyguard();
        } catch (Exception ignored) { }   // newer Android ignores this for secure lock screens
    }

    /**
     * Car mode: hide Android's bars and notification shade everywhere, with whatever this device allows.
     * Device owner (Android 6+): status bar disabled outright. Android 5-10 with WRITE_SECURE_SETTINGS
     * (granted once over adb): system-wide immersive mode. Android 5-7: an invisible strip blocks the shade.
     * Without any of these, only the launcher's own screens are full-screen.
     */
    static void setCarMode(Context c, boolean on) {
        android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) c.getSystemService(DEVICE_POLICY_SERVICE);
        if (Build.VERSION.SDK_INT >= 23 && dpm != null && dpm.isDeviceOwnerApp(c.getPackageName())) {
            try {
                android.content.ComponentName admin = new android.content.ComponentName(c, Admin.class);
                dpm.setStatusBarDisabled(admin, on);
                dpm.setKeyguardDisabled(admin, on);
            } catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT < 30) {   // policy_control was removed in Android 11
            try {
                android.provider.Settings.Global.putString(c.getContentResolver(), "policy_control", on ? "immersive.full=*" : null);
            } catch (SecurityException ignored) { }
        }
        // From Android 8 app overlays sit below the status bar, so the strip can no longer block the shade.
        if (Build.VERSION.SDK_INT < 26 && (Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(c))) {
            Intent svc = new Intent(c, ShadeBlocker.class);
            if (on) c.startService(svc); else c.stopService(svc);
        }
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density * Theme.scale); }

    private TextView text(float sp, String family, int color, float spacing) {
        TextView t = new TextView(this);
        t.setTextSize(sp * Theme.scale);
        t.setTypeface(Theme.font(family));
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        t.setIncludeFontPadding(false);
        return t;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Theme.page());

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(28), dp(20), dp(28), dp(16));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Top bar: logo + status
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        top.addView(logo, new LinearLayout.LayoutParams(dp(44), dp(48)));
        View word = wordmark();
        LinearLayout.LayoutParams wl = word instanceof ImageView ? new LinearLayout.LayoutParams(dp(150), dp(14)) : new LinearLayout.LayoutParams(-2, -2);
        wl.leftMargin = dp(16);
        top.addView(word, wl);
        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        status = text(15, "sans-serif-light", Theme.DIM, 0.08f);
        top.addView(status);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // Secret: hold the logo for 2 s to open all apps
        logo.setOnTouchListener(new View.OnTouchListener() {
            final Runnable open = new Runnable() { public void run() { openApps(); } };
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) handler.postDelayed(open, 2000);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) handler.removeCallbacks(open);
                return true;
            }
        });

        // Middle: clock column + tiles
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        clock = text(118, "sans-serif-thin", Theme.TEXT, -0.02f);
        date = text(19, "sans-serif-light", Theme.DIM, 0.12f);
        speed = text(40, "sans-serif-light", Theme.TEXT, 0.02f);
        left.addView(clock);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = dp(6);
        left.addView(date, dl);
        View line = new View(this);
        line.setBackgroundColor(Theme.ACCENT);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(56), dp(2));
        ll.topMargin = dp(26);
        ll.bottomMargin = dp(18);
        left.addView(line, ll);
        left.addView(speed);
        engine = text(17, "sans-serif-light", Theme.DIM, 0.06f);
        LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(-2, -2);
        el.topMargin = dp(10);
        left.addView(engine, el);
        mid.addView(left, new LinearLayout.LayoutParams(0, -2, 4.2f));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout r1 = new LinearLayout(this), r2 = new LinearLayout(this);
        r1.addView(tile(getString(R.string.tile_nav), LineIcon.NAV, Target.NAV), cell());
        r1.addView(tile(getString(R.string.tile_music), LineIcon.MUSIC, Target.MUSIC), cell());
        r1.addView(tile(getString(R.string.tile_video), LineIcon.VIDEO, Target.VIDEO), cell());
        r2.addView(tile(getString(R.string.tile_car), LineIcon.GAUGE, Target.CAR), cell());
        r2.addView(tile(getString(R.string.tile_lights), LineIcon.LIGHT, Target.LIGHTS), cell());
        r2.addView(tile(getString(R.string.tile_carplay), LineIcon.CARPLAY, Target.CARPLAY), cell());
        grid.addView(r1, new LinearLayout.LayoutParams(-1, 0, 1));
        grid.addView(r2, new LinearLayout.LayoutParams(-1, 0, 1));
        mid.addView(grid, new LinearLayout.LayoutParams(0, -1, 6.2f));

        // Bottom bar: media controls + apps/settings
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, dp(8), 0, 0);
        bottom.addView(iconButton(LineIcon.APPS, 30, new View.OnClickListener() {
            public void onClick(View v) { openApps(); }
        }));
        View s1 = new View(this);
        bottom.addView(s1, new LinearLayout.LayoutParams(0, 1, 1));
        bottom.addView(iconButton(LineIcon.PREV, 34, media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)));
        View play = iconButton(LineIcon.PLAY, 40, media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        ((LinearLayout.LayoutParams) play.getLayoutParams()).leftMargin = dp(36);
        ((LinearLayout.LayoutParams) play.getLayoutParams()).rightMargin = dp(36);
        bottom.addView(play);
        bottom.addView(iconButton(LineIcon.NEXT, 34, media(KeyEvent.KEYCODE_MEDIA_NEXT)));
        View s2 = new View(this);
        bottom.addView(s2, new LinearLayout.LayoutParams(0, 1, 1));
        bottom.addView(themeButton());
        bottom.addView(iconButton(LineIcon.SETTINGS, 32, new View.OnClickListener() {
            public void onClick(View v) { launch(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
        }));
        col.addView(bottom, new LinearLayout.LayoutParams(-1, dp(72)));

        return root;
    }

    /** The brand's wordmark image if the pack has one, otherwise the app name in spaced capitals. */
    private View wordmark() {
        int id = App.optionalDrawable("wordmark");
        if (id != 0) {
            ImageView word = new ImageView(this);
            word.setImageResource(id);
            word.setAdjustViewBounds(true);
            word.setAlpha(0.85f);
            if (Theme.day) word.setColorFilter(Theme.TEXT);
            return word;
        }
        TextView t = text(15, "sans-serif-light", Theme.TEXT, 0.4f);
        t.setText(getString(R.string.app_name).toUpperCase(Locale.getDefault()));
        t.setAlpha(0.85f);
        return t;
    }

    /** Cycles AUTO -> DAY -> NIGHT; the screen re-themes itself when the choice changes the look. */
    private View themeButton() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(14), dp(8), dp(18), dp(8));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        g.setColor(Theme.CHIP);
        g.setStroke(dp(1), Theme.CHIP_STROKE);
        b.setBackground(g);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new LineIcon(Theme.day ? LineIcon.LIGHT : LineIcon.MOON, Theme.TEXT));
        b.addView(iv, new LinearLayout.LayoutParams(dp(28), dp(28)));
        final TextView t = text(15, "sans-serif-light", Theme.TEXT, 0.12f);
        t.setText(Theme.modeLabel(this));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = dp(10);
        b.addView(t, tl);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2);
        bl.rightMargin = dp(12);
        b.setLayoutParams(bl);
        pressable(b);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int next = (Theme.mode(MainActivity.this) + 1) % 3;
                Theme.setMode(MainActivity.this, next);
                t.setText(Theme.modeLabel(MainActivity.this));   // same look (e.g. AUTO while it is day): only the label changes
            }
        });
        return b;
    }

    private LinearLayout.LayoutParams cell() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
        p.setMargins(dp(8), dp(8), dp(8), dp(8));
        return p;
    }

    private View tile(String label, int icon, final Target target) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Theme.surface(this, 22));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new LineIcon(icon, Theme.TEXT));
        t.addView(iv, new LinearLayout.LayoutParams(dp(50), dp(50)));
        TextView tv = text(20, "sans-serif-light", Theme.TEXT, 0.06f);
        tv.setText(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(16);
        t.addView(tv, lp);
        pressable(t);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (target == Target.NAV) startActivity(new Intent(MainActivity.this, bg.dmd.carhome.nav.NavActivity.class));
                else if (target == Target.CAR) startActivity(new Intent(MainActivity.this, bg.dmd.carhome.car.CarActivity.class));
                else if (target == Target.LIGHTS) startActivity(new Intent(MainActivity.this, bg.dmd.carhome.car.LightsActivity.class));
                else open(target);
            }
        });
        if (target == Target.NAV) t.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { open(Target.NAV); return true; }   // hold = Waze / other nav app
        });
        return t;
    }

    private View iconButton(int icon, int sizeDp, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new LineIcon(icon, Theme.TEXT));
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp + 24), dp(sizeDp + 24)));
        pressable(iv);
        iv.setOnClickListener(l);
        return iv;
    }

    private void pressable(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) view.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.7f).setDuration(80);
                else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120);
                return false;
            }
        });
    }

    private View.OnClickListener media(final int code) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
            }
        };
    }

    // ---- app targets ----
    enum Target { NAV, CARPLAY, VIDEO, MUSIC, CAR, LIGHTS }

    private static final String[] NAV_PKGS = {"com.waze", "com.google.android.apps.maps", "net.osmand.plus", "net.osmand", "com.sygic.aura", "com.mapswithme.maps.pro", "app.organicmaps"};
    // Lightweight clients first: on old tablets the official app no longer runs, and whoever installed one prefers it.
    private static final String[] VIDEO_PKGS = {"org.schabi.newpipe", "InfinityLoop1309.NewPipeEnhanced", "org.smarttube.stable",
            "org.smarttube.beta", "com.teamsmart.videomanager.tv", "com.google.android.youtube"};
    private static final String[] MUSIC_PKGS = {"com.spotify.music", "com.google.android.apps.youtube.music", "deezer.android.app", "com.soundcloud.android"};
    private static final String[] CARPLAY_WORDS = {"autokit", "carplay", "zlink", "carlink"};

    private void open(Target t) {
        PackageManager pm = getPackageManager();
        String[] pkgs = t == Target.NAV ? NAV_PKGS : t == Target.VIDEO ? VIDEO_PKGS : t == Target.MUSIC ? MUSIC_PKGS : new String[0];
        for (String p : pkgs) {
            Intent i = pm.getLaunchIntentForPackage(p);
            if (i != null) { launch(i); return; }
        }
        if (t == Target.CARPLAY) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String label = String.valueOf(ri.loadLabel(pm)).toLowerCase(Locale.ROOT);
                String pkg = ri.activityInfo.packageName.toLowerCase(Locale.ROOT);
                for (String w : CARPLAY_WORDS) {
                    if (label.contains(w) || pkg.contains(w)) {
                        launch(pm.getLaunchIntentForPackage(ri.activityInfo.packageName));
                        return;
                    }
                }
            }
        }
        if (t == Target.MUSIC) {
            Intent m = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
            if (!pm.queryIntentActivities(m, 0).isEmpty()) { launch(m); return; }
        }
        Toast.makeText(this, R.string.app_not_installed, Toast.LENGTH_SHORT).show();
    }

    private void launch(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void openApps() { startActivity(new Intent(this, AppsActivity.class)); }

    // ---- live info ----
    private void updateClock() {
        Date now = new Date();
        Locale loc = Locale.getDefault();
        clock.setText(new SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", loc).format(now));
        String pattern = Build.VERSION.SDK_INT >= 18 ? android.text.format.DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM") : "EEEE, d MMMM";
        date.setText(new SimpleDateFormat(pattern, loc).format(now).toUpperCase(loc));
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
        if (wi != null && wi.getNetworkId() != -1) {
            String ssid = wi.getSSID() == null ? "" : wi.getSSID().replace("\"", "");
            // Android 8.1+ hides the network name without location access
            sb.append(ssid.isEmpty() || ssid.contains("unknown ssid") ? getString(R.string.wifi) : ssid).append("   ");
        } else sb.append(getString(R.string.no_internet)).append("   ");
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int lvl = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            boolean plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (lvl >= 0) sb.append(lvl * 100 / sc).append('%').append(plugged ? " ⚡" : "");
        }
        status.setText(sb.toString());
    }

    @Override
    public void onLocationChanged(Location l) {
        if (car != null && car.obdConnected && car.speed >= 0) return;   // OBD speed wins
        if (l.hasSpeed()) speed.setText(getString(R.string.speed_kmh, Math.round(l.getSpeed() * 3.6f)));
    }
    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { speed.setText(R.string.gps_off); }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateStatus();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, f);
        speed.setText(R.string.speed_none);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (Exception e) {
            speed.setText("");
        }
        hideBars();
        car.addListener(carListener);
        car.start();
        if (!getSharedPreferences("carhome", MODE_PRIVATE).getBoolean("serviceMode", false)) setCarMode(this, true);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        car.stop();
        car.start();   // the car link and GPS can start now
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(screenOn);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(tick);
        car.removeListener(carListener);
        lm.removeUpdates(this);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideBars();
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override public void onBackPressed() { /* home screen: nowhere to go back to */ }
}
