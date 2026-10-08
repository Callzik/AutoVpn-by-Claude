package app.dash;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private Prefs prefs;
    private FrameLayout root;
    private View mainView, onboardingView;

    private TextView netChip, title, subtitle, banner, wlBadge, srvName, srvSub, srvBadge, pingBtn, errorText;
    private PowerButton power;
    private LinearLayout updCard;
    private TextView updText, updBtn;
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
        root.addView(new StarField(this));
        mainView = buildMain();
        onboardingView = buildOnboarding();
        root.addView(mainView);
        root.addView(onboardingView);
        showScreen();
        askNotifications();
        showChangelog();
        VpnControl.init(this);
        handleIntent(getIntent());
    }

    /** "Что нового" after an update — skipped on the very first install. */
    private void showChangelog() {
        int code;
        boolean fresh;
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            code = pi.versionCode;
            fresh = pi.firstInstallTime == pi.lastUpdateTime;
        } catch (Exception e) {
            return;
        }
        int seen = prefs.lastSeenVersion();
        if (seen == 0) {
            if (fresh) { // first install: nothing to tell
                prefs.lastSeenVersion(code);
                return;
            }
            seen = code - 1; // updated from a build that did not remember versions yet
        }
        if (seen >= code) return;
        java.util.List<String> lines = Changelog.since(seen, code);
        prefs.lastSeenVersion(code);
        if (lines.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append("•  ").append(l).append("\n\n");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Что нового")
                .setMessage(sb.toString().trim())
                .setPositiveButton("Понятно", null)
                .show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Tile / widget asked to connect but needed the screen (permission or subscription). */
    private void handleIntent(Intent intent) {
        if (intent != null && Updater.ACTION_STATUS.equals(intent.getAction())) {
            Updater.onStatus(this, intent);
            intent.setAction(null);
            return;
        }
        if (intent == null || !intent.getBooleanExtra(VpnControl.EXTRA_CONNECT, false)) return;
        intent.removeExtra(VpnControl.EXTRA_CONNECT);
        if (AppState.vpn == AppState.OFF && !prefs.subUrl().trim().isEmpty()) toggle();
    }

    @Override protected void onResume() {
        super.onResume();
        AppState.addListener(listener);
        handler.post(ticker);
        showScreen();
        Updater.check(this, false);
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
        col.setPadding(Ui.dp(c, 22), Ui.dp(c, 22), Ui.dp(c, 22), Ui.dp(c, 22));
        sv.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // header: comet logo + wordmark + settings
        LinearLayout top = Ui.row(c);
        ImageView logo = new ImageView(c);
        logo.setImageResource(R.drawable.ic_logo);
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30)));
        TextView word = Ui.text(c, "dash", 24, Ui.FG, true);
        word.setLetterSpacing(-0.02f);
        word.setPadding(Ui.dp(c, 10), 0, Ui.dp(c, 8), 0);
        top.addView(word);
        netChip = Ui.text(c, "v" + versionName(), 12, Ui.MUTED, false);
        top.addView(netChip, Ui.weight());
        FrameLayout settings = new FrameLayout(c);
        settings.setBackground(Ui.round(c, Ui.SURFACE, 14, Ui.LINE));
        settings.addView(new SlidersIcon(c), new FrameLayout.LayoutParams(Ui.dp(c, 20), Ui.dp(c, 20), Gravity.CENTER));
        settings.setContentDescription("Настройки");
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        top.addView(settings, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        col.addView(top);

        // hero: orbit button + status + timer
        LinearLayout hero = new LinearLayout(c);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        power = new PowerButton(c);
        power.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        hero.addView(power, new LinearLayout.LayoutParams(Ui.dp(c, 270), Ui.dp(c, 270)));
        title = Ui.text(c, "Отключено", 32, Ui.FG, true);
        title.setLetterSpacing(-0.02f);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 14));
        subtitle = Ui.text(c, "", 15, Ui.MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        hero.addView(subtitle, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        errorText = Ui.text(c, "", 14, Ui.BAD, false);
        errorText.setGravity(Gravity.CENTER);
        hero.addView(errorText, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        hero.setMinimumHeight(Ui.dp(c, 420));
        hero.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 12));
        col.addView(hero, heroLp);

        banner = Ui.text(c, "", 13, Ui.WARN_FG, false);
        banner.setPadding(Ui.dp(c, 14), Ui.dp(c, 10), Ui.dp(c, 14), Ui.dp(c, 10));
        banner.setVisibility(View.GONE);
        col.addView(banner, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0));

        // update available
        updCard = Ui.row(c);
        updCard.setBackground(Ui.round(c, Ui.ACCENT_TINT, 16, 0));
        updCard.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 10), Ui.dp(c, 10));
        updText = Ui.text(c, "", 14, 0xFFD9D3FF, false);
        updCard.addView(updText, Ui.weight());
        updBtn = Ui.text(c, "Обновить", 14, Ui.ACCENT_INK, true);
        updBtn.setBackground(Ui.round(c, Ui.ACCENT, 12, 0));
        updBtn.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 8));
        updBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Updater.start(MainActivity.this); }
        });
        updCard.addView(updBtn);
        updCard.setVisibility(View.GONE);
        col.addView(updCard, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        // tiles: ping (tap = re-ping), white lists
        LinearLayout tiles = new LinearLayout(c);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        pingBtn = tile(tiles, "Пинг", true);
        ((View) pingBtn.getParent()).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BoxVpnService s = BoxVpnService.instance;
                if (s != null && AppState.vpn == AppState.ON) s.pingCurrent();
            }
        });
        wlBadge = tile(tiles, "Белые списки", false);
        col.addView(tiles, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        // server card
        LinearLayout srv = Ui.row(c);
        srv.setBackground(Ui.round(c, Ui.SURFACE, 18, Ui.LINE));
        srv.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16));
        LinearLayout srvText = new LinearLayout(c);
        srvText.setOrientation(LinearLayout.VERTICAL);
        srvName = Ui.text(c, "Автовыбор сервера", 16, Ui.FG, true);
        srvName.setSingleLine(true);
        srvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        srvSub = Ui.text(c, "", 13, Ui.MUTED, false);
        srvSub.setSingleLine(true);
        srvSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        srvText.addView(srvName);
        srvText.addView(srvSub, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        srv.addView(srvText, Ui.weight());
        srv.addView(Ui.text(c, "›", 24, Ui.MUTED, false));
        srv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, ServersActivity.class)); }
        });
        col.addView(srv, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        return sv;
    }

    /** One stat tile; returns its value TextView. */
    private TextView tile(LinearLayout parent, String label, boolean mono) {
        Context c = this;
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setBackground(Ui.round(c, Ui.SURFACE, 16, Ui.LINE));
        t.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12));
        TextView l = Ui.text(c, label, 12, Ui.MUTED, false);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(l);
        TextView v = Ui.text(c, "—", mono ? 20 : 18, Ui.FG, true);
        if (mono) v.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        v.setSingleLine(true);
        t.addView(v, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (parent.getChildCount() > 0) lp.leftMargin = Ui.dp(c, 10);
        parent.addView(t, lp);
        return v;
    }

    /** Settings glyph: two slider lines. */
    static class SlidersIcon extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        SlidersIcon(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(Ui.dp(c, 2));
            p.setColor(Ui.FG);
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float u = getWidth() / 24f;
            cv.drawLine(4 * u, 7 * u, 14 * u, 7 * u, p);
            cv.drawLine(18 * u, 7 * u, 20 * u, 7 * u, p);
            cv.drawLine(4 * u, 17 * u, 8 * u, 17 * u, p);
            cv.drawLine(12 * u, 17 * u, 20 * u, 17 * u, p);
            cv.drawCircle(16 * u, 7 * u, 2 * u, p);
            cv.drawCircle(10 * u, 17 * u, 2 * u, p);
        }
    }

    private void render() {
        if (title == null) return;
        int s = AppState.vpn;
        power.setState(s);

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
                title.setText("Отключено");
                String net = currentNetGuess();
                subtitle.setText("none".equals(net) ? "Нет сети" : "Нажмите, чтобы включить");
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
            banner.setTextColor(ok ? 0xFFD9D3FF : Ui.WARN_FG);
        }

        // update card
        boolean upd = !Updater.newVersion.isEmpty();
        updCard.setVisibility(upd ? View.VISIBLE : View.GONE);
        if (upd) {
            int pr = Updater.progress;
            updText.setText(pr == 101 ? "Установка " + Updater.newVersion + "…"
                    : pr >= 0 ? "Загрузка " + Updater.newVersion + " · " + pr + "%"
                    : !Updater.error.isEmpty() ? Updater.error : "Доступна версия " + Updater.newVersion);
            updBtn.setVisibility(pr >= 0 ? View.GONE : View.VISIBLE);
            updBtn.setText(Updater.error.isEmpty() ? "Обновить" : "Ещё раз");
        }

        // tile: ping
        boolean on = s == AppState.ON;
        pingBtn.setText(!on ? "—" : AppState.pingingCurrent ? "…" : AppState.ping > 0 ? String.valueOf(AppState.ping) : "—");
        pingBtn.setTextColor(!on || AppState.pingingCurrent || AppState.ping <= 0 ? Ui.FG
                : AppState.ping < 300 ? Ui.ACCENT : AppState.ping < 400 ? Ui.WARN : Ui.BAD);

        // tile: white lists
        int wlColor = Ui.FG;
        if (AppState.wlChecking) wlBadge.setText("…");
        else if (AppState.wl == AppState.WL_ON) { wlBadge.setText("вкл"); wlColor = Ui.WARN; }
        else if (AppState.wl == AppState.WL_OFF && AppState.regularBlocked && s != AppState.OFF) { wlBadge.setText("блок"); wlColor = Ui.WARN; }
        else if (AppState.wl == AppState.WL_OFF) wlBadge.setText("выкл");
        else if (AppState.wl == AppState.WL_NONET) { wlBadge.setText("нет сети"); wlColor = Ui.BAD; }
        else wlBadge.setText("—");
        wlBadge.setTextColor(wlColor);

        // server card
        boolean manual = !AppState.pinned.isEmpty();
        String grp = "auto-lte".equals(AppState.group) ? "LTE" : "основные";
        if (on && !AppState.serverName.isEmpty()) {
            srvName.setText(AppState.serverName);
            srvSub.setText(AppState.switching ? "Проверка серверов…"
                    : manual ? "Выбран вручную" : "Лучший из " + AppState.alive + " · " + grp);
        } else if (s == AppState.CONNECTING) {
            srvName.setText("Выбор сервера…");
            srvSub.setText(AppState.lastServers);
        } else if (s == AppState.WAITING) {
            srvName.setText("Сервер не выбран");
            srvSub.setText("Выбор после появления связи");
        } else {
            srvName.setText(manual ? AppState.pinned : "Автовыбор сервера");
            srvSub.setText(manual ? "Выбран вручную" : "Самый быстрый из доступных");
        }
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
        subInput.setHintTextColor(0xFF8E8AA3);
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
        prefs.addSub(v);
        showScreen();
    }
}
