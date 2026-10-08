package bg.dmd.carhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

/** Start-up overlay: the logo fades in with a light sweep behind it, then the overlay dissolves into the home screen. */
public class Splash extends FrameLayout {

    public Splash(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        float d = c.getResources().getDisplayMetrics().density * Theme.scale;

        final View glow = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(260 * d);
        g.setColors(new int[]{0x554AA3FF, 0x00000000});
        glow.setBackground(g);
        glow.setAlpha(0f);
        addView(glow, new LayoutParams((int) (560 * d), (int) (560 * d), Gravity.CENTER));

        final ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.logo);
        logo.setAdjustViewBounds(true);
        logo.setAlpha(0f);
        logo.setScaleX(0.86f);
        logo.setScaleY(0.86f);
        addView(logo, new LayoutParams((int) (200 * d), (int) (220 * d), Gravity.CENTER));

        final android.view.View word;
        LayoutParams wl;
        int wid = App.optionalDrawable("wordmark");
        if (wid != 0) {
            ImageView iv = new ImageView(c);
            iv.setImageResource(wid);
            iv.setAdjustViewBounds(true);
            word = iv;
            wl = new LayoutParams((int) (260 * d), (int) (24 * d), Gravity.CENTER);
        } else {   // no wordmark in the brand pack: the app name in spaced capitals
            android.widget.TextView t = new android.widget.TextView(c);
            t.setText(c.getString(R.string.app_name).toUpperCase(java.util.Locale.getDefault()));
            t.setTextColor(0xFFF2F4F7);
            t.setTextSize(20 * Theme.scale);
            t.setLetterSpacing(0.45f);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            word = t;
            wl = new LayoutParams(-2, -2, Gravity.CENTER);
        }
        word.setAlpha(0f);
        wl.topMargin = (int) (170 * d);
        addView(word, wl);
        word.setTranslationY(12 * d);

        AnimatorSet in = new AnimatorSet();
        in.playTogether(
                ObjectAnimator.ofFloat(logo, "alpha", 0f, 1f).setDuration(1100),
                ObjectAnimator.ofFloat(logo, "scaleX", 0.86f, 1f).setDuration(1600),
                ObjectAnimator.ofFloat(logo, "scaleY", 0.86f, 1f).setDuration(1600),
                ObjectAnimator.ofFloat(glow, "alpha", 0f, 1f, 0.35f).setDuration(1800));
        in.setInterpolator(new DecelerateInterpolator(1.6f));

        AnimatorSet text = new AnimatorSet();
        text.playTogether(
                ObjectAnimator.ofFloat(word, "alpha", 0f, 0.9f).setDuration(700),
                ObjectAnimator.ofFloat(word, "translationY", 12 * d, 0f).setDuration(700));
        text.setStartDelay(900);

        ObjectAnimator out = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).setDuration(600);
        out.setStartDelay(1100);

        AnimatorSet all = new AnimatorSet();
        all.playSequentially(in, out);
        all.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (getParent() instanceof FrameLayout) ((FrameLayout) getParent()).removeView(Splash.this);
            }
        });
        text.start();
        all.start();
    }
}
