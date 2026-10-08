package bg.dmd.carhome.nav;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** HTTPS helpers. Android 5 lacks the Let's Encrypt root (map tiles), so it is bundled and trusted alongside the system store. */
public final class Net {
    private static SSLSocketFactory factory;

    private Net() { }

    public static synchronized void init(Context c) {
        if (factory != null) return;
        try {
            final X509TrustManager system = defaultTm(null);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            InputStream in = c.getResources().openRawResource(bg.dmd.carhome.R.raw.isrgrootx1);
            ks.setCertificateEntry("isrgx1", CertificateFactory.getInstance("X.509").generateCertificate(in));
            in.close();
            final X509TrustManager extra = defaultTm(ks);
            X509TrustManager both = new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] ch, String a) throws CertificateException { system.checkClientTrusted(ch, a); }
                public void checkServerTrusted(X509Certificate[] ch, String a) throws CertificateException {
                    try { system.checkServerTrusted(ch, a); } catch (CertificateException e) { extra.checkServerTrusted(ch, a); }
                }
                public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{both}, null);
            factory = ctx.getSocketFactory();
        } catch (Exception e) {
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
    }

    private static X509TrustManager defaultTm(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && factory != null) ((HttpsURLConnection) c).setSSLSocketFactory(factory);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "CarHome/1.0 (open-source car display)");
        return c;
    }

    public static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            return readAll(c.getInputStream());
        } finally { c.disconnect(); }
    }

    public static String postJson(String url, String body, Map<String, String> headers) throws Exception {
        HttpURLConnection c = open(url);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            OutputStream o = c.getOutputStream();
            o.write(body.getBytes("UTF-8"));
            o.close();
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String s = in == null ? "" : new String(readAll(in), "UTF-8");
            if (code >= 400) throw new Exception("HTTP " + code + ": " + s);
            return s;
        } finally { c.disconnect(); }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
}
