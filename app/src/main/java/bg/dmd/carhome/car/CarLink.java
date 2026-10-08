package bg.dmd.carhome.car;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import bg.dmd.carhome.App;
import bg.dmd.carhome.Perms;
import bg.dmd.carhome.R;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Talks to the car: an ELM327 OBD-II adapter over classic Bluetooth (SPP) and a
 * Bluetooth LED strip (ELK-BLEDOM / "LED LAMP" app, or Triones) over BLE.
 * One instance lives as long as the launcher process.
 */
public final class CarLink {
    public interface Listener { void onCarChanged(); }

    private static CarLink instance;

    public static synchronized CarLink get(Context c) {
        if (instance == null) instance = new CarLink(c.getApplicationContext());
        return instance;
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID ELK_SVC = uuid16(0xFFF0), ELK_CHR = uuid16(0xFFF3);
    private static final UUID TRI_SVC = uuid16(0xFFD5), TRI_CHR = uuid16(0xFFD9);
    private static final UUID DMX_SVC = uuid16(0xFFE0), DMX_CHR = uuid16(0xFFE1);   // LEDDMX ("LED LAMP" app)
    private static final String[] OBD_NAMES = {"obd", "elm", "v-link", "vlink", "obdii", "obd2", "car"};
    private static final String[] LED_NAMES = {"elk", "bledom", "melk", "ledble", "triones", "led", "qhm", "lamp"};

    private static UUID uuid16(int v) {
        return UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", v));
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();

    // ---- OBD state (read from any thread) ----
    public volatile String obdStatus = App.s(R.string.link_not_connected);
    public volatile boolean obdConnected;
    public volatile String obdName = "";
    public volatile int rpm = -1, speed = -1, coolant = Integer.MIN_VALUE, load = -1, throttle = -1, intake = Integer.MIN_VALUE, fuel = -1;
    public volatile float volts = -1;
    private volatile boolean obdRunning;
    private final LinkedBlockingQueue<Runnable> obdJobs = new LinkedBlockingQueue<Runnable>();
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private final boolean[] supported = new boolean[0x61];

    // ---- LED state ----
    public volatile String ledStatus = App.s(R.string.link_not_connected);
    private volatile boolean needPairing;
    public volatile boolean ledConnected;
    public volatile boolean ledOn;
    public volatile int ledColor;       // RGB
    public volatile int ledBrightness;  // 0..100
    public static final int FX_STATIC = 0, FX_BREATHE = 1, FX_PULSE = 2, FX_AURORA = 3, FX_REVS = 4;
    public volatile int ledEffect;
    public volatile int shownColor;     // what the strip shows right now (effects change it)
    public volatile float shownLevel = 1f;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ledChar;
    private boolean triones, leddmx;
    private boolean scanning;
    private final Handler ledH;
    private byte[] pendingColor;        // only the newest colour is sent while dragging

    private CarLink(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("car", Context.MODE_PRIVATE);
        ledOn = prefs.getBoolean("ledOn", true);
        ledColor = prefs.getInt("ledColor", 0x4AA3FF);
        ledBrightness = prefs.getInt("ledBright", 80);
        ledEffect = prefs.getInt("ledFx", FX_STATIC);
        shownColor = ledColor;
        HandlerThread ht = new HandlerThread("led");
        ht.start();
        ledH = new Handler(ht.getLooper());
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    private void changed() {
        ui.post(new Runnable() { public void run() { for (Listener l : listeners) l.onCarChanged(); } });
    }

    /** Starts both links (car powered on). Safe to call repeatedly. */
    @SuppressLint("MissingPermission")
    public synchronized void start() {
        if (bt == null) { obdStatus = ledStatus = App.s(R.string.link_no_bt); changed(); return; }
        if (!Perms.bluetooth(ctx)) { obdStatus = ledStatus = App.s(R.string.link_bt_permission); changed(); return; }
        // no external power = ignition off: don't talk to the ECU or the strip
        android.content.Intent bat = ctx.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        if (bat != null && bat.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) == 0) {
            obdStatus = ledStatus = App.s(R.string.link_car_off);
            changed();
            return;
        }
        if (!bt.isEnabled()) {
            // apps may switch Bluetooth on by themselves only up to Android 12
            boolean on = false;
            if (android.os.Build.VERSION.SDK_INT < 33) try { on = bt.enable(); } catch (Exception ignored) { }
            if (!on) { obdStatus = ledStatus = App.s(R.string.link_bt_off); changed(); }
        }
        if (!obdRunning) {
            obdRunning = true;
            new Thread(new Runnable() { public void run() { obdLoop(); } }, "obd").start();
        }
        ledH.removeCallbacks(ledWatchdog);
        ledH.post(ledWatchdog);
    }

    /** Car powered off: stop polling the ECU (so it can sleep) and drop the LED link. */
    public synchronized void stop() {
        obdRunning = false;
        closeObd();
        ledH.removeCallbacks(ledWatchdog);
        ledH.post(new Runnable() {
            public void run() {
                stopScan();
                if (gatt != null) { gatt.close(); gatt = null; }
                ledChar = null;
                ledConnected = false;
                ledStatus = App.s(R.string.link_off);
                changed();
            }
        });
    }

    // =====================================================================  OBD

    private void obdLoop() {
        while (obdRunning) {
            try {
                if (socket == null) connectObd();
                int cycle = 0;
                while (obdRunning && socket != null) {
                    Runnable job;
                    while ((job = obdJobs.poll()) != null) job.run();
                    rpm = pidInt("010C", 2, -4) / 4;
                    speed = pidInt("010D", 1, -1);
                    if (cycle % 5 == 0) {
                        int c = pidInt("0105", 1, Integer.MIN_VALUE);
                        coolant = c == Integer.MIN_VALUE ? c : c - 40;
                        int l = pidInt("0104", 1, -1);
                        load = l < 0 ? -1 : l * 100 / 255;
                        int t = pidInt("0111", 1, -1);
                        throttle = t < 0 ? -1 : t * 100 / 255;
                        volts = parseVolts(cmd("ATRV", 2000));
                    }
                    if (cycle % 20 == 0) {
                        int ia = pidInt("010F", 1, Integer.MIN_VALUE);
                        intake = ia == Integer.MIN_VALUE ? ia : ia - 40;
                        int f = pidInt("012F", 1, -1);
                        fuel = f < 0 ? -1 : f * 100 / 255;
                    }
                    changed();
                    cycle++;
                    Thread.sleep(150);
                }
            } catch (Exception e) {
                closeObd();
                android.util.Log.i("CarLink", "obd: " + e);
                if (!needPairing) obdStatus = App.s(R.string.obd_searching);
                changed();
            }
            sleepQuiet(6000);
        }
    }

    @SuppressLint("MissingPermission")
    private void connectObd() throws Exception {
        BluetoothDevice dev = null;
        String mac = prefs.getString("obdMac", null);
        for (BluetoothDevice d : bt.getBondedDevices()) {
            String n = d.getName() == null ? "" : d.getName().toLowerCase(Locale.ROOT);
            if (d.getAddress().equals(mac)) { dev = d; break; }
            for (String k : OBD_NAMES) if (dev == null && n.contains(k)) dev = d;
        }
        for (BluetoothDevice d : bt.getBondedDevices()) android.util.Log.i("CarLink", "bonded: " + d.getName() + " " + d.getAddress() + " type " + d.getType());
        needPairing = dev == null;
        if (dev == null) { obdStatus = App.s(R.string.obd_pair); changed(); throw new Exception("no obd"); }
        obdStatus = App.s(R.string.link_connecting, dev.getName());
        changed();
        bt.cancelDiscovery();
        BluetoothSocket s;
        try {
            s = dev.createRfcommSocketToServiceRecord(SPP);
            s.connect();
        } catch (Exception first) {
            // many ELM327 clones only answer on RFCOMM channel 1
            s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
            s.connect();
        }
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
        cmd("ATZ", 3000);
        for (String init : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"}) cmd(init, 1500);
        java.util.Arrays.fill(supported, false);
        readSupported("0100", 0x01);
        readSupported("0120", 0x21);
        readSupported("0140", 0x41);
        prefs.edit().putString("obdMac", dev.getAddress()).apply();
        obdName = dev.getName();
        obdConnected = true;
        obdStatus = App.s(R.string.link_connected);
        changed();
    }

    private void closeObd() {
        obdConnected = false;
        rpm = speed = load = throttle = fuel = -1;
        coolant = intake = Integer.MIN_VALUE;
        volts = -1;
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
        socket = null;
    }

    /** Sends an ELM command and returns the reply up to the '>' prompt, without spaces/newlines. */
    private String cmd(String c, long timeoutMs) throws Exception {
        return raw(c, timeoutMs).replace("SEARCHING...", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    /** Same, but keeps the reply's line breaks (one line per ECU / frame). */
    private String raw(String c, long timeoutMs) throws Exception {
        if (out == null) throw new Exception("closed");
        while (in.available() > 0) in.read();
        out.write((c + "\r").getBytes("ASCII"));
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) {
                int ch = in.read();
                if (ch == '>') break;
                if (ch < 0) throw new Exception("eof");
                sb.append((char) ch);
            } else Thread.sleep(10);
        }
        return sb.toString();
    }

    private void readSupported(String pid, int base) {
        try {
            String r = cmd(pid, 4000);
            int i = r.indexOf("41" + pid.substring(2));
            if (i < 0) return;
            long bits = Long.parseLong(r.substring(i + 4, i + 12), 16);
            for (int b = 0; b < 32; b++) if ((bits & (1L << (31 - b))) != 0 && base + b < supported.length) supported[base + b] = true;
        } catch (Exception ignored) { }
    }

    /** Reads a mode-01 PID and returns A or (A*256+B), or def when unsupported/no data. */
    private int pidInt(String pid, int bytes, int def) throws Exception {
        int p = Integer.parseInt(pid.substring(2), 16);
        if (p < supported.length && !supported[p] && anySupported()) return def;
        String r = cmd(pid, 1500);
        if (r.contains("UNABLETOCONNECT") || r.contains("CANERROR")) throw new Exception("bus");
        int i = r.indexOf("41" + pid.substring(2));
        if (i < 0 || r.length() < i + 4 + bytes * 2) return def;
        return Integer.parseInt(r.substring(i + 4, i + 4 + bytes * 2), 16);
    }

    private boolean anySupported() { for (boolean b : supported) if (b) return true; return false; }

    private static float parseVolts(String r) {
        try { return Float.parseFloat(r.replace("V", "")); } catch (Exception e) { return -1; }
    }

    private static void sleepQuiet(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) { } }

    public interface DtcResult { void done(List<String> codes, String error); }

    /** Reads stored trouble codes (mode 03). */
    public void readDtc(final DtcResult cb) {
        if (!obdConnected) { cb.done(new ArrayList<String>(), App.s(R.string.obd_not_connected)); return; }
        obdJobs.add(new Runnable() {
            public void run() {
                final List<String> codes = new ArrayList<String>();
                String err = null;
                try { codes.addAll(parseDtc(raw("03", 5000))); } catch (Exception e) { err = App.s(R.string.obd_no_link); }
                final String fe = err;
                ui.post(new Runnable() { public void run() { cb.done(codes, fe); } });
            }
        });
    }

    /** Clears trouble codes and the check-engine light (mode 04). */
    public void clearDtc(final DtcResult cb) {
        if (!obdConnected) { cb.done(new ArrayList<String>(), App.s(R.string.obd_not_connected)); return; }
        obdJobs.add(new Runnable() {
            public void run() {
                String err = null;
                try { if (!cmd("04", 5000).contains("44")) err = App.s(R.string.obd_refused); }
                catch (Exception e) { err = App.s(R.string.obd_no_link); }
                final String fe = err;
                ui.post(new Runnable() { public void run() { cb.done(new ArrayList<String>(), fe); } });
            }
        });
    }

    static List<String> parseDtc(String reply) {
        List<String> out = new ArrayList<String>();
        for (String line : reply.toUpperCase(Locale.ROOT).split("[\\r\\n]+")) {
            String l = line.replaceAll("\\s", "");
            if (l.matches("^[0-9A-F]:.*")) l = l.substring(2);          // multi-frame "0:", "1:" prefixes
            int i = l.indexOf("43");
            if (i < 0) continue;
            String body = l.substring(i + 2);
            if (body.length() % 4 == 2) body = body.substring(2);       // CAN: leading count byte
            for (int k = 0; k + 4 <= body.length(); k += 4) {
                String h = body.substring(k, k + 4);
                if (h.equals("0000") || !h.matches("[0-9A-F]{4}")) continue;
                int first = Integer.parseInt(h.substring(0, 1), 16);
                String code = "PCBU".charAt(first >> 2) + Integer.toString(first & 3) + h.substring(1);
                if (!out.contains(code)) out.add(code);
            }
        }
        return out;
    }

    // =====================================================================  LED

    private final Runnable ledWatchdog = new Runnable() {
        @SuppressLint("MissingPermission")
        public void run() {
            if (!ledConnected && gatt == null && bt != null && Perms.bluetooth(ctx) && bt.isEnabled()) {
                String mac = prefs.getString("ledMac", null);
                if (mac != null) connectLed(bt.getRemoteDevice(mac));
                else startScan();
            }
            ledH.postDelayed(this, 12000);
        }
    };

    @SuppressLint("MissingPermission")
    private void startScan() {
        BluetoothLeScanner sc = bt.getBluetoothLeScanner();
        if (sc == null || scanning) return;
        scanning = true;
        ledStatus = App.s(R.string.led_searching);
        changed();
        try { sc.startScan(scanCb); } catch (Exception e) { scanning = false; return; }   // e.g. no location access
        ledH.postDelayed(new Runnable() { public void run() { stopScan(); } }, 10000);
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        BluetoothLeScanner sc = bt.getBluetoothLeScanner();
        if (sc != null) try { sc.stopScan(scanCb); } catch (Exception ignored) { }
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onScanResult(int type, final ScanResult r) {
            String n = r.getDevice().getName();
            if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
            if (n == null) return;
            String ln = n.toLowerCase(Locale.ROOT);
            for (String k : LED_NAMES) {
                if (ln.contains(k)) {
                    ledH.post(new Runnable() {
                        public void run() {
                            if (gatt != null) return;
                            stopScan();
                            connectLed(r.getDevice());
                        }
                    });
                    return;
                }
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void connectLed(BluetoothDevice d) {
        ledStatus = App.s(R.string.link_connecting, d.getName() == null ? App.s(R.string.led_the_strip) : d.getName());
        changed();
        gatt = d.connectGatt(ctx, false, gattCb);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(final BluetoothGatt g, int status, int state) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices();
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                ledH.post(new Runnable() {
                    public void run() {
                        g.close();
                        if (gatt == g) gatt = null;
                        ledChar = null;
                        ledConnected = false;
                        ledStatus = App.s(R.string.led_out_of_range);
                        changed();
                    }
                });
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(final BluetoothGatt g, int status) {
            ledH.post(new Runnable() {
                public void run() {
                    BluetoothGattCharacteristic ch = null;
                    BluetoothGattService s = g.getService(ELK_SVC);
                    if (s != null) { ch = s.getCharacteristic(ELK_CHR); triones = false; }
                    if (ch == null && (s = g.getService(TRI_SVC)) != null) { ch = s.getCharacteristic(TRI_CHR); triones = true; }
                    leddmx = false;
                    if (ch == null && (s = g.getService(DMX_SVC)) != null) { ch = s.getCharacteristic(DMX_CHR); triones = false; leddmx = ch != null; }
                    for (BluetoothGattService sv : g.getServices())
                        for (BluetoothGattCharacteristic c : sv.getCharacteristics())
                            android.util.Log.i("CarLink", "LED svc " + sv.getUuid() + " chr " + c.getUuid() + " props 0x" + Integer.toHexString(c.getProperties()));
                    if (ch == null) {
                        ledStatus = App.s(R.string.led_unknown);
                        g.disconnect();
                        changed();
                        return;
                    }
                    ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    ledChar = ch;
                    ledConnected = true;
                    ledStatus = App.s(R.string.led_connected, g.getDevice().getName() == null ? "LED" : g.getDevice().getName());
                    prefs.edit().putString("ledMac", g.getDevice().getAddress()).apply();
                    changed();
                    welcome();      // the strip fades up in the last colour you chose
                }
            });
        }
    };

    @SuppressLint("MissingPermission")
    private void write(byte[] v) {
        if (gatt == null || ledChar == null) return;
        ledChar.setValue(v);
        gatt.writeCharacteristic(ledChar);
        sleepQuiet(45);   // BLE allows one write at a time
    }

    private static byte[] b(int... v) {
        byte[] r = new byte[v.length];
        for (int i = 0; i < v.length; i++) r[i] = (byte) v[i];
        return r;
    }

    // LEDDMX: LEDDMX-03 uses 7B…BF frames, older LEDDMX-00 the 7E…EF ones; both are sent, the strip ignores the other.
    private void dmxPower(boolean on) {
        write(b(0x7B, 0xFF, 0x04, on ? 1 : 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xBF));
        write(b(0x7E, 0xFF, 0x04, on ? 1 : 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF));
    }

    private void dmxColor(int rgb) {
        int r = Color.red(rgb), g = Color.green(rgb), bl = Color.blue(rgb);
        write(b(0x7B, 0xFF, 0x07, r, g, bl, 0x00, 0xFF, 0xBF));
        write(b(0x7E, 0xFF, 0x05, 0x03, r, g, bl, 0xFF, 0xEF));
    }

    private void dmxBrightness(int pct) {
        pct = Math.max(0, Math.min(100, pct));
        write(b(0x7B, 0xFF, 0x01, pct * 32 / 100, pct, 0x00, 0xFF, 0xFF, 0xBF));
        write(b(0x7E, 0xFF, 0x01, pct, 0x00, 0xFF, 0xFF, 0xFF, 0xEF));
    }

    private byte[] powerCmd(boolean on) {
        if (triones) return on ? b(0xCC, 0x23, 0x33) : b(0xCC, 0x24, 0x33);
        return on ? b(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF) : b(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF);
    }

    private byte[] colorCmd(int rgb) {
        int r = Color.red(rgb), g = Color.green(rgb), bl = Color.blue(rgb);
        if (triones) {   // no brightness command: scale the colour instead
            float k = ledBrightness / 100f;
            return b(0x56, Math.round(r * k), Math.round(g * k), Math.round(bl * k), 0x00, 0xF0, 0xAA);
        }
        return b(0x7E, 0x00, 0x05, 0x03, r, g, bl, 0x00, 0xEF);
    }

    private byte[] brightnessCmd(int pct) {
        return b(0x7E, 0x00, 0x01, Math.max(0, Math.min(100, pct)), 0x00, 0x00, 0x00, 0x00, 0xEF);
    }

    private void applyLed() {
        if (leddmx) {
            dmxPower(ledOn);
            if (ledOn) { dmxColor(ledColor); dmxBrightness(ledBrightness); }
            return;
        }
        write(powerCmd(ledOn));
        if (ledOn) {
            write(colorCmd(ledColor));
            if (!triones) write(brightnessCmd(ledBrightness));
        }
    }

    /** Ignition-on greeting: a short sweep through the brand blues, then fade up to your colour. */
    private void welcome() {
        if (!ledOn) { applyLed(); return; }
        int[] sweep = {0x0A2A6B, 0x2F7BFF, 0xBFE6FF};
        if (leddmx) dmxPower(true); else write(powerCmd(true));
        for (int c : sweep) { sendRaw(c, 60); sleepQuiet(160); }
        for (int k = 0; k <= 10; k++) { sendRaw(ledColor, ledBrightness * k / 10); sleepQuiet(70); }
        shownColor = ledColor;
        shownLevel = 1f;
        changed();
        ledH.removeCallbacks(fx);
        ledH.postDelayed(fx, 200);
    }

    /** Writes colour + brightness in whatever dialect the strip speaks. */
    private void sendRaw(int rgb, int pct) {
        if (leddmx) { dmxColor(rgb); dmxBrightness(pct); return; }
        if (triones) {
            float k = pct / 100f;
            write(b(0x56, Math.round(Color.red(rgb) * k), Math.round(Color.green(rgb) * k), Math.round(Color.blue(rgb) * k), 0x00, 0xF0, 0xAA));
            return;
        }
        write(colorCmd(rgb));
        write(brightnessCmd(pct));
    }

    public void setLedEffect(int fx) {
        ledEffect = fx;
        prefs.edit().putInt("ledFx", fx).apply();
        ledH.removeCallbacks(fx_);
        ledH.post(fx_);
        changed();
    }

    private final Runnable fx_ = new Runnable() {
        public void run() {
            if (ledEffect == FX_STATIC) { shownColor = ledColor; shownLevel = 1f; applyLed(); changed(); }
            ledH.removeCallbacks(fx);
            ledH.post(fx);
        }
    };

    /** Effect engine: ~8 updates a second while an animated effect is on. */
    private final Runnable fx = new Runnable() {
        public void run() {
            if (!ledConnected || !ledOn || ledEffect == FX_STATIC) return;
            double t = android.os.SystemClock.uptimeMillis() / 1000.0;
            int color = ledColor;
            float lvl;
            switch (ledEffect) {
                case FX_BREATHE:
                    lvl = (float) (0.25 + 0.75 * (0.5 + 0.5 * Math.sin(t * 2 * Math.PI / 4.0)));
                    break;
                case FX_PULSE: {                       // heartbeat: two quick beats, then rest
                    double ph = t % 1.4;
                    lvl = (float) (0.2 + 0.8 * Math.max(Math.exp(-Math.pow((ph - 0.15) / 0.07, 2)), 0.8 * Math.exp(-Math.pow((ph - 0.45) / 0.07, 2))));
                    break;
                }
                case FX_AURORA:
                    color = Color.HSVToColor(new float[]{(float) ((t * 18) % 360), 0.9f, 1f}) & 0xFFFFFF;
                    lvl = 1f;
                    break;
                case FX_REVS: {                         // idle = blue, red line = red
                    float k = rpm < 0 ? 0 : Math.max(0, Math.min(1, (rpm - 800) / 3400f));
                    float hue = 215 - 215 * k;
                    color = Color.HSVToColor(new float[]{hue < 0 ? 0 : hue, 1f, 1f}) & 0xFFFFFF;
                    lvl = 0.5f + 0.5f * k;
                    break;
                }
                default:
                    lvl = 1f;
            }
            shownColor = color;
            shownLevel = lvl;
            sendRaw(color, Math.max(3, Math.round(ledBrightness * lvl)));
            changed();
            ledH.postDelayed(this, 120);
        }
    };

    public void setLedPower(final boolean on) {
        ledOn = on;
        prefs.edit().putBoolean("ledOn", on).apply();
        ledH.post(new Runnable() { public void run() { applyLed(); if (on) { ledH.removeCallbacks(fx); ledH.post(fx); } } });
        changed();
    }

    public void setLedColor(int rgb) {
        ledColor = rgb & 0xFFFFFF;
        if (ledEffect == FX_STATIC || ledEffect == FX_BREATHE || ledEffect == FX_PULSE) shownColor = ledColor;
        if (!ledOn) { ledOn = true; prefs.edit().putBoolean("ledOn", true).apply(); }
        prefs.edit().putInt("ledColor", ledColor).apply();
        synchronized (this) {
            boolean idle = pendingColor == null;
            pendingColor = colorCmd(ledColor);
            if (idle) ledH.post(sendColor);
        }
        changed();
    }

    private final Runnable sendColor = new Runnable() {
        public void run() {
            byte[] v;
            synchronized (CarLink.this) { v = pendingColor; pendingColor = null; }
            if (v == null) return;
            if (leddmx) dmxColor(ledColor); else write(v);
        }
    };

    public void setLedBrightness(final int pct) {
        ledBrightness = pct;
        prefs.edit().putInt("ledBright", pct).apply();
        ledH.removeCallbacks(sendBright);
        ledH.postDelayed(sendBright, 60);
        changed();
    }

    private final Runnable sendBright = new Runnable() {
        public void run() {
            if (leddmx) dmxBrightness(ledBrightness);
            else if (triones) write(colorCmd(ledColor));
            else write(brightnessCmd(ledBrightness));
        }
    };

    /** Forget the saved LED strip so the next search picks a new one. */
    public void forgetLed() {
        prefs.edit().remove("ledMac").apply();
        ledH.post(new Runnable() {
            public void run() { if (gatt != null) { gatt.close(); gatt = null; } ledConnected = false; ledChar = null; }
        });
    }
}
