package app.dash;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Live numbers for the main screen tiles: speed, traffic and the VPN exit IP with its country. */
final class Stats {
    private Stats() {}

    // speed and traffic, bytes and bytes/s
    static volatile long down, up, downRate, upRate;
    static volatile boolean hasTraffic;

    // exit IP
    static volatile String ip = "", cc = "", country = "";
    static volatile boolean ipBusy;
    static volatile boolean ipFailed;

    private static long lastDown = -1, lastUp, lastAt;
    private static String ipFor = "";
    private static long ipTriedAt;
    private static volatile boolean polling;
    private static final ExecutorService exec = Executors.newSingleThreadExecutor();

    /** Called once a second while the main screen is visible. */
    static void tick() {
        BoxVpnService s = BoxVpnService.instance;
        final Clash cl = s == null ? null : s.clashApi();
        if (cl == null || AppState.vpn == AppState.OFF || AppState.vpn == AppState.CONNECTING) {
            reset();
            return;
        }
        if (!polling) {
            polling = true;
            exec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        long[] t = cl.totals();
                        long now = System.currentTimeMillis();
                        if (lastDown >= 0 && t[0] >= lastDown && now > lastAt) {
                            long dt = now - lastAt;
                            downRate = (t[0] - lastDown) * 1000 / dt;
                            upRate = (t[1] - lastUp) * 1000 / dt;
                        }
                        lastDown = t[0];
                        lastUp = t[1];
                        lastAt = now;
                        down = t[0];
                        up = t[1];
                        hasTraffic = true;
                    } catch (Exception ignored) {
                    } finally {
                        polling = false;
                    }
                }
            });
        }
        // exit IP: once per server, retry a failed lookup after 20 s
        String srv = AppState.serverName;
        if (AppState.vpn == AppState.ON && !ipBusy && !srv.isEmpty()
                && (!srv.equals(ipFor) || (ip.isEmpty() && System.currentTimeMillis() - ipTriedAt > 20000))) {
            ipBusy = true;
            ipFor = srv;
            ipTriedAt = System.currentTimeMillis();
            final String secret = cl.secret();
            new Thread(new Runnable() {
                @Override public void run() { lookup(secret); }
            }, "exit-ip").start();
        }
    }

    static void reset() {
        lastDown = -1;
        downRate = upRate = 0;
        hasTraffic = false;
        ip = cc = country = "";
        ipFor = "";
        ipFailed = false;
    }

    /** Forget the IP so the next tick asks again (tap on the tile). */
    static void refreshIp() {
        if (!ipBusy) ipFor = "";
    }

    private static void lookup(String secret) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(
                    getViaProbe(secret, "ip-api.com", "/json/?fields=status,query,countryCode"));
            if (!"success".equals(o.optString("status"))) throw new Exception("ip-api: " + o);
            ip = o.optString("query", "");
            cc = o.optString("countryCode", "").toUpperCase(Locale.ROOT);
        } catch (Exception e) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(getViaProbe(secret, "ipwho.is", "/?fields=ip,country_code"));
                ip = o.optString("ip", "");
                cc = o.optString("country_code", "").toUpperCase(Locale.ROOT);
            } catch (Exception e2) {
                AppState.log("Не удалось узнать внешний IP: " + e2.getMessage());
            }
        }
        country = cc.length() == 2 ? new Locale("", cc).getDisplayCountry(new Locale("ru")) : "";
        ipFailed = ip.isEmpty();
        ipBusy = false;
    }

    /** Plain HTTP GET through the core's local proxy, so the request leaves via the VPN server. */
    private static String getViaProbe(String secret, String host, String path) throws Exception {
        try (Socket so = new Socket()) {
            so.connect(new InetSocketAddress("127.0.0.1", ConfigBuilder.PROBE_PORT), 3000);
            so.setSoTimeout(10000);
            String auth = android.util.Base64.encodeToString(("dash:" + secret).getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String req = "GET http://" + host + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nProxy-Authorization: Basic " + auth
                    + "\r\nUser-Agent: Dash\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
            OutputStream os = so.getOutputStream();
            os.write(req.getBytes("UTF-8"));
            os.flush();
            InputStream in = so.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 64 * 1024) bo.write(buf, 0, n);
            String r = bo.toString("UTF-8");
            int sep = r.indexOf("\r\n\r\n");
            if (sep < 0) throw new Exception("пустой ответ");
            String head = r.substring(0, sep), body = r.substring(sep + 4);
            if (!head.startsWith("HTTP/1.1 200") && !head.startsWith("HTTP/1.0 200")) {
                throw new Exception(head.split("\r\n")[0]);
            }
            if (head.toLowerCase(Locale.ROOT).contains("transfer-encoding: chunked")) body = unchunk(body);
            return body;
        }
    }

    private static String unchunk(String b) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < b.length()) {
            int eol = b.indexOf("\r\n", i);
            if (eol < 0) break;
            int len;
            try {
                len = Integer.parseInt(b.substring(i, eol).split(";")[0].trim(), 16);
            } catch (NumberFormatException e) {
                break;
            }
            if (len == 0) break;
            int start = eol + 2, end = Math.min(b.length(), start + len);
            out.append(b, start, end);
            i = end + 2;
        }
        return out.toString();
    }

    /* ---------- formatting ---------- */

    static String flag(String cc) {
        if (cc == null || cc.length() != 2) return "🌐";
        int a = 0x1F1E6 + (cc.charAt(0) - 'A'), b = 0x1F1E6 + (cc.charAt(1) - 'A');
        return new String(Character.toChars(a)) + new String(Character.toChars(b));
    }

    /** Bytes/s as megabits per second, e.g. "4.2" or "0.08". */
    static String mbit(long bytesPerSec) {
        double m = bytesPerSec * 8 / 1_000_000.0;
        if (m >= 100) return String.format(Locale.ROOT, "%.0f", m);
        if (m >= 0.1 || m == 0) return String.format(Locale.ROOT, "%.1f", m);
        return String.format(Locale.ROOT, "%.2f", m);
    }

    static String bytes(long b) {
        if (b < 1024) return b + " Б";
        double k = b / 1024.0;
        if (k < 1024) return String.format(Locale.ROOT, "%.0f КБ", k);
        double m = k / 1024;
        if (m < 1024) return String.format(Locale.ROOT, m < 10 ? "%.1f МБ" : "%.0f МБ", m);
        return String.format(Locale.ROOT, "%.2f ГБ", m / 1024);
    }
}
