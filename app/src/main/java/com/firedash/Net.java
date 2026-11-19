package com.firedash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTP helpers. Fire OS 5 (Android 5.1) is too old to know some of today's root
 * certificates (e.g. Let's Encrypt's), so we add a bundle of modern roots on top of the system ones.
 */
final class Net {
    private Net() {}

    private static boolean installed;

    /** Trust the system roots plus the bundled ones. Call once at startup. */
    static synchronized void installExtraRoots(InputStream pem) {
        if (installed) return;
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(pem);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int i = 0;
            for (java.security.cert.Certificate c : certs) ks.setCertificateEntry("root" + (i++), c);
            final X509TrustManager extra = trustManager(ks);
            final X509TrustManager system = trustManager(null);
            X509TrustManager both = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    system.checkClientTrusted(chain, auth);
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    try {
                        system.checkServerTrusted(chain, auth);
                    } catch (CertificateException e) {
                        extra.checkServerTrusted(chain, auth);
                    }
                }
                @Override public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
            };
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] { both }, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(ctx.getSocketFactory());
            installed = true;
        } catch (Exception e) {
            // Fall back to the system roots only.
        } finally {
            try { pem.close(); } catch (IOException ignored) { }
        }
    }

    private static X509TrustManager trustManager(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no trust manager");
    }

    static String get(String url) throws IOException {
        return new String(getBytes(url), "UTF-8");
    }

    /** Tries each URL in turn and returns the first that works. */
    static String getFirst(String... urls) throws IOException {
        IOException first = null;
        for (String u : urls) {
            if (u == null || u.isEmpty()) continue;
            try {
                return get(u);
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        throw first != null ? first : new IOException("no URL");
    }

    static byte[] getBytes(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            in.close();
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    static void download(String url, File dest) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            try {
                copy(in, out);
            } finally {
                out.close();
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        // Follow redirects ourselves, including http <-> https, which HttpURLConnection won't.
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "FireDashboard/2.0 (Android)");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null) throw new IOException("bad redirect");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                conn.disconnect();
                throw new IOException("server error " + code);
            }
            return conn;
        }
        throw new IOException("too many redirects");
    }

    /** A reply from send(): the status code and body, whatever the status. */
    static final class Reply {
        final int code;
        final String body;
        Reply(int code, String body) { this.code = code; this.body = body; }
    }

    /**
     * Sends a request with an optional JSON body and bearer token. Used for the JARVIS server on the LAN.
     * Unlike get(), error statuses are returned rather than thrown, so the server's message can be shown.
     */
    static Reply send(String method, String url, String json, String bearer, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", "FireDashboard/2.0 (Android)");
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            if (json != null) {
                byte[] bytes = json.getBytes("UTF-8");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(bytes);
                } finally {
                    out.close();
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (in != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try {
                    copy(in, buf);
                } finally {
                    in.close();
                }
                body = new String(buf.toByteArray(), "UTF-8");
            }
            return new Reply(code, body);
        } finally {
            conn.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
    }

    /** A short, readable reason. */
    static String describe(Exception e) {
        if (e instanceof UnknownHostException) return "no connection";
        if (e instanceof SocketTimeoutException) return "timed out";
        String m = e.getMessage();
        if (m != null && m.startsWith("server error")) return m;
        if (e instanceof javax.net.ssl.SSLException) return "secure connection failed";
        if (e instanceof IllegalArgumentException) return "unexpected reply";
        return "network error";
    }
}
