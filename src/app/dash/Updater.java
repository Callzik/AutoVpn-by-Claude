package app.dash;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub for a newer build and installs it (one tap: download + system install prompt). */
public final class Updater {
    static final String VERSION_URL = "https://raw.githubusercontent.com/Callzik/DashVPN-by-Claude/main/version.json";
    static final String ACTION_STATUS = "app.dash.INSTALL_STATUS";
    private static final long CHECK_EVERY = 3 * 3600_000L;

    // state for the main screen
    static volatile String newVersion = "";
    static volatile String apkUrl = "";
    static volatile String apkSha = ""; // lowercase hex SHA-256 from version.json, empty if not published
    static volatile int progress = -1; // -1 idle, 0..100 downloading, 101 installing
    static volatile String error = "";
    private static volatile boolean checking;

    private Updater() {}

    static int currentCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; // int field works on API 26-27 too
        } catch (Exception e) {
            return 0;
        }
    }

    /** Background check, at most every few hours unless forced. */
    static void check(final Context ctx, boolean force) {
        final Context c = ctx.getApplicationContext();
        final android.content.SharedPreferences sp = c.getSharedPreferences("main", Context.MODE_PRIVATE);
        // show what was found earlier right away
        if (newVersion.isEmpty() && sp.getInt("upd_code", 0) > currentCode(c)) {
            newVersion = sp.getString("upd_ver", "");
            apkUrl = sp.getString("upd_url", "");
            apkSha = sp.getString("upd_sha", "");
        }
        if (checking) return;
        if (!force && System.currentTimeMillis() - sp.getLong("upd_checked", 0) < CHECK_EVERY) return;
        checking = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject a = new JSONObject(get(VERSION_URL)).getJSONObject("android");
                    int code = a.getInt("code");
                    String ver = a.getString("version"), url = a.getString("url");
                    String sha = a.optString("sha256", "").trim().toLowerCase(java.util.Locale.ROOT);
                    sp.edit().putLong("upd_checked", System.currentTimeMillis())
                            .putInt("upd_code", code).putString("upd_ver", ver).putString("upd_url", url)
                            .putString("upd_sha", sha).apply();
                    if (code > currentCode(c)) {
                        newVersion = ver;
                        apkUrl = url;
                        apkSha = sha;
                    } else {
                        newVersion = "";
                    }
                } catch (Exception e) {
                    AppState.log("Проверка обновлений: " + e.getMessage());
                } finally {
                    checking = false;
                }
            }
        }, "update-check").start();
    }

    private static String get(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        h.setUseCaches(false);
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    /** Called from the "Обновить" button. */
    static void start(final Activity act) {
        if (progress >= 0 || apkUrl.isEmpty()) return;
        if (!act.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(act, "Разрешите Dash устанавливать обновления и нажмите «Обновить» ещё раз", Toast.LENGTH_LONG).show();
            act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.getPackageName())));
            return;
        }
        final Context c = act.getApplicationContext();
        final String url = apkUrl, sha = apkSha;
        progress = 0;
        error = "";
        new Thread(new Runnable() {
            @Override public void run() {
                File f = new File(c.getCacheDir(), "update.apk");
                try {
                    download(url, f);
                    if (!sha.isEmpty() && !sha.equals(sha256(f))) {
                        f.delete();
                        error = "Файл обновления повреждён";
                        AppState.log("Обновление: SHA-256 не совпал");
                        progress = -1;
                        return;
                    }
                    progress = 101;
                    install(c, f);
                } catch (Exception e) {
                    error = "Не удалось загрузить обновление";
                    AppState.log("Обновление: " + e);
                    progress = -1;
                }
            }
        }, "update-download").start();
    }

    private static void download(String u, File out) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(true);
        long total = h.getContentLengthLong();
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                done += n;
                if (total > 0) progress = (int) Math.min(100, done * 100 / total);
            }
        } finally {
            h.disconnect();
        }
        if (out.length() < 1_000_000) throw new Exception("файл слишком мал");
    }

    private static String sha256(File f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) b.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return b.toString();
    }

    private static void install(Context c, File apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("dash.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            Intent i = new Intent(c, MainActivity.class).setAction(ACTION_STATUS);
            PendingIntent p = PendingIntent.getActivity(c, 7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(p.getIntentSender());
        }
    }

    /** MainActivity got the installer's answer. */
    static void onStatus(Activity act, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) act.startActivity(confirm);
            return;
        }
        progress = -1;
        if (st != PackageInstaller.STATUS_SUCCESS) {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            error = st == PackageInstaller.STATUS_FAILURE_ABORTED ? "" : "Установка не удалась";
            if (m != null) AppState.log("Обновление: " + m);
        }
    }
}
