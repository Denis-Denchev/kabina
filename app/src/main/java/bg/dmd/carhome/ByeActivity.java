package bg.dmd.carhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Ignition off: the logo glows, "GOODBYE", fades to black, then the screen turns off. */
public class ByeActivity extends Activity {
    private final android.os.Handler h = new android.os.Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        float d = getResources().getDisplayMetrics().density * Theme.scale;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        View glow = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(260 * d);
        g.setColors(new int[]{0x554AA3FF, 0x00000000});
        glow.setBackground(g);
        glow.setAlpha(0.35f);
        root.addView(glow, new FrameLayout.LayoutParams((int) (560 * d), (int) (560 * d), Gravity.CENTER));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        root.addView(logo, new FrameLayout.LayoutParams((int) (200 * d), (int) (220 * d), Gravity.CENTER));

        TextView bye = new TextView(this);
        bye.setText(R.string.goodbye);
        bye.setTextColor(0x99F2F4F7);
        bye.setTextSize(18 * Theme.scale);
        bye.setLetterSpacing(0.4f);
        bye.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        bl.topMargin = (int) (170 * d);
        root.addView(bye, bl);
        setContentView(root);
        root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN);

        root.setAlpha(0f);
        AnimatorSet in = new AnimatorSet();
        in.playTogether(ObjectAnimator.ofFloat(root, "alpha", 0f, 1f).setDuration(500),
                ObjectAnimator.ofFloat(glow, "alpha", 0.35f, 1f).setDuration(900));
        AnimatorSet out = new AnimatorSet();
        out.playTogether(ObjectAnimator.ofFloat(logo, "alpha", 1f, 0f).setDuration(1200),
                ObjectAnimator.ofFloat(logo, "scaleX", 1f, 0.9f).setDuration(1200),
                ObjectAnimator.ofFloat(logo, "scaleY", 1f, 0.9f).setDuration(1200),
                ObjectAnimator.ofFloat(glow, "alpha", 1f, 0f).setDuration(1200),
                ObjectAnimator.ofFloat(bye, "alpha", 1f, 0f).setDuration(1000));
        out.setStartDelay(1300);
        out.setInterpolator(new AccelerateInterpolator());
        AnimatorSet all = new AnimatorSet();
        all.playSequentially(in, out);
        all.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { sleepNow(); }
        });
        all.start();
    }

    private boolean plugged() {
        Intent bat = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return bat != null && bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private void sleepNow() {
        if (plugged()) { finish(); return; } // power came back during the animation
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        if (dpm.isAdminActive(new ComponentName(this, Admin.class))) dpm.lockNow();
        h.postDelayed(new Runnable() { public void run() { finish(); } }, 500);
    }

    @Override public void onBackPressed() { }
}
