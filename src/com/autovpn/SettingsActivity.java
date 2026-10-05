package com.autovpn;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends Activity {
    private Prefs prefs;
    private TextView modeWl, modeNet, modeHint, logView, appsCount;
    private EditText subInput;
    private LinearLayout subList;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Context c = this;
        ScrollView sv = new ScrollView(c);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), Ui.dp(c, 32));
        sv.addView(col);
        setContentView(sv);

        LinearLayout top = Ui.row(c);
        TextView back = Ui.text(c, "←", 24, Ui.FG, false);
        back.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        back.setContentDescription("Назад");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);
        top.addView(Ui.text(c, "Настройки", 22, Ui.FG, true));
        String vn = "?";
        try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView ver = Ui.text(c, "версия " + vn, 13, Ui.MUTED, false);
        ver.setGravity(android.view.Gravity.END);
        top.addView(ver, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(top);

        // Subscriptions
        col.addView(section(c, "Подписки"));
        LinearLayout sub = Ui.card(c);
        subList = new LinearLayout(c);
        subList.setOrientation(LinearLayout.VERTICAL);
        sub.addView(subList);
        renderSubs();

        subInput = new EditText(c);
        subInput.setTextColor(Ui.FG);
        subInput.setHintTextColor(0xFF8A919C);
        subInput.setHint("Ещё одна ссылка: https://… или vless://…");
        subInput.setTypeface(Typeface.MONOSPACE);
        subInput.setTextSize(13);
        subInput.setMaxLines(3);
        subInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        subInput.setBackground(Ui.round(c, Ui.SURFACE2, 12, 0));
        subInput.setPadding(Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12));
        sub.addView(subInput, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        LinearLayout addRow = Ui.row(c);
        TextView paste = Ui.button(c, "Вставить", false);
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                ClipData d = cb.getPrimaryClip();
                CharSequence t = d != null && d.getItemCount() > 0 ? d.getItemAt(0).coerceToText(SettingsActivity.this) : null;
                if (t != null) subInput.setText(t.toString().trim());
                else Toast.makeText(SettingsActivity.this, "Буфер обмена пуст", Toast.LENGTH_SHORT).show();
            }
        });
        addRow.addView(paste, Ui.weight());
        View gap = new View(c);
        addRow.addView(gap, new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        TextView add = Ui.button(c, "Добавить", false);
        add.setTextColor(Ui.ACCENT);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String val = subInput.getText().toString().trim();
                if (!MainActivity.looksLikeLink(val)) {
                    Toast.makeText(SettingsActivity.this, "Это не похоже на ссылку на подписку", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!prefs.addSub(val)) {
                    Toast.makeText(SettingsActivity.this, "Эта подписка уже добавлена", Toast.LENGTH_SHORT).show();
                    return;
                }
                subInput.setText("");
                subsChanged();
            }
        });
        addRow.addView(add, Ui.weight());
        sub.addView(addRow, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        long upd = prefs.subUpdated();
        String info = upd == 0 ? "Ещё не загружались" : "Загружены " + android.text.format.DateFormat.format("dd.MM HH:mm", upd);
        if (!AppState.lastServers.isEmpty()) info += "\n" + AppState.lastServers;
        info += "\nСерверы всех подписок объединяются, автовыбор берёт лучший из всех. Пока VPN включён, подписки обновляются сами раз в 12 часов";
        TextView subInfo = Ui.text(c, info, 13, Ui.MUTED, false);
        sub.addView(subInfo, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        TextView save = Ui.button(c, "Обновить и переподключить", false);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.resetSubUpdated();
                reconnect();
            }
        });
        sub.addView(save, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        col.addView(sub, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        // Auto selection
        col.addView(section(c, "Автовыбор"));
        LinearLayout auto = Ui.card(c);
        auto.addView(Ui.text(c, "Как выбирать серверы", 15, Ui.FG, true));
        LinearLayout seg = Ui.row(c);
        seg.setBackground(Ui.round(c, Ui.SURFACE2, 12, 0));
        seg.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
        modeWl = segButton(c, "По белым спискам");
        modeNet = segButton(c, "По типу сети");
        seg.addView(modeWl, Ui.weight());
        seg.addView(modeNet, Ui.weight());
        modeWl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(Prefs.MODE_WL); }
        });
        modeNet.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setMode(Prefs.MODE_NET); }
        });
        auto.addView(seg, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));
        modeHint = Ui.text(c, "", 13, Ui.MUTED, false);
        auto.addView(modeHint, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        auto.addView(Ui.divider(c));
        auto.addView(Ui.text(c, "Белые списки проверяются раз в 2 минуты и при смене сети: если Google и Cloudflare не открываются, а Госуслуги и ya.ru открываются — списки включены.", 13, Ui.MUTED, false));
        col.addView(auto, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        renderMode();

        // Routing
        col.addView(section(c, "Маршрутизация"));
        LinearLayout rt = Ui.card(c);
        rt.addView(switchRow(c, "Российские сайты без VPN", ".ru, .рф, .su и российские сервисы", prefs.ruDirect(), new Toggle() {
            @Override public void set(boolean v) { prefs.ruDirect(v); }
        }));
        rt.addView(Ui.divider(c));
        rt.addView(switchRow(c, "Заблокированные в РФ — через VPN", "Даже если сайт на .ru", prefs.blockedVpn(), new Toggle() {
            @Override public void set(boolean v) { prefs.blockedVpn(v); }
        }));
        rt.addView(Ui.divider(c));
        rt.addView(Ui.text(c, "Изменения применятся после переподключения.", 13, Ui.MUTED, false));
        col.addView(rt, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        // Apps
        col.addView(section(c, "Приложения"));
        LinearLayout apps = Ui.card(c);
        LinearLayout appsRow = Ui.row(c);
        LinearLayout appsText = new LinearLayout(c);
        appsText.setOrientation(LinearLayout.VERTICAL);
        appsText.addView(Ui.text(c, "Приложения без VPN", 15, Ui.FG, true));
        appsCount = Ui.text(c, "", 13, Ui.MUTED, false);
        appsText.addView(appsCount);
        appsRow.addView(appsText, Ui.weight());
        appsRow.addView(Ui.text(c, "›", 24, Ui.MUTED, false));
        appsRow.setMinimumHeight(Ui.dp(c, 48));
        appsRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, AppsActivity.class)); }
        });
        apps.addView(appsRow);
        col.addView(apps, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        // Servers
        col.addView(section(c, "Серверы из подписки"));
        LinearLayout srv = Ui.card(c);
        srv.addView(Ui.text(c, serversText(), 13, Ui.FG, false));
        col.addView(srv, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));

        // Log
        col.addView(section(c, "Журнал"));
        LinearLayout lg = Ui.card(c);
        logView = Ui.text(c, "", 11, Ui.MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        lg.addView(logView);
        TextView copy = Ui.button(c, "Скопировать журнал", false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("log", logText()));
                Toast.makeText(SettingsActivity.this, "Журнал скопирован", Toast.LENGTH_SHORT).show();
            }
        });
        lg.addView(copy, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));
        col.addView(lg, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        logView.setText(logText());
    }

    @Override protected void onResume() {
        super.onResume();
        int n = prefs.excludedApps().size();
        if (appsCount != null) appsCount.setText(n == 0 ? "Все приложения идут через VPN" : "Напрямую: " + n);
    }

    private String logText() {
        List<String> lines = AppState.logSnapshot();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 200);
        for (int i = lines.size() - 1; i >= from; i--) sb.append(lines.get(i)).append('\n');
        return sb.length() == 0 ? "Пока пусто" : sb.toString();
    }

    private String serversText() {
        List<Subs.Entry> entries = Subs.cached(prefs);
        if (entries.isEmpty()) return "Подписка ещё не загружалась. Подключитесь один раз.";
        List<String> warnings = new ArrayList<>();
        List<String> stubs = new ArrayList<>();
        List<Server> list = Subs.applyOff(Subs.merge(entries, warnings, stubs), prefs.offServers());
        for (String st : stubs) warnings.add("заглушка вместо серверов — " + st);
        StringBuilder sb = new StringBuilder();
        String[] titles = {"Обычные", "LTE (для белых списков)", "Не участвуют"};
        for (int g = 0; g < 3; g++) {
            int n = 0;
            StringBuilder part = new StringBuilder();
            for (Server s : list) {
                if (s.group != g) continue;
                n++;
                part.append("  ").append(s.name);
                if (entries.size() > 1) part.append("  [").append(s.sub).append(']');
                if (s.excludeReason != null) part.append(" — ").append(s.excludeReason);
                part.append('\n');
            }
            sb.append(titles[g]).append(": ").append(n).append('\n').append(part);
            if (g < 2) sb.append('\n');
        }
        for (String w : warnings) sb.append("\n⚠ ").append(w);
        return sb.toString().trim();
    }

    /** One row per subscription with a delete cross. */
    private void renderSubs() {
        Context c = this;
        subList.removeAllViews();
        List<String> urls = prefs.subUrls();
        for (int i = 0; i < urls.size(); i++) {
            final String url = urls.get(i);
            if (i > 0) subList.addView(Ui.divider(c));
            LinearLayout row = Ui.row(c);
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(c, Subs.label(prefs, url), 15, Ui.FG, true);
            name.setSingleLine(true);
            text.addView(name);
            TextView link = Ui.text(c, url, 12, Ui.MUTED, false);
            link.setTypeface(Typeface.MONOSPACE);
            link.setSingleLine(true);
            link.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            text.addView(link);
            row.addView(text, Ui.weight());
            TextView del = Ui.text(c, "✕", 18, Ui.MUTED, false);
            del.setPadding(Ui.dp(c, 14), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));
            del.setContentDescription("Удалить подписку");
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (prefs.subUrls().size() <= 1) {
                        Toast.makeText(SettingsActivity.this, "Должна остаться хотя бы одна подписка", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new android.app.AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("Удалить подписку?")
                            .setMessage(Subs.label(prefs, url))
                            .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                                @Override public void onClick(android.content.DialogInterface d, int w) {
                                    prefs.removeSub(url);
                                    subsChanged();
                                }
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                }
            });
            row.addView(del);
            subList.addView(row);
        }
    }

    private void subsChanged() {
        AppState.servers = new ArrayList<>();
        AppState.pings.clear();
        prefs.resetSubUpdated();
        renderSubs();
        reconnect();
    }

    private interface Toggle { void set(boolean v); }

    private View switchRow(Context c, String title, String sub, boolean value, final Toggle t) {
        LinearLayout row = Ui.row(c);
        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.text(c, title, 15, Ui.FG, true));
        text.addView(Ui.text(c, sub, 13, Ui.MUTED, false));
        row.addView(text, Ui.weight());
        Switch sw = new Switch(c);
        sw.setChecked(value);
        sw.setContentDescription(title);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) { t.set(v); }
        });
        row.addView(sw);
        return row;
    }

    private TextView segButton(Context c, String label) {
        TextView t = Ui.text(c, label, 13, Ui.MUTED, true);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinHeight(Ui.dp(c, 40));
        t.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
        return t;
    }

    private void setMode(String m) {
        if (m.equals(prefs.mode())) return;
        prefs.mode(m);
        renderMode();
        if (AppState.vpn != AppState.OFF) {
            Toast.makeText(this, "Применится при следующей проверке", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderMode() {
        boolean wl = Prefs.MODE_WL.equals(prefs.mode());
        modeWl.setBackground(wl ? Ui.round(this, Ui.SURFACE, 9, Ui.LINE2) : null);
        modeNet.setBackground(!wl ? Ui.round(this, Ui.SURFACE, 9, Ui.LINE2) : null);
        modeWl.setTextColor(wl ? Ui.FG : Ui.MUTED);
        modeNet.setTextColor(!wl ? Ui.FG : Ui.MUTED);
        modeHint.setText(wl
                ? "Белые списки включены — LTE, выключены — обычные. Тип сети не важен. Если группа не отвечает, берётся другая."
                : "Wi-Fi — обычные, мобильная сеть — LTE. Если группа не отвечает, берётся другая.");
    }

    private TextView section(Context c, String s) {
        TextView t = Ui.text(c, s.toUpperCase(), 12, Ui.MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(c, 4), Ui.dp(c, 24), 0, 0);
        return t;
    }

    private void reconnect() {
        if (AppState.vpn != AppState.OFF) {
            startService(new Intent(this, BoxVpnService.class).setAction(BoxVpnService.ACTION_STOP));
            final Context app = getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    app.startForegroundService(new Intent(app, BoxVpnService.class).setAction(BoxVpnService.ACTION_START));
                }
            }, 3000);
            Toast.makeText(this, "Переподключение…", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
