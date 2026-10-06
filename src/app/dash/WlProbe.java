package app.dash;

import android.net.Network;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

        public int foreignOk() {
            int n = 0;
            for (boolean b : foreign) if (b) n++;
            return n;
        }

        public String detail() {
            return "Госуслуги " + (ru[0] || ru[1] ? "да" : "нет") + " · зарубежные " + foreignOk() + " из " + foreign.length;
        }

        public String logLine() {
            return "Проверка БС: google " + ok(foreign[0]) + ", cloudflare " + ok(foreign[1]) + ", wikipedia " + ok(foreign[2])
                    + ", gosuslugi " + ok(ru[0]) + ", ya.ru " + ok(ru[1]) + " → "
                    + (state == AppState.WL_ON ? "включены" : state == AppState.WL_OFF ? "выключены" : "нет интернета");
        }

        private static String ok(boolean b) { return b ? "ok" : "нет"; }
    }

    private WlProbe() {}

    /**
     * All sites at once. White lists are on when Russian sites open but at most one of three foreign ones does
     * (a single foreign site may happen to be allowed or a single one may be down).
     */
    public static Result run(final Network network) {
        final Result r = new Result();
        final CountDownLatch latch = new CountDownLatch(RU.length + FOREIGN.length);
        start(network, FOREIGN, r.foreign, latch);
        start(network, RU, r.ru, latch);
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        boolean ruOk = r.ru[0] || r.ru[1];
        int fr = r.foreignOk();
        if (fr >= 2) r.state = AppState.WL_OFF;
        else if (ruOk) r.state = AppState.WL_ON;
        else if (fr == 1) r.state = AppState.WL_OFF; // Russian sites down but abroad works: not white lists
        else r.state = AppState.WL_NONET;
        return r;
    }

    private static void start(final Network network, final String[] urls, final boolean[] out, final CountDownLatch latch) {
        for (int i = 0; i < urls.length; i++) {
            final int k = i;
            new Thread(new Runnable() {
                @Override public void run() {
                    out[k] = reachable(network, urls[k]);
                    latch.countDown();
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
