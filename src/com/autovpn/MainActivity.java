package com.autovpn;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private Prefs prefs;
    private FrameLayout root;
    private View mainView, onboardingView;

    private TextView netChip, title, subtitle, banner, wlSub, wlBadge, srvName, srvSub, srvBadge, rtRu, rtRest, errorText;
    private PowerButton power;
    private EditText subInput;
    private TextView subError;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };
    private final Runnable listener = new Runnable() {
        @Override public void run() { render(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        mainView = buildMain();
        onboardingView = buildOnboarding();
        root.addView(mainView);
        root.addView(onboardingView);
        showScreen();
        askNotifications();
        VpnControl.init(this);
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Tile / widget asked to connect but needed the screen (permission or subscription). */
    private void handleIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(VpnControl.EXTRA_CONNECT, false)) return;
        intent.removeExtra(VpnControl.EXTRA_CONNECT);
        if (AppState.vpn == AppState.OFF && !prefs.subUrl().trim().isEmpty()) toggle();
    }

    @Override protected void onResume() {
        super.onResume();
        AppState.addListener(listener);
        handler.post(ticker);
        showScreen();
    }

    @Override protected void onPause() {
        AppState.removeListener(listener);
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void showScreen() {
        boolean hasSub = !prefs.subUrl().trim().isEmpty();
        mainView.setVisibility(hasSub ? View.VISIBLE : View.GONE);
        onboardingView.setVisibility(hasSub ? View.GONE : View.VISIBLE);
        render();
    }

    /* ---------- main screen ---------- */

    private View buildMain() {
        Context c = this;
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 20), Ui.dp(c, 20), Ui.dp(c, 24));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout top = Ui.row(c);
        netChip = Ui.text(c, "Wi-Fi", 14, Ui.FG, true);
        netChip.setBackground(Ui.round(c, Ui.SURFACE, 18, Ui.LINE));
        netChip.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        top.addView(netChip);
        // fixed 1px height: a plain View with WRAP_CONTENT would take the whole screen height
        TextView ver = Ui.text(c, "v" + versionName(), 12, Ui.MUTED, false);
        ver.setGravity(Gravity.CENTER);
        top.addView(ver, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView settings = Ui.text(c, "Настройки", 14, Ui.FG, true);
        settings.setBackground(Ui.round(c, Ui.SURFACE, 18, Ui.LINE));
        settings.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        top.addView(settings);
        col.addView(top);

        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        power = new PowerButton(c);
        power.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        hero.addView(power, new LinearLayout.LayoutParams(Ui.dp(c, 264), Ui.dp(c, 264)));
        title = Ui.text(c, "Не подключено", 28, Ui.FG, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 18));
        subtitle = Ui.text(c, "", 15, Ui.MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        errorText = Ui.text(c, "", 14, Ui.BAD, false);
        errorText.setGravity(Gravity.CENTER);
        hero.addView(errorText, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        heroLp.topMargin = Ui.dp(c, 16);
        hero.setMinimumHeight(Ui.dp(c, 400));
        hero.setPadding(0, Ui.dp(c, 12), 0, Ui.dp(c, 12));
        col.addView(hero, heroLp);

        banner = Ui.text(c, "", 13, Ui.WARN_FG, false);
        banner.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        banner.setVisibility(View.GONE);
        col.addView(banner, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        // white lists
        LinearLayout wl = Ui.card(c);
        LinearLayout wlRow = Ui.row(c);
        LinearLayout wlText = new LinearLayout(c);
        wlText.setOrientation(LinearLayout.VERTICAL);
        wlText.addView(Ui.text(c, "Белые списки", 15, Ui.FG, true));
        wlSub = Ui.text(c, "Госуслуги и Google", 13, Ui.MUTED, false);
        wlText.addView(wlSub);
        wlRow.addView(wlText, Ui.weight());
        wlBadge = Ui.badge(c);
        wlRow.addView(wlBadge);
        wl.addView(wlRow);
        col.addView(wl, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        // server
        LinearLayout srv = Ui.card(c);
        srv.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16));
        LinearLayout srvRow = Ui.row(c);
        LinearLayout srvText = new LinearLayout(c);
        srvText.setOrientation(LinearLayout.VERTICAL);
        srvName = Ui.text(c, "Автовыбор сервера", 17, Ui.FG, true);
        srvSub = Ui.text(c, "", 14, Ui.MUTED, false);
        srvText.addView(srvName);
        srvText.addView(srvSub);
        srvRow.addView(srvText, Ui.weight());
        srvBadge = Ui.badge(c);
        srvRow.addView(srvBadge);
        srv.addView(srvRow);
        srv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, ServersActivity.class)); }
        });
        TextView srvHint = Ui.text(c, "Все серверы и пинг  ›", 13, Ui.ACCENT, false);
        srv.addView(srvHint, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        col.addView(srv, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        // routing
        LinearLayout rt = Ui.card(c);
        LinearLayout r1 = Ui.row(c);
        r1.addView(Ui.text(c, "Российские сайты", 15, Ui.FG, false), Ui.weight());
        rtRu = Ui.text(c, "напрямую", 14, Ui.MUTED, false);
        r1.addView(rtRu);
        rt.addView(r1);
        rt.addView(Ui.divider(c));
        LinearLayout r2 = Ui.row(c);
        r2.addView(Ui.text(c, "Всё остальное", 15, Ui.FG, false), Ui.weight());
        rtRest = Ui.text(c, "через VPN", 14, Ui.MUTED, false);
        r2.addView(rtRest);
        rt.addView(r2);
        col.addView(rt, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        return sv;
    }

    private void render() {
        if (title == null) return;
        int s = AppState.vpn;
        power.setState(s);

        String net = AppState.net;
        if (s == AppState.OFF) net = currentNetGuess();
        netChip.setText("wifi".equals(net) ? "Wi-Fi" : "cell".equals(net) ? "Мобильная сеть" : "none".equals(net) ? "Нет сети" : "Сеть");
        netChip.setTextColor("cell".equals(net) ? Ui.WARN : "none".equals(net) ? Ui.BAD : Ui.FG);

        title.setTextColor(s == AppState.WAITING ? Ui.WARN : Ui.FG);
        subtitle.setTypeface(s == AppState.ON ? Typeface.MONOSPACE : Typeface.DEFAULT);
        switch (s) {
            case AppState.CONNECTING:
                title.setText("Подключение…");
                subtitle.setText(AppState.phase);
                break;
            case AppState.ON:
                title.setText("Подключено");
                subtitle.setText(duration(System.currentTimeMillis() - AppState.since));
                break;
            case AppState.WAITING:
                title.setText(AppState.wl == AppState.WL_NONET ? "Ожидание сети" : "Нет ответа");
                subtitle.setText(AppState.phase);
                break;
            default:
                title.setText("Не подключено");
                subtitle.setText("Нажмите, чтобы включить");
        }
        errorText.setText(s == AppState.OFF ? AppState.error : "");
        errorText.setVisibility(s == AppState.OFF && !AppState.error.isEmpty() ? View.VISIBLE : View.GONE);

        // banner (7 s)
        boolean showBanner = !AppState.banner.isEmpty() && System.currentTimeMillis() - AppState.bannerAt < 7000 && s != AppState.OFF;
        banner.setVisibility(showBanner ? View.VISIBLE : View.GONE);
        if (showBanner) {
            banner.setText(AppState.banner);
            boolean ok = AppState.bannerTone == 1;
            banner.setBackground(Ui.round(this, ok ? Ui.ACCENT_TINT : Ui.WARN_TINT, 14, 0));
            banner.setTextColor(ok ? 0xFFCFEFDD : Ui.WARN_FG);
        }

        // white lists
        if (AppState.wlChecking || AppState.wl == AppState.WL_UNKNOWN) {
            wlBadge.setText(AppState.wlChecking ? "Проверка…" : "—");
            Ui.tone(this, wlBadge, Ui.TONE_NEUTRAL);
        } else if (AppState.wl == AppState.WL_ON) {
            wlBadge.setText("Включены");
            Ui.tone(this, wlBadge, Ui.TONE_WARN);
        } else if (AppState.wl == AppState.WL_OFF && AppState.regularBlocked && s != AppState.OFF) {
            wlBadge.setText("Блокировка");
            Ui.tone(this, wlBadge, Ui.TONE_WARN);
        } else if (AppState.wl == AppState.WL_OFF) {
            wlBadge.setText("Выключены");
            Ui.tone(this, wlBadge, Ui.TONE_OK);
        } else {
            wlBadge.setText("Нет сети");
            Ui.tone(this, wlBadge, Ui.TONE_BAD);
        }
        if (AppState.wl == AppState.WL_OFF && AppState.regularBlocked && s != AppState.OFF) {
            wlSub.setText("Сайты открываются, но обычные VPN-серверы заблокированы. Работаю через серверы для БС");
        } else {
            wlSub.setText(AppState.wlDetail.isEmpty() ? "Проверяется при подключении" : AppState.wlDetail);
        }

        // server
        if (s == AppState.ON && !AppState.serverName.isEmpty()) {
            srvName.setText(AppState.serverName);
            String ping = AppState.ping > 0 ? AppState.ping + " мс" : "пинг —";
            boolean manual = !AppState.pinned.isEmpty();
            srvSub.setText((manual ? "Выбран вручную" : "Лучший из " + AppState.alive) + " · " + ping + " · " + ("auto-lte".equals(AppState.group) ? "LTE" : "обычные"));
            srvBadge.setVisibility(View.VISIBLE);
            srvBadge.setText(AppState.switching ? "Проверка…" : manual ? "Вручную" : "Авто");
            Ui.tone(this, srvBadge, AppState.switching ? Ui.TONE_NEUTRAL : manual ? Ui.TONE_WARN : Ui.TONE_OK);
        } else if (s == AppState.CONNECTING) {
            srvName.setText("Выбираю сервер…");
            srvSub.setText(AppState.lastServers);
            srvBadge.setVisibility(View.GONE);
        } else if (s == AppState.WAITING) {
            srvName.setText("Сервер не выбран");
            srvSub.setText("Выберу, как только будет связь");
            srvBadge.setVisibility(View.GONE);
        } else {
            srvName.setText("Автовыбор сервера");
            srvSub.setText("Подключит к самому быстрому");
            srvBadge.setVisibility(View.GONE);
        }

        boolean on = s == AppState.ON;
        rtRu.setText(prefs.ruDirect() ? "напрямую" : "через VPN");
        rtRu.setTextColor(!prefs.ruDirect() && on ? Ui.ACCENT : Ui.MUTED);
        rtRest.setTextColor(on ? Ui.ACCENT : Ui.MUTED);
    }

    private String currentNetGuess() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return "none";
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return "none";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "cell";
            return "other";
        } catch (Exception e) {
            return "other";
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static String duration(long ms) {
        long t = Math.max(0, ms / 1000);
        return String.format("%02d:%02d:%02d", t / 3600, (t / 60) % 60, t % 60);
    }

    private void toggle() {
        if (AppState.vpn == AppState.OFF) {
            AppState.error = "";
            Intent i = VpnService.prepare(this);
            if (i != null) startActivityForResult(i, REQ_VPN);
            else startVpn();
        } else {
            startService(new Intent(this, BoxVpnService.class).setAction(BoxVpnService.ACTION_STOP));
        }
    }

    private void startVpn() {
        Intent i = new Intent(this, BoxVpnService.class).setAction(BoxVpnService.ACTION_START);
        startForegroundService(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) startVpn();
            else Toast.makeText(this, "Без разрешения VPN не включится", Toast.LENGTH_LONG).show();
        }
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications()
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.askedNotifications(true);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    /* ---------- onboarding ---------- */

    private View buildOnboarding() {
        Context c = this;
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 56), Ui.dp(c, 20), Ui.dp(c, 24));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(c, "Добавьте подписку", 28, Ui.FG, true));
        TextView lead = Ui.text(c, "Вставьте ссылку от VPN-провайдера. Подойдут ссылки на подписку и ключи vless, vmess, trojan, ss, hy2.", 15, Ui.MUTED, false);
        lead.setLineSpacing(0, 1.2f);
        col.addView(lead, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        col.addView(Ui.text(c, "Ссылка", 14, Ui.FG, true), Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 28));
        subInput = new EditText(c);
        subInput.setHint("https://… или vless://…");
        subInput.setHintTextColor(0xFF8A919C);
        subInput.setTextColor(Ui.FG);
        subInput.setTypeface(Typeface.MONOSPACE);
        subInput.setTextSize(14);
        subInput.setSingleLine(false);
        subInput.setMaxLines(4);
        subInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        subInput.setBackground(Ui.round(c, Ui.SURFACE, 14, Ui.LINE2));
        subInput.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
        col.addView(subInput, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        subError = Ui.text(c, "", 13, Ui.BAD, false);
        subError.setVisibility(View.GONE);
        col.addView(subError, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        TextView paste = Ui.button(c, "Вставить из буфера", false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData d = cb.getPrimaryClip();
                if (d != null && d.getItemCount() > 0 && d.getItemAt(0).coerceToText(MainActivity.this) != null) {
                    subInput.setText(d.getItemAt(0).coerceToText(MainActivity.this).toString().trim());
                } else {
                    Toast.makeText(MainActivity.this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
                }
            }
        });
        col.addView(paste, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        View spacer = new View(c);
        col.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout setup = Ui.card(c);
        setup.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16));
        setup.addView(Ui.text(c, "Настроится само", 14, Ui.MUTED, true));
        String[] items = {"Российские сайты — без VPN", "Самый быстрый сервер — автоматически", "Белые списки — переход на LTE"};
        for (String it : items) {
            TextView t = Ui.text(c, "✓  " + it, 15, Ui.FG, false);
            setup.addView(t, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        }
        col.addView(setup, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));

        TextView go = Ui.button(c, "Продолжить", true);
        go.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveSubscription(); }
        });
        col.addView(go, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));
        return sv;
    }

    static boolean looksLikeLink(String v) {
        return v.matches("(?is)^(https?|vless|vmess|trojan|ss|hy2|hysteria2)://\\S+.*");
    }

    private void saveSubscription() {
        String v = subInput.getText().toString().trim();
        if (!looksLikeLink(v)) {
            subError.setText(v.isEmpty() ? "Вставьте ссылку на подписку"
                    : "Это не похоже на ссылку. Нужна ссылка на подписку (https://…) или ключ vless://, vmess://, trojan://, ss://, hy2://");
            subError.setVisibility(View.VISIBLE);
            return;
        }
        subError.setVisibility(View.GONE);
        prefs.subUrl(v);
        showScreen();
    }
}
