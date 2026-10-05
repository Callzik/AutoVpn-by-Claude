package com.autovpn;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** All servers with their ping; tap one to use it, or pick "Авто". */
public class ServersActivity extends Activity {
    private Prefs prefs;
    private final List<Server> items = new ArrayList<>();
    private Adapter adapter;
    private TextView test, hint;
    private boolean manySubs;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        Context c = this;

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(Ui.dp(c, 20), Ui.dp(c, 16), Ui.dp(c, 20), 0);
        setContentView(col);

        LinearLayout top = Ui.row(c);
        TextView back = Ui.text(c, "←", 24, Ui.FG, false);
        back.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 4));
        back.setContentDescription("Назад");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        top.addView(back);
        top.addView(Ui.text(c, "Серверы", 22, Ui.FG, true), Ui.weight());
        test = Ui.button(c, "Проверить", true);
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                BoxVpnService s = BoxVpnService.instance;
                if (s == null || AppState.vpn != AppState.ON) {
                    Toast.makeText(ServersActivity.this, "Сначала подключитесь", Toast.LENGTH_SHORT).show();
                    return;
                }
                s.pingAll();
            }
        });
        top.addView(test);
        col.addView(top);

        hint = Ui.text(c, "", 13, Ui.MUTED, false);
        col.addView(hint, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        ListView list = new ListView(c);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new Adapter();
        list.setAdapter(adapter);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppState.addListener(refresh);
        update();
        BoxVpnService s = BoxVpnService.instance;
        if (s != null && AppState.vpn == AppState.ON && AppState.pings.isEmpty()) s.pingAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppState.removeListener(refresh);
    }

    private int ping(Server s) {
        Integer p = AppState.pings.get(s.tag);
        return p == null ? 0 : p; // 0 unknown, -1 no answer
    }

    private void update() {
        List<Server> src = AppState.servers;
        if (src == null || src.isEmpty()) {
            try {
                src = Subs.merge(Subs.cached(prefs), new ArrayList<String>(), new ArrayList<String>());
            } catch (Exception e) {
                src = new ArrayList<>();
            }
        }
        items.clear();
        java.util.Set<String> subs = new java.util.HashSet<>();
        for (Server s : src) {
            if (s.group != Server.EXCLUDED) items.add(s);
            subs.add(s.sub);
        }
        manySubs = subs.size() > 1;
        Collections.sort(items, new Comparator<Server>() {
            @Override public int compare(Server a, Server b) {
                if (a.group != b.group) return a.group - b.group;
                int pa = ping(a), pb = ping(b);
                int ka = pa > 0 ? pa : pa == 0 ? 100000 : 200000;
                int kb = pb > 0 ? pb : pb == 0 ? 100000 : 200000;
                return ka - kb;
            }
        });
        boolean on = AppState.vpn == AppState.ON;
        test.setText(AppState.pinging ? "Проверяю…" : "Проверить");
        test.setAlpha(on && !AppState.pinging ? 1f : 0.5f);
        hint.setText(on ? "Нажмите на сервер, чтобы использовать его. «Авто» выбирает лучший сам."
                : "Подключитесь, чтобы увидеть пинг. Выбор применится при подключении.");
        adapter.notifyDataSetChanged();
    }

    private void choose(String name) {
        prefs.pinned(name);
        AppState.pinned = name;
        BoxVpnService s = BoxVpnService.instance;
        if (s != null && AppState.vpn != AppState.OFF) s.pin(name);
        else Toast.makeText(this, name.isEmpty() ? "Авто" : "Применится при подключении", Toast.LENGTH_SHORT).show();
        update();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size() + 1; }
        @Override public Object getItem(int i) { return i; }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Context c = parent.getContext();
            LinearLayout root = Ui.row(c);
            root.setPadding(0, Ui.dp(c, 10), 0, Ui.dp(c, 10));
            root.setMinimumHeight(Ui.dp(c, 56));
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(c, "", 15, Ui.FG, true);
            name.setSingleLine(true);
            TextView sub = Ui.text(c, "", 12, Ui.MUTED, false);
            text.addView(name);
            text.addView(sub);
            root.addView(text, Ui.weight());
            TextView right = Ui.text(c, "", 14, Ui.MUTED, true);
            root.addView(right);

            String pinned = prefs.pinned();
            if (pos == 0) {
                name.setText("Авто");
                sub.setText("Лучший сервер выбирается сам");
                right.setText(pinned.isEmpty() ? "✓" : "");
                right.setTextColor(Ui.ACCENT);
                root.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { choose(""); }
                });
                return root;
            }
            final Server s = items.get(pos - 1);
            name.setText(s.name);
            sub.setText((s.group == Server.LTE ? "LTE / белые списки" : "обычный") + (manySubs && !s.sub.isEmpty() ? " · " + s.sub : ""));
            sub.setSingleLine(true);
            int p = ping(s);
            if (AppState.pinned.equals(s.name) || pinned.equals(s.name)) {
                right.setText((p > 0 ? p + " мс  " : "") + "✓");
                right.setTextColor(Ui.ACCENT);
            } else if (p > 0) {
                right.setText(p + " мс");
                right.setTextColor(p < 150 ? Ui.ACCENT : p < 400 ? Ui.WARN : Ui.BAD);
            } else if (p < 0) {
                right.setText("—");
                right.setTextColor(Ui.BAD);
            }
            root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { choose(s.name); }
            });
            return root;
        }
    }
}
