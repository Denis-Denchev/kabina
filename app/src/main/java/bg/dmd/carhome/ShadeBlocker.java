package bg.dmd.carhome;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/** Invisible strip over the status bar in every app, so the notification shade can't be pulled down (Android 5-7 only). */
public class ShadeBlocker extends Service {
    private View strip;

    @Override
    public void onCreate() {
        super.onCreate();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        strip = new View(this);
        strip.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return true; }
        });
        // Exactly the status bar's height: any taller and it swallows taps on every app's toolbar.
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int h = id > 0 ? getResources().getDimensionPixelSize(id) : Math.round(24 * getResources().getDisplayMetrics().density);
        @SuppressWarnings("deprecation")
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, h,
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT);
        lp.gravity = Gravity.TOP;
        wm.addView(strip, lp);
    }

    @Override public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (strip != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(strip);
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
