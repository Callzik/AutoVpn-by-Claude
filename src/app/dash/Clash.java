package app.dash;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Talks to the sing-box Clash API on localhost. */
public final class Clash {
    private final String secret;

    public Clash(String secret) { this.secret = secret; }

    private String call(String method, String path, String body, int readTimeout) throws Exception {
        URL u = new URL("http://127.0.0.1:" + ConfigBuilder.CLASH_PORT + path);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(3000);
            c.setReadTimeout(readTimeout);
            c.setRequestMethod(method);
            c.setRequestProperty("Authorization", "Bearer " + secret);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                OutputStream os = c.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.close();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : read(in);
            if (code >= 400) throw new Exception("HTTP " + code + " " + text);
            return text;
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    public boolean alive() {
        try {
            call("GET", "/", null, 2000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void select(String selector, String name) throws Exception {
        call("PUT", "/proxies/" + enc(selector), new JSONObject().put("name", name).toString(), 4000);
    }

    /** Tag of the member currently chosen by a selector or urltest group. */
    public String now(String group) throws Exception {
        return new JSONObject(call("GET", "/proxies/" + enc(group), null, 4000)).optString("now", "");
    }

    /** Last measured delay of a single outbound, -1 when unknown or failed. */
    public int lastDelay(String tag) throws Exception {
        JSONObject o = new JSONObject(call("GET", "/proxies/" + enc(tag), null, 4000));
        JSONArray h = o.optJSONArray("history");
        if (h == null || h.length() == 0) return -1;
        int d = h.getJSONObject(h.length() - 1).optInt("delay", 0);
        return d > 0 ? d : -1;
    }

    /** Delay of one outbound (e.g. "direct") to the given URL, -1 on failure. */
    public int proxyDelay(String tag, String url, int timeoutMs) throws Exception {
        String path = "/proxies/" + enc(tag) + "/delay?url=" + enc(url) + "&timeout=" + timeoutMs;
        try {
            return new JSONObject(call("GET", path, null, timeoutMs + 4000)).optInt("delay", -1);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().startsWith("HTTP 5")) return -1;
            throw e;
        }
    }

    /**
     * Tests every member of a group now, each one separately and in parallel.
     * (The core's /group/…/delay returns an empty result while the group's own check is running,
     * which happens right after start and on every network change.)
     * Returns tag → delay for members that answered.
     */
    public Map<String, Integer> testGroup(String group, int timeoutMs) throws Exception {
        return testGroup(group, timeoutMs, 0, null);
    }

    public interface Done { void onDone(Map<String, Integer> all); }

    /**
     * graceMs > 0: returns graceMs after the first answer instead of waiting for silent members to time out.
     * The rest keep testing (their results still reach the core) and done, if given, gets the full result.
     */
    public Map<String, Integer> testGroup(String group, final int timeoutMs, long graceMs, final Done done) throws Exception {
        JSONArray all = new JSONObject(call("GET", "/proxies/" + enc(group), null, 4000)).optJSONArray("all");
        final Map<String, Integer> m = new ConcurrentHashMap<>();
        if (all == null || all.length() == 0) return m;
        int n = all.length(), threads = Math.min(n, 64);
        final long limit = (long) (timeoutMs + 8000) * ((n + threads - 1) / threads);
        final CountDownLatch left = new CountDownLatch(n);
        final CountDownLatch first = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < n; i++) {
            final String tag = all.getString(i);
            pool.execute(new Runnable() {
                @Override public void run() {
                    try {
                        int d = proxyDelay(tag, ConfigBuilder.TEST_URL, timeoutMs);
                        if (d > 0) {
                            m.put(tag, d);
                            first.countDown();
                        }
                    } catch (Exception ignored) {
                    } finally {
                        left.countDown();
                    }
                }
            });
        }
        pool.shutdown();
        if (done != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        left.await(limit, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                    }
                    done.onDone(new HashMap<>(m));
                }
            }, "ping-rest").start();
        }
        try {
            if (graceMs > 0) {
                if (first.await(limit, TimeUnit.MILLISECONDS)) left.await(graceMs, TimeUnit.MILLISECONDS);
            } else {
                left.await(limit, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException ignored) {
        }
        return new HashMap<>(m);
    }

    public interface Listener { void onResult(String tag, int delay); }

    /** Tests the given outbounds in parallel, reporting each result as it arrives (-1 = no answer). */
    public void testTags(List<String> tags, final int timeoutMs, final Listener l) {
        if (tags.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tags.size(), 48));
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (final String tag : tags) {
                fs.add(pool.submit(new Runnable() {
                    @Override public void run() {
                        int d = -1;
                        try {
                            d = proxyDelay(tag, ConfigBuilder.TEST_URL, timeoutMs);
                        } catch (Exception ignored) {
                        }
                        l.onResult(tag, d > 0 ? d : -1);
                    }
                }));
            }
            for (Future<?> f : fs) {
                try { f.get(timeoutMs + 10000, TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Makes the urltest group re-test all members and re-select. Returns an empty map when the group
     * was already checking (its own check then re-selects when it finishes).
     */
    public Map<String, Integer> groupCheck(String group, int timeoutMs) throws Exception {
        String path = "/group/" + enc(group) + "/delay?url=" + enc(ConfigBuilder.TEST_URL) + "&timeout=" + timeoutMs;
        JSONObject o = new JSONObject(call("GET", path, null, timeoutMs + 20000));
        Map<String, Integer> m = new HashMap<>();
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            int d = o.optInt(k, 0);
            if (d > 0) m.put(k, d);
        }
        return m;
    }

    /** Closes all open connections so apps reconnect at once through the new server. */
    public void closeAll() throws Exception {
        call("DELETE", "/connections", null, 4000);
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
}
