package app.dash;

import android.net.Network;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Detects operator white lists: Russian sites open, foreign ones do not.
 * Requests go straight through the physical network, the app itself is excluded from the VPN.
 */
public final class WlProbe {
    public static final String[] RU = {"https://www.gosuslugi.ru/", "https://ya.ru/"};
    public static final String[] FOREIGN = {"https://www.google.com/generate_204", "https://www.cloudflare.com/cdn-cgi/trace",
            "https://www.wikipedia.org/"};

    public static final class Result {
        public int state;          // AppState.WL_*
        public boolean[] ru = new boolean[RU.length];
        public boolean[] foreign = new boolean[FOREIGN.length];
        boolean[] ruDone = new boolean[RU.length];
        boolean[] foreignDone = new boolean[FOREIGN.length];

        public int foreignOk() {
            int n = 0;
            for (boolean b : foreign) if (b) n++;
            return n;
        }

        public String detail() {
            return "Госуслуги " + (ru[0] || ru[1] ? "да" : "нет") + " · зарубежные " + foreignOk() + " из " + foreign.length;
        }

        public String logLine() {
            return "Проверка БС: google " + ok(foreign[0], foreignDone[0]) + ", cloudflare " + ok(foreign[1], foreignDone[1])
                    + ", wikipedia " + ok(foreign[2], foreignDone[2])
                    + ", gosuslugi " + ok(ru[0], ruDone[0]) + ", ya.ru " + ok(ru[1], ruDone[1]) + " → "
                    + (state == AppState.WL_ON ? "включены" : state == AppState.WL_OFF ? "выключены" : "нет интернета");
        }

        private static String ok(boolean b, boolean done) { return b ? "ok" : done ? "нет" : "не дождались"; }
    }

    private WlProbe() {}

    /**
     * All sites at once. White lists are on when Russian sites open but at most one of three foreign ones does
     * (a single foreign site may happen to be allowed or a single one may be down).
     * Stops waiting as soon as the answer is clear: two foreign sites and a Russian one opened.
     */
    public static Result run(final Network network) {
        final Result r = new Result();
        start(network, FOREIGN, r.foreign, r.foreignDone, r);
        start(network, RU, r.ru, r.ruDone, r);
        long deadline = System.currentTimeMillis() + 8000;
        synchronized (r) {
            while (!decided(r)) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    r.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
            boolean ruOk = r.ru[0] || r.ru[1];
            int fr = r.foreignOk();
            if (fr >= 2) r.state = AppState.WL_OFF;
            else if (ruOk) r.state = AppState.WL_ON;
            else if (fr == 1) r.state = AppState.WL_OFF; // Russian sites down but abroad works: not white lists
            else r.state = AppState.WL_NONET;
            // a snapshot: late answers must not change what was logged
            Result out = new Result();
            out.state = r.state;
            System.arraycopy(r.ru, 0, out.ru, 0, RU.length);
            System.arraycopy(r.ruDone, 0, out.ruDone, 0, RU.length);
            System.arraycopy(r.foreign, 0, out.foreign, 0, FOREIGN.length);
            System.arraycopy(r.foreignDone, 0, out.foreignDone, 0, FOREIGN.length);
            return out;
        }
    }

    private static boolean decided(Result r) {
        boolean all = true;
        for (boolean d : r.ruDone) all &= d;
        for (boolean d : r.foreignDone) all &= d;
        return all || (r.foreignOk() >= 2 && (r.ru[0] || r.ru[1]));
    }

    private static void start(final Network network, final String[] urls, final boolean[] out, final boolean[] done, final Result r) {
        for (int i = 0; i < urls.length; i++) {
            final int k = i;
            new Thread(new Runnable() {
                @Override public void run() {
                    boolean ok = reachable(network, urls[k]);
                    synchronized (r) {
                        out[k] = ok;
                        done[k] = true;
                        r.notifyAll();
                    }
                }
            }).start();
        }
    }

    static boolean reachable(Network network, String url) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            c = (HttpURLConnection) (network != null ? network.openConnection(u) : u.openConnection());
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128 Mobile Safari/537.36");
            int code = c.getResponseCode();
            // a real answer from the real site, not an operator stub
            if (url.endsWith("/generate_204")) return code == 204;
            if (url.contains("/cdn-cgi/trace")) return code == 200 && Clash.read(c.getInputStream()).contains("ip=");
            return code > 0 && code < 500;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
