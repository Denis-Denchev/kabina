package bg.dmd.carhome.nav;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import bg.dmd.carhome.LineIcon;
import bg.dmd.carhome.R;
import bg.dmd.carhome.Theme;

/** Built-in navigation: dark map, Google search + routes, spoken turn-by-turn in the phone's language. */
public class NavActivity extends Activity implements LocationListener, MapView.Listener, Theme.Busy {

    private MapView map;
    private LinearLayout searchPill, maneuverCard, bottomBar, previewCard, searchPanel, results;
    private TextView manDist, manText, etaClock, etaRest, speedView, previewTitle, previewInfo, recenterBtn;
    private ManeuverIcon manIcon;
    private EditText query;

    private GoogleApi api;
    private LocationManager lm;
    private TextToSpeech tts;
    private boolean voice;
    private final Handler h = new Handler();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Location last;
    private float heading;
    private GoogleApi.Place target;
    private GoogleApi.Route route;
    private double[] cum;               // cumulative metres along route.points
    private int idx;                    // current segment
    private int offCount;
    private long lastReroute;
    private boolean navigating;

    public boolean themeBusy() { if (navigating) map.invalidate(); return navigating; }
    private final Set<String> spoken = new HashSet<String>();

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density * Theme.scale); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        api = new GoogleApi(mapsKey());
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        setContentView(buildUi());
        Location l = null;
        try { l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Exception ignored) { }   // no permission yet
        if (l != null) { last = l; map.setCar(l.getLatitude(), l.getLongitude(), 0); }
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) return;
                int r = tts.setLanguage(Locale.getDefault());
                voice = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });
        showBrowse();
        loadGoogleTiles();
    }

    /** Switch the map to Google's tiles (day or night style); the session token is cached until it expires. */
    private void loadGoogleTiles() {
        if (mapsKey().length() < 10) return;
        final SharedPreferences sp = getSharedPreferences("nav", MODE_PRIVATE);
        final String mode = Theme.day ? "Day" : "";
        long exp = sp.getLong("tileExp" + mode, 0);
        String tok = sp.getString("tileSession" + mode, null);
        if (tok != null && exp - System.currentTimeMillis() / 1000 > 3600) { map.setSource(api.tiles(tok)); return; }
        io.execute(new Runnable() {
            public void run() {
                try {
                    final String[] s = api.tileSession();
                    sp.edit().putString("tileSession" + mode, s[0]).putLong("tileExp" + mode, Long.parseLong(s[1])).apply();
                    h.post(new Runnable() { public void run() { map.setSource(api.tiles(s[0])); } });
                } catch (Exception ignored) { }   // stay on the OSM night map
            }
        });
    }

    // ------------------------------------------------------------------ UI

    private TextView text(float sp, String fam, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp * Theme.scale);
        t.setTypeface(Theme.font(fam));
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable glass(float radius) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{(Theme.SURF_A & 0xFFFFFF) | 0xF0000000, (Theme.SURF_B & 0xFFFFFF) | 0xF0000000});
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1), Theme.STROKE);
        return g;
    }

    private TextView button(String label, boolean primary, View.OnClickListener l) {
        TextView t = text(18, "sans-serif-light", primary ? Theme.ON_ACCENT : Theme.TEXT);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(26), dp(12), dp(26), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        if (primary) g.setColor(Theme.ACCENT); else { g.setColor(Theme.CHIP); g.setStroke(dp(1), Theme.CHIP_STROKE); }
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        map = new MapView(this);
        map.setListener(this);
        root.addView(map, new FrameLayout.LayoutParams(-1, -1));

        // back to the home screen
        ImageView home = new ImageView(this);
        home.setImageResource(R.drawable.logo);
        home.setPadding(dp(10), dp(8), dp(10), dp(8));
        home.setBackground(glass(18));
        home.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { finish(); } });
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(dp(56), dp(60), Gravity.TOP | Gravity.LEFT);
        hl.setMargins(dp(20), dp(20), 0, 0);
        root.addView(home, hl);

        // search pill
        searchPill = new LinearLayout(this);
        searchPill.setGravity(Gravity.CENTER_VERTICAL);
        searchPill.setPadding(dp(20), 0, dp(24), 0);
        searchPill.setBackground(glass(30));
        ImageView mag = new ImageView(this);
        mag.setImageDrawable(new LineIcon(LineIcon.NAV, Theme.ACCENT));
        searchPill.addView(mag, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView hint = text(20, "sans-serif-light", Theme.DIM);
        hint.setText(R.string.nav_where);
        hint.setPadding(dp(14), 0, 0, 0);
        searchPill.addView(hint);
        searchPill.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { openSearch(); } });
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(dp(420), dp(60), Gravity.TOP | Gravity.LEFT);
        sl.setMargins(dp(92), dp(20), 0, 0);
        root.addView(searchPill, sl);

        // next-turn card
        maneuverCard = new LinearLayout(this);
        maneuverCard.setGravity(Gravity.CENTER_VERTICAL);
        maneuverCard.setPadding(dp(18), dp(14), dp(22), dp(14));
        maneuverCard.setBackground(glass(22));
        ImageView mi = new ImageView(this);
        manIcon = new ManeuverIcon();
        mi.setImageDrawable(manIcon);
        maneuverCard.addView(mi, new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout mt = new LinearLayout(this);
        mt.setOrientation(LinearLayout.VERTICAL);
        mt.setPadding(dp(16), 0, 0, 0);
        manDist = text(40, "sans-serif-light", Theme.TEXT);
        manText = text(17, "sans-serif-light", Theme.DIM);
        manText.setMaxLines(2);
        mt.addView(manDist);
        mt.addView(manText);
        maneuverCard.addView(mt, new LinearLayout.LayoutParams(dp(300), -2));
        FrameLayout.LayoutParams ml = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        ml.setMargins(dp(92), dp(20), 0, 0);
        root.addView(maneuverCard, ml);

        // speed bubble
        speedView = text(30, "sans-serif-light", Theme.TEXT);
        speedView.setGravity(Gravity.CENTER);
        GradientDrawable sb = new GradientDrawable();
        sb.setShape(GradientDrawable.OVAL);
        sb.setColor((Theme.SURF_B & 0xFFFFFF) | 0xF0000000);
        sb.setStroke(dp(2), Theme.CHIP_STROKE);
        speedView.setBackground(sb);
        speedView.setText("0");
        FrameLayout.LayoutParams spl = new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM | Gravity.LEFT);
        spl.setMargins(dp(20), 0, 0, dp(110));
        root.addView(speedView, spl);

        // re-center
        recenterBtn = button(getString(R.string.nav_recenter), false, new View.OnClickListener() {
            public void onClick(View v) { map.recenter(); recenterBtn.setVisibility(View.GONE); }
        });
        recenterBtn.setVisibility(View.GONE);
        FrameLayout.LayoutParams rl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT);
        rl.setMargins(0, 0, dp(24), dp(116));
        root.addView(recenterBtn, rl);

        // ETA bar
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(28), 0, dp(14), 0);
        bottomBar.setBackground(glass(26));
        etaClock = text(32, "sans-serif-light", Theme.TEXT);
        etaRest = text(18, "sans-serif-light", Theme.DIM);
        etaRest.setPadding(dp(22), 0, 0, 0);
        bottomBar.addView(etaClock);
        bottomBar.addView(etaRest, new LinearLayout.LayoutParams(0, -2, 1));
        bottomBar.addView(button(getString(R.string.nav_end), false, new View.OnClickListener() { public void onClick(View v) { stopNav(); } }));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(dp(620), dp(76), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bl.bottomMargin = dp(20);
        root.addView(bottomBar, bl);

        // route preview
        previewCard = new LinearLayout(this);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setPadding(dp(28), dp(20), dp(20), dp(20));
        previewCard.setBackground(glass(26));
        previewTitle = text(24, "sans-serif-light", Theme.TEXT);
        previewTitle.setSingleLine(true);
        previewInfo = text(18, "sans-serif-light", Theme.DIM);
        previewCard.addView(previewTitle);
        previewCard.addView(previewInfo);
        LinearLayout pb = new LinearLayout(this);
        pb.setGravity(Gravity.RIGHT);
        pb.setPadding(0, dp(14), 0, 0);
        pb.addView(button(getString(R.string.cancel), false, new View.OnClickListener() { public void onClick(View v) { showBrowse(); } }));
        TextView go = button(getString(R.string.nav_go), true, new View.OnClickListener() { public void onClick(View v) { startNav(); } });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.leftMargin = dp(12);
        pb.addView(go, gl);
        previewCard.addView(pb, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(dp(620), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pl.bottomMargin = dp(20);
        root.addView(previewCard, pl);

        // search panel
        searchPanel = new LinearLayout(this);
        searchPanel.setOrientation(LinearLayout.VERTICAL);
        searchPanel.setBackgroundColor(Theme.SCRIM);
        searchPanel.setPadding(dp(92), dp(20), dp(92), dp(20));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(glass(30));
        bar.setPadding(dp(22), 0, dp(10), 0);
        query = new EditText(this);
        query.setTextColor(Theme.TEXT);
        query.setHintTextColor(Theme.DIM);
        query.setHint(R.string.nav_hint);
        query.setTextSize(22 * Theme.scale);
        query.setTypeface(Theme.font("sans-serif-light"));
        query.setBackground(null);
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int a, KeyEvent e) { runSearch(); return true; }
        });
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { if (s.length() == 0) showRecent(); }
        });
        bar.addView(query, new LinearLayout.LayoutParams(0, dp(60), 1));
        bar.addView(button(getString(R.string.nav_search), true, new View.OnClickListener() { public void onClick(View v) { runSearch(); } }));
        bar.addView(button("✕", false, new View.OnClickListener() { public void onClick(View v) { closeSearch(); } }));
        searchPanel.addView(bar);
        ScrollView sv = new ScrollView(this);
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(0, dp(12), 0, 0);
        sv.addView(results);
        searchPanel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        searchPanel.setVisibility(View.GONE);
        root.addView(searchPanel, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private void show(View... visible) {
        View[] all = {searchPill, maneuverCard, bottomBar, previewCard};
        for (View v : all) v.setVisibility(View.GONE);
        for (View v : visible) v.setVisibility(View.VISIBLE);
    }

    private void showBrowse() {
        navigating = false;
        route = null;
        target = null;
        map.route = null;
        map.dest = null;
        map.bearing = 0;
        map.anchorY = 0.5f;
        map.setZoom(15.5);
        map.recenter();
        show(searchPill);
    }

    // ------------------------------------------------------------------ search

    private void openSearch() {
        searchPanel.setVisibility(View.VISIBLE);
        query.setText("");
        showRecent();
        query.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(query, InputMethodManager.SHOW_IMPLICIT);
    }

    private void closeSearch() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(query.getWindowToken(), 0);
        searchPanel.setVisibility(View.GONE);
    }

    private void showRecent() {
        results.removeAllViews();
        List<GoogleApi.Place> rec = loadRecent();
        if (!rec.isEmpty()) results.addView(label(getString(R.string.nav_recent)));
        for (GoogleApi.Place p : rec) results.addView(row(p));
    }

    private TextView label(String s) {
        TextView t = text(13, "sans-serif-light", Theme.DIM);
        t.setText(s);
        t.setLetterSpacing(0.3f);
        t.setPadding(dp(8), dp(14), 0, dp(6));
        return t;
    }

    private View row(final GoogleApi.Place p) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(18), dp(14), dp(18), dp(14));
        TextView n = text(21, "sans-serif-light", Theme.TEXT);
        n.setText(p.name);
        TextView a = text(15, "sans-serif-light", Theme.DIM);
        a.setText(p.address);
        r.addView(n);
        r.addView(a);
        r.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { closeSearch(); preview(p); } });
        return r;
    }

    private void runSearch() {
        final String q = query.getText().toString().trim();
        if (q.isEmpty()) return;
        if (!keyOk()) return;
        results.removeAllViews();
        results.addView(label(getString(R.string.nav_searching)));
        final double la = last == null ? Double.NaN : last.getLatitude(), lo = last == null ? 0 : last.getLongitude();
        io.execute(new Runnable() {
            public void run() {
                try {
                    final List<GoogleApi.Place> found = api.search(q, la, lo);
                    h.post(new Runnable() {
                        public void run() {
                            results.removeAllViews();
                            if (found.isEmpty()) results.addView(label(getString(R.string.nav_no_results)));
                            for (GoogleApi.Place p : found) results.addView(row(p));
                        }
                    });
                } catch (final Exception e) {
                    h.post(new Runnable() { public void run() { results.removeAllViews(); results.addView(label(getString(R.string.nav_error, e.getMessage()))); } });
                }
            }
        });
    }

    /** Key baked in at build time (apikey.txt), or the one the user pasted in the app. */
    private String mapsKey() {
        String k = getSharedPreferences("nav", MODE_PRIVATE).getString("mapsKey", "");
        return k.length() > 10 ? k : getString(R.string.maps_key);
    }

    private boolean keyOk() {
        if (mapsKey().length() > 10) return true;
        results.removeAllViews();
        TextView t = label(getString(R.string.nav_no_key));
        t.setTextColor(Theme.ACCENT);
        t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { askKey(); } });
        results.addView(t);
        return false;
    }

    private void askKey() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setHint("AIza…");
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.key_title)
                .setMessage(R.string.key_msg)
                .setView(in)
                .setPositiveButton(R.string.save, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        String k = in.getText().toString().trim();
                        if (k.length() < 10) return;
                        getSharedPreferences("nav", MODE_PRIVATE).edit().putString("mapsKey", k).apply();
                        api = new GoogleApi(k);
                        loadGoogleTiles();
                        runSearch();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ routing

    private void preview(final GoogleApi.Place p) {
        if (last == null) { previewTitle.setText(p.name); previewInfo.setText(R.string.nav_waiting_gps); show(previewCard); return; }
        target = p;
        saveRecent(p);
        previewTitle.setText(p.name);
        previewInfo.setText(R.string.nav_routing);
        show(previewCard);
        computeRoute(false);
    }

    private void computeRoute(final boolean reroute) {
        if (last == null || target == null) return;
        lastReroute = System.currentTimeMillis();
        final double la = last.getLatitude(), lo = last.getLongitude(), hd = heading;
        io.execute(new Runnable() {
            public void run() {
                try {
                    final GoogleApi.Route r = api.route(la, lo, hd, target.lat, target.lng);
                    h.post(new Runnable() { public void run() { setRoute(r, reroute); } });
                } catch (final Exception e) {
                    h.post(new Runnable() { public void run() { if (!reroute) previewInfo.setText(getString(R.string.nav_error, e.getMessage())); } });
                }
            }
        });
    }

    private void setRoute(GoogleApi.Route r, boolean reroute) {
        route = r;
        cum = new double[r.points.size()];
        for (int i = 1; i < cum.length; i++) {
            double[] a = r.points.get(i - 1), b = r.points.get(i);
            cum[i] = cum[i - 1] + Geo.dist(a[0], a[1], b[0], b[1]);
        }
        idx = 0;
        offCount = 0;
        map.route = r.points;
        map.dest = new double[]{target.lat, target.lng};
        if (reroute) { say(getString(R.string.nav_rerouting)); spoken.clear(); return; }
        previewInfo.setText(Geo.durText(r.seconds) + "  ·  " + Geo.distText(r.meters) + "  ·  " + getString(R.string.nav_arrive, clock(r.seconds)));
        map.fit(r.points);
    }

    private void startNav() {
        if (route == null) return;
        navigating = true;
        spoken.clear();
        map.anchorY = 0.7f;
        map.setZoom(17);
        map.recenter();
        recenterBtn.setVisibility(View.GONE);
        show(maneuverCard, bottomBar);
        if (last != null) onLocationChanged(last);
    }

    private void stopNav() { showBrowse(); }

    // ------------------------------------------------------------------ guidance

    @Override
    public void onLocationChanged(Location l) {
        if (l.hasBearing() && l.getSpeed() > 1.5f) heading = l.getBearing();
        last = l;
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        speedView.setText(String.valueOf(Math.round(kmh)));
        map.setCar(l.getLatitude(), l.getLongitude(), heading);
        if (!navigating || route == null) return;

        if (map.follow) {
            map.bearing = heading;
            double target = kmh < 50 ? 17 : kmh < 90 ? 16 : 15;
            map.setZoom(map.zoom + (target - map.zoom) * 0.25);
        }

        // snap to the route
        double[] p = {l.getLatitude(), l.getLongitude()};
        double best = Double.MAX_VALUE, bestT = 0;
        int bestI = idx;
        int end = Math.min(route.points.size() - 1, idx + 80);
        for (int i = Math.max(0, idx - 3); i < end; i++) {
            double[] r = Geo.toSegment(p, route.points.get(i), route.points.get(i + 1));
            if (r[0] < best) { best = r[0]; bestI = i; bestT = r[1]; }
        }
        if (best > 50) offCount++; else { offCount = 0; idx = bestI; }
        if (offCount >= 3 && System.currentTimeMillis() - lastReroute > 12000) computeRoute(true);

        double along = cum[idx] + bestT * (cum[Math.min(idx + 1, cum.length - 1)] - cum[idx]);
        double total = cum[cum.length - 1];
        double remain = Math.max(0, total - along);

        int s = 0;
        while (s < route.steps.size() - 1 && route.steps.get(s).endAlong <= along + 1) s++;
        String man, txt;
        double toTurn;
        if (s + 1 < route.steps.size()) {
            GoogleApi.Step next = route.steps.get(s + 1);
            man = next.maneuver;
            txt = next.text;
            toTurn = route.steps.get(s).endAlong - along;
        } else {
            man = "DESTINATION";
            txt = target.name;
            toTurn = remain;
        }
        manIcon.set(man);
        manDist.setText(Geo.distText(toTurn));
        manText.setText(txt);
        double sec = route.seconds * (remain / Math.max(1, total));
        etaClock.setText(clock(sec));
        etaRest.setText(Geo.durText(sec) + "  ·  " + Geo.distText(remain));

        announce(s + 1, toTurn, txt, kmh);
        if (remain < 30) {
            say(getString(R.string.nav_arrived));
            navigating = false;
            h.postDelayed(new Runnable() { public void run() { showBrowse(); } }, 6000);
        }
    }

    private void announce(int step, double meters, String txt, float kmh) {
        int[] marks = kmh > 70 ? new int[]{1000, 400, 80} : new int[]{400, 150, 40};
        for (int m : marks) {
            String k = step + ":" + m;
            if (meters <= m && meters > m * 0.5 && !spoken.contains(k)) {
                spoken.add(k);
                say(m <= 80 ? txt : getString(R.string.nav_in, Geo.distSpoken(meters), txt));
            } else if (meters <= m * 0.5) spoken.add(k);
        }
    }

    private void say(String s) {
        if (voice && tts != null) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "nav");
    }

    private String clock(double secFromNow) {
        String f = android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a";
        return new SimpleDateFormat(f, Locale.getDefault()).format(new Date(System.currentTimeMillis() + (long) (secFromNow * 1000)));
    }

    @Override public void onUserMoved() { recenterBtn.setVisibility(View.VISIBLE); }

    // ------------------------------------------------------------------ recent places

    private List<GoogleApi.Place> loadRecent() {
        List<GoogleApi.Place> out = new ArrayList<GoogleApi.Place>();
        try {
            JSONArray a = new JSONArray(getSharedPreferences("nav", MODE_PRIVATE).getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                GoogleApi.Place p = new GoogleApi.Place();
                p.name = o.getString("n");
                p.address = o.optString("a");
                p.lat = o.getDouble("la");
                p.lng = o.getDouble("lo");
                out.add(p);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void saveRecent(GoogleApi.Place p) {
        try {
            List<GoogleApi.Place> rec = loadRecent();
            JSONArray a = new JSONArray();
            a.put(new JSONObject().put("n", p.name).put("a", p.address).put("la", p.lat).put("lo", p.lng));
            for (GoogleApi.Place r : rec) {
                if (a.length() >= 8) break;
                if (r.name.equals(p.name) && Math.abs(r.lat - p.lat) < 1e-5) continue;
                a.put(new JSONObject().put("n", r.name).put("a", r.address).put("la", r.lat).put("lo", r.lng));
            }
            getSharedPreferences("nav", MODE_PRIVATE).edit().putString("recent", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this); } catch (Exception ignored) { }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!navigating) lm.removeUpdates(this);   // keep guiding if music is opened over the map
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lm.removeUpdates(this);
        if (tts != null) tts.shutdown();
    }

    @Override public void onBackPressed() {
        if (searchPanel.getVisibility() == View.VISIBLE) closeSearch();
        else if (previewCard.getVisibility() == View.VISIBLE) showBrowse();
        else if (!navigating) finish();
    }

    @Override public void onStatusChanged(String p, int s, Bundle e) { }
    @Override public void onProviderEnabled(String p) { }
    @Override public void onProviderDisabled(String p) { }
}
