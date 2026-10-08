package bg.dmd.carhome;

import android.app.Application;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

public class App extends Application {
    private static App self;

    /** Localised string from anywhere (views, the car link, formatting helpers). */
    public static String s(int id, Object... args) {
        return args.length == 0 ? self.getString(id) : self.getString(id, args);
    }

    /** A drawable the brand pack may or may not ship (e.g. the cabin photo); 0 when absent. */
    public static int optionalDrawable(String name) {
        return self.getResources().getIdentifier(name, "drawable", self.getPackageName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        Theme.init(this);
        // Android 8+ no longer delivers power broadcasts to manifest receivers: listen while the launcher lives.
        if (Build.VERSION.SDK_INT >= 26) {
            IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
            f.addAction(Intent.ACTION_POWER_DISCONNECTED);
            registerReceiver(new PowerReceiver(), f);
        }
    }
}
