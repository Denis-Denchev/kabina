package bg.dmd.carhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/** Car ignition = USB power. Power on → wake + logo intro. Power off → logo outro, then screen off. */
public class PowerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_POWER_CONNECTED.equals(i.getAction())) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "carhome:ignition");
            wl.acquire(4000);
            bg.dmd.carhome.car.CarLink.get(c).start();
            start(c, new Intent(c, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_INTRO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) {
            bg.dmd.carhome.car.CarLink.get(c).stop();     // let the ECU sleep; strip loses power anyway
            start(c, new Intent(c, ByeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** Android 10+ blocks activity starts from the background unless the app is device owner or may draw overlays. */
    private static void start(Context c, Intent i) {
        try { c.startActivity(i); } catch (Exception ignored) { }
    }
}
