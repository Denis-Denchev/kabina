package bg.dmd.carhome.car;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import bg.dmd.carhome.R;
import bg.dmd.carhome.Theme;

/** Ambient lighting: live cockpit preview, named colours, effects, brightness. */
public class LightsActivity extends Activity implements CarLink.Listener {
    private static final int[] COLORS = {0x2F7BFF, 0xBFE6FF, 0xFF1E2D, 0xFF6A00, 0xFFC46B, 0x8B3DFF, 0x00E5C3, 0xFF2DAA};
    private String[] NAMES, SUBS, FX;   // from resources: brand packs rename the colours, translations the rest

    private CarLink car;
    private CockpitView cockpit;
    private TextView status, power, title, subtitle, brightText;
    private final LinearLayout[] cards = new LinearLayout[COLORS.length];
    private final TextView[] fxChips = new TextView[5];
    private Slider bright;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density * Theme.scale); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        car = CarLink.get(this);
        NAMES = getResources().getStringArray(R.array.color_names);
        SUBS = getResources().getStringArray(R.array.color_subs);
        FX = getResources().getStringArray(R.array.effects);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Theme.PAGE);

        cockpit = new CockpitView(this);
        root.addView(cockpit, new FrameLayout.LayoutParams(-1, -1));

        // dark fade so the panel on the right stays readable over the cockpit
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Theme.PAGE & 0xFFFFFF, Theme.PAGE & 0xFFFFFF, (Theme.PAGE & 0xFFFFFF) | 0xE6000000, (Theme.PAGE & 0xFFFFFF) | 0xF5000000}));
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(16), dp(28), dp(12));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this);
        col.addView(CarActivity.header(this, getString(R.string.lights_title), status));
        status.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { car.forgetLed(); car.start(); return true; }
        });

        LinearLayout body = new LinearLayout(this);
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        // left: the name of the current colour floats over the cockpit
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(8), dp(26), 0, 0);
        title = CarActivity.text(this, 44, "sans-serif-thin", Theme.TEXT);
        title.setLetterSpacing(0.18f);
        subtitle = CarActivity.text(this, 17, "sans-serif-light", Theme.DIM);
        subtitle.setLetterSpacing(0.08f);
        left.addView(title);
        left.addView(subtitle);
        body.addView(left, new LinearLayout.LayoutParams(0, -1, 5.2f));

        // right: controls
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), 0, 0, 0);
        body.addView(right, new LinearLayout.LayoutParams(0, -1, 4.8f));

        LinearLayout pr = new LinearLayout(this);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        right.addView(pr);
        TextView lbl = CarActivity.text(this, 12, "sans-serif-light", Theme.DIM);
        lbl.setText(R.string.colors);
        lbl.setLetterSpacing(0.3f);
        pr.addView(lbl, new LinearLayout.LayoutParams(0, -2, 1));
        power = CarActivity.pill(this, "", true, new View.OnClickListener() { public void onClick(View v) { car.setLedPower(!car.ledOn); } });
        pr.addView(power);

        for (int row = 0; row < 4; row++) {
            LinearLayout r = new LinearLayout(this);
            for (int k = 0; k < 2; k++) {
                final int i = row * 2 + k;
                cards[i] = colorCard(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                r.addView(cards[i], lp);
            }
            right.addView(r);
        }

        Slider hue = new Slider(this, true);
        hue.listener = new Slider.Listener() { public void onValue(float v) { car.setLedColor(Color.HSVToColor(new float[]{v * 360f, 1f, 1f})); } };
        right.addView(caption(getString(R.string.your_color)));
        right.addView(hue, new LinearLayout.LayoutParams(-1, dp(32)));

        LinearLayout br = new LinearLayout(this);
        br.setGravity(Gravity.CENTER_VERTICAL);
        br.addView(caption(getString(R.string.brightness)), new LinearLayout.LayoutParams(0, -2, 1));
        brightText = CarActivity.text(this, 13, "sans-serif-light", Theme.DIM);
        br.addView(brightText);
        right.addView(br);
        bright = new Slider(this, false);
        bright.listener = new Slider.Listener() { public void onValue(float v) { car.setLedBrightness(Math.max(5, Math.round(v * 100))); } };
        right.addView(bright, new LinearLayout.LayoutParams(-1, dp(32)));

        right.addView(caption(getString(R.string.effect)));
        LinearLayout fx = new LinearLayout(this);
        for (int i = 0; i < FX.length; i++) {
            final int f = i;
            fxChips[i] = CarActivity.text(this, 14, "sans-serif-light", Theme.TEXT);
            fxChips[i].setText(FX[i]);
            fxChips[i].setGravity(Gravity.CENTER);
            fxChips[i].setSingleLine(true);
            fxChips[i].setOnClickListener(new View.OnClickListener() { public void onClick(View v) { car.setLedEffect(f); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
            lp.setMargins(dp(3), 0, dp(3), 0);
            fx.addView(fxChips[i], lp);
        }
        right.addView(fx);

        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private TextView caption(String s) {
        TextView t = CarActivity.text(this, 12, "sans-serif-light", Theme.DIM);
        t.setText(s);
        t.setLetterSpacing(0.3f);
        t.setPadding(dp(4), dp(10), 0, dp(4));
        return t;
    }

    private LinearLayout colorCard(final int i) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(12), 0, dp(8), 0);
        View dot = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(18));
        g.setColors(new int[]{0xFF000000 | blendWhite(COLORS[i]), 0xFF000000 | COLORS[i]});
        dot.setBackground(g);
        c.addView(dot, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout tx = new LinearLayout(this);
        tx.setOrientation(LinearLayout.VERTICAL);
        tx.setPadding(dp(12), 0, 0, 0);
        TextView n = CarActivity.text(this, 15, "sans-serif-light", Theme.TEXT);
        n.setText(NAMES[i]);
        n.setLetterSpacing(0.15f);
        n.setSingleLine(true);
        TextView s = CarActivity.text(this, 11, "sans-serif-light", Theme.DIM);
        s.setText(SUBS[i]);
        s.setSingleLine(true);
        tx.addView(n);
        tx.addView(s);
        c.addView(tx);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).withEndAction(new Runnable() {
                    public void run() { cards[i].animate().scaleX(1f).scaleY(1f).setDuration(160); }
                });
                car.setLedColor(COLORS[i]);
                if (car.ledEffect == CarLink.FX_AURORA || car.ledEffect == CarLink.FX_REVS) car.setLedEffect(CarLink.FX_STATIC);
            }
        });
        return c;
    }

    private static int blendWhite(int c) {
        return ((Color.red(c) + 255) / 2 << 16) | ((Color.green(c) + 255) / 2 << 8) | ((Color.blue(c) + 255) / 2);
    }

    private int nearest(int rgb) {
        for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == (rgb & 0xFFFFFF)) return i;
        return -1;
    }

    @Override
    public void onCarChanged() {
        status.setText((car.ledConnected ? "● " : "○ ") + car.ledStatus);
        status.setTextColor(car.ledConnected ? Theme.OK : Theme.DIM);
        power.setText(car.ledOn ? R.string.turn_off : R.string.turn_on);
        cockpit.setColor(car.ledOn ? car.shownColor : 0x000000);
        cockpit.setLevel(car.shownLevel * (0.35f + 0.65f * car.ledBrightness / 100f), car.ledOn);
        int sel = nearest(car.ledColor);
        if (car.ledEffect == CarLink.FX_AURORA) { title.setText(R.string.aurora_title); subtitle.setText(R.string.aurora_sub); }
        else if (car.ledEffect == CarLink.FX_REVS) { title.setText(R.string.revs_title); subtitle.setText(R.string.revs_sub); }
        else if (sel >= 0) { title.setText(NAMES[sel]); subtitle.setText(SUBS[sel]); }
        else { title.setText(R.string.custom_color); subtitle.setText(String.format("#%06X", car.ledColor)); }
        if (!car.ledOn) subtitle.setText(R.string.led_off_sub);
        for (int i = 0; i < cards.length; i++) {
            GradientDrawable g = CarActivity.glass(this, 16);
            if (i == sel && car.ledEffect != CarLink.FX_AURORA && car.ledEffect != CarLink.FX_REVS) g.setStroke(dp(2), 0xFF000000 | COLORS[i]);
            cards[i].setBackground(g);
        }
        for (int i = 0; i < fxChips.length; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(21));
            if (i == car.ledEffect) g.setColor(Theme.ACCENT); else { g.setColor(Theme.CHIP); g.setStroke(dp(1), Theme.CHIP_STROKE); }
            fxChips[i].setBackground(g);
            fxChips[i].setTextColor(i == car.ledEffect ? Theme.ON_ACCENT : Theme.TEXT);
        }
        bright.set(car.ledBrightness / 100f);
        brightText.setText(car.ledBrightness + " %");
    }

    @Override protected void onResume() { super.onResume(); car.addListener(this); car.start(); onCarChanged(); }
    @Override protected void onPause() { super.onPause(); car.removeListener(this); }

    /** Rounded track with a glowing thumb; hue mode shows the colour spectrum. */
    static class Slider extends View {
        interface Listener { void onValue(float v); }
        Listener listener;
        private final boolean hue;
        private float value = 0.5f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;

        Slider(Context c, boolean hue) {
            super(c);
            this.hue = hue;
            d = c.getResources().getDisplayMetrics().density;
        }

        void set(float v) { value = v; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth(), r = 9 * d, cy = h / 2;
            RectF track = new RectF(r, cy - 5 * d, w - r, cy + 5 * d);
            if (hue) {
                int[] cols = new int[7];
                for (int i = 0; i < 7; i++) cols[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
                p.setShader(new LinearGradient(track.left, 0, track.right, 0, cols, null, Shader.TileMode.CLAMP));
            } else {
                p.setShader(new LinearGradient(track.left, 0, track.right, 0, 0xFF0E2A55, 0xFFBFE6FF, Shader.TileMode.CLAMP));
            }
            c.drawRoundRect(track, 5 * d, 5 * d, p);
            p.setShader(null);
            float x = track.left + value * track.width();
            p.setColor((Theme.ACCENT & 0xFFFFFF) | 0x33000000);
            c.drawCircle(x, cy, r + 7 * d, p);
            if (Theme.day) { p.setColor(0x40000000); c.drawCircle(x, cy + d, r + d, p); }   // white thumb needs an edge on a light page
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, cy, r, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float r = 9 * d;
            value = Math.max(0, Math.min(1, (e.getX() - r) / (getWidth() - 2 * r)));
            invalidate();
            if (listener != null && (e.getAction() == MotionEvent.ACTION_MOVE || e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_DOWN))
                listener.onValue(value);
            return true;
        }
    }
}
