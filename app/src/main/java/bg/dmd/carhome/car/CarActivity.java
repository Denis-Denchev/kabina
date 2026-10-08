package bg.dmd.carhome.car;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import bg.dmd.carhome.R;
import bg.dmd.carhome.Theme;

/** Live OBD-II data and trouble codes. */
public class CarActivity extends Activity implements CarLink.Listener {

    private CarLink car;
    private Gauge gauge;
    private TextView status, codes;
    private Car3DView car3d;
    private CarGLView glView;
    private boolean hasCodes;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density * Theme.scale); }

    static TextView text(Activity a, float sp, String fam, int color) {
        TextView t = new TextView(a);
        t.setTextSize(sp * Theme.scale);
        t.setTypeface(Theme.font(fam));
        t.setTextColor(color);
        return t;
    }

    static GradientDrawable glass(Activity a, float r) {
        return Theme.surface(a, r);
    }

    static TextView pill(Activity a, String label, boolean primary, View.OnClickListener l) {
        float d = a.getResources().getDisplayMetrics().density * Theme.scale;
        TextView t = text(a, 17, "sans-serif-light", primary ? Theme.ON_ACCENT : Theme.TEXT);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding((int) (24 * d), (int) (12 * d), (int) (24 * d), (int) (12 * d));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(24 * d);
        if (primary) g.setColor(Theme.ACCENT); else { g.setColor(Theme.CHIP); g.setStroke((int) d, Theme.CHIP_STROKE); }
        t.setBackground(g);
        t.setOnClickListener(l);
        return t;
    }

    /** Logo (back) button + spaced title + status line, shared by the car screens. */
    static LinearLayout header(final Activity a, String title, TextView status) {
        float d = a.getResources().getDisplayMetrics().density * Theme.scale;
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(a);
        logo.setImageResource(R.drawable.logo);
        logo.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
        logo.setBackground(glass(a, 18));
        logo.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { a.finish(); } });
        top.addView(logo, new LinearLayout.LayoutParams((int) (56 * d), (int) (60 * d)));
        TextView t = text(a, 16, "sans-serif-light", Theme.DIM);
        t.setText(title);
        t.setLetterSpacing(0.35f);
        t.setPadding((int) (20 * d), 0, 0, 0);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        status.setTextColor(Theme.DIM);
        status.setTextSize(15 * Theme.scale);
        top.addView(status);
        return top;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        car = CarLink.get(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(28), dp(20));
        root.setBackground(Theme.page());
        status = new TextView(this);
        root.addView(header(this, getString(R.string.car_title), status));

        LinearLayout body = new LinearLayout(this);
        body.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        gauge = new Gauge(this);
        body.addView(gauge, new LinearLayout.LayoutParams(0, -1, 3.8f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        car3d = new Car3DView(this);
        CarGLView gl = new CarGLView(this);
        if (gl.hasModel()) {               // the brand pack's 3D model, baked into assets/car.bin
            glView = gl;
            FrameLayout stage = new FrameLayout(this);
            stage.addView(gl, new FrameLayout.LayoutParams(-1, -1));
            car3d.useExternalAnchors(gl.screen);
            stage.addView(car3d, new FrameLayout.LayoutParams(-1, -1));
            TextView credit = text(this, 9, "sans-serif-light", Theme.FAINT);
            credit.setText(R.string.model_credit);
            stage.addView(credit, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.RIGHT));
            right.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            right.addView(car3d, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        LinearLayout dtcRow = new LinearLayout(this);
        dtcRow.setGravity(Gravity.CENTER_VERTICAL);
        dtcRow.setPadding(dp(6), dp(10), dp(6), 0);
        codes = text(this, 15, "sans-serif-light", Theme.DIM);
        codes.setText(R.string.dtc_hint);
        dtcRow.addView(codes, new LinearLayout.LayoutParams(0, -2, 1));
        dtcRow.addView(pill(this, getString(R.string.dtc_check), false, new View.OnClickListener() { public void onClick(View v) { readCodes(); } }));
        TextView clr = pill(this, getString(R.string.dtc_clear), false, new View.OnClickListener() { public void onClick(View v) { askClear(); } });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = dp(10);
        dtcRow.addView(clr, cl);
        right.addView(dtcRow);
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 6.2f));
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void readCodes() {
        codes.setText(R.string.dtc_reading);
        codes.setTextColor(Theme.DIM);
        car.readDtc(new CarLink.DtcResult() {
            public void done(List<String> list, String error) {
                if (error != null) { codes.setText(error); codes.setTextColor(Theme.WARN); return; }
                hasCodes = !list.isEmpty();
                car3d.setAlert(hasCodes);
                if (glView != null) glView.setAlert(hasCodes);
                if (list.isEmpty()) { codes.setText(R.string.dtc_none); codes.setTextColor(Theme.OK); return; }
                StringBuilder sb = new StringBuilder();
                for (String c : list) sb.append(c).append("  ").append(DtcText.describe(c)).append('\n');
                codes.setText(sb.toString().trim());
                codes.setTextColor(Theme.ERR);
            }
        });
    }

    private void askClear() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dtc_clear_title)
                .setMessage(R.string.dtc_clear_msg)
                .setPositiveButton(R.string.dtc_clear, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        car.clearDtc(new CarLink.DtcResult() {
                            public void done(List<String> l, String error) {
                                if (error == null) car3d.setAlert(false);
                                codes.setText(error != null ? error : getString(R.string.dtc_cleared));
                                codes.setTextColor(error != null ? Theme.WARN : Theme.OK);
                            }
                        });
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onCarChanged() {
        status.setText((car.obdConnected ? "● " : "○ ") + car.obdStatus);
        status.setTextColor(car.obdConnected ? Theme.OK : Theme.DIM);
        gauge.set(Math.max(0, car.rpm), car.speed < 0 ? "—" : String.valueOf(car.speed),
                car.rpm < 0 ? getString(R.string.rpm_none) : getString(R.string.rpm_value, car.rpm));
        int ct = car.coolant;
        car3d.set(0, ct == Integer.MIN_VALUE ? "—" : ct + " °C", ct != Integer.MIN_VALUE && ct >= 105);
        car3d.set(1, car.volts < 0 ? "—" : String.format(Locale.ROOT, "%.1f V", car.volts), car.volts > 0 && (car.volts < 12.0f || car.volts > 14.9f));
        car3d.set(2, car.intake == Integer.MIN_VALUE ? "—" : car.intake + " °C", false);
        car3d.set(3, car.load < 0 ? "—" : car.load + " %" + (car.throttle < 0 ? "" : "  ·  " + getString(R.string.throttle, car.throttle)), false);
        car3d.set(4, car.fuel < 0 ? "—" : car.fuel + " %", car.fuel >= 0 && car.fuel < 12);
    }

    @Override protected void onResume() { super.onResume(); car.addListener(this); car.start(); onCarChanged(); if (glView != null) glView.onResume(); }
    @Override protected void onPause() { super.onPause(); car.removeListener(this); if (glView != null) glView.onPause(); }
}
