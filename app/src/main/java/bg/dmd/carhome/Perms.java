package bg.dmd.carhome;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/** Runtime permissions (Android 6+): location for speed and navigation, Bluetooth for OBD and the LED strip. */
public final class Perms {
    private Perms() { }

    private static String[] wanted() {
        List<String> p = new ArrayList<String>();
        p.add("android.permission.ACCESS_FINE_LOCATION");
        p.add("android.permission.ACCESS_COARSE_LOCATION");
        if (Build.VERSION.SDK_INT >= 31) {
            p.add("android.permission.BLUETOOTH_SCAN");
            p.add("android.permission.BLUETOOTH_CONNECT");
        }
        return p.toArray(new String[0]);
    }

    public static boolean has(Context c, String perm) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when the app may talk to Bluetooth devices. */
    public static boolean bluetooth(Context c) {
        return Build.VERSION.SDK_INT < 31 || (has(c, "android.permission.BLUETOOTH_CONNECT") && has(c, "android.permission.BLUETOOTH_SCAN"));
    }

    static void request(Activity a) {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> missing = new ArrayList<String>();
        for (String p : wanted()) if (!has(a, p)) missing.add(p);
        if (!missing.isEmpty()) a.requestPermissions(missing.toArray(new String[0]), 1);
    }
}
