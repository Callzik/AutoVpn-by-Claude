package com.autovpn;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pick apps that bypass the VPN (banks, Gosuslugi, games …). */
public class AppsActivity extends Activity {
    private static final class App {
        String pkg, label;
        Drawable icon;
    }

    private Prefs prefs;
    private Set<String> selected;
    private Set<String> initial;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private Adapter adapter;
    private TextView countText, loading;
    private String filter = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        selected = prefs.excludedApps();
        initial = prefs.excludedApps();
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
        top.addView(Ui.text(c, "Приложения без VPN", 22, Ui.FG, true));
        col.addView(top);

        TextView hint = Ui.text(c, "Отмеченные приложения работают напрямую, мимо VPN. Пригодится для банков, Госуслуг и игр.", 13, Ui.MUTED, false);
        col.addView(hint, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        EditText search = new EditText(c);
        search.setHint("Поиск");
        search.setSingleLine(true);
        search.setTextColor(Ui.FG);
        search.setHintTextColor(0xFF8A919C);
        search.setTextSize(15);
        search.setBackground(Ui.round(c, Ui.SURFACE2, 12, 0));
        search.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c2) {}
            @Override public void afterTextChanged(Editable s) {
                filter = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter();
            }
        });
        col.addView(search, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 12));

        countText = Ui.text(c, "", 13, Ui.MUTED, false);
        col.addView(countText, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        loading = Ui.text(c, "Загрузка списка приложений…", 14, Ui.MUTED, false);
        loading.setGravity(Gravity.CENTER);
        col.addView(loading, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 24));

        ListView list = new ListView(c);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        adapter = new Adapter();
        list.setAdapter(adapter);
        col.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        updateCount();
        load();
    }

    private void load() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                PackageManager pm = app.getPackageManager();
                Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(launcher, 0);
                Map<String, App> byPkg = new HashMap<>();
                for (ResolveInfo ri : ris) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(app.getPackageName()) || byPkg.containsKey(pkg)) continue;
                    App a = new App();
                    a.pkg = pkg;
                    a.label = String.valueOf(ri.loadLabel(pm));
                    try {
                        a.icon = ri.loadIcon(pm);
                    } catch (Exception ignored) {
                    }
                    byPkg.put(pkg, a);
                }
                final List<App> res = new ArrayList<>(byPkg.values());
                final Collator coll = Collator.getInstance(new Locale("ru"));
                Collections.sort(res, new Comparator<App>() {
                    @Override public int compare(App x, App y) {
                        boolean sx = selected.contains(x.pkg), sy = selected.contains(y.pkg);
                        if (sx != sy) return sx ? -1 : 1;
                        return coll.compare(x.label, y.label);
                    }
                });
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        all.clear();
                        all.addAll(res);
                        loading.setVisibility(View.GONE);
                        applyFilter();
                    }
                });
            }
        }, "apps").start();
    }

    private void applyFilter() {
        shown.clear();
        for (App a : all) {
            if (filter.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(filter)
                    || a.pkg.toLowerCase(Locale.ROOT).contains(filter)) shown.add(a);
        }
        adapter.notifyDataSetChanged();
    }

    private void updateCount() {
        countText.setText(selected.isEmpty() ? "Все приложения идут через VPN" : "Без VPN: " + selected.size());
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.excludedApps(selected);
        if (isFinishing() && !selected.equals(initial)) {
            if (AppState.vpn != AppState.OFF) {
                final Context app = getApplicationContext();
                VpnControl.stop(app);
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override public void run() {
                        try {
                            VpnControl.start(app);
                        } catch (Exception e) {
                            AppState.log("Не удалось переподключиться: " + e.getMessage());
                        }
                    }
                }, 3000);
                Toast.makeText(this, "Переподключение для применения", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Context c = parent.getContext();
            Row r;
            if (convert == null) {
                r = new Row(c);
                convert = r.root;
                convert.setTag(r);
            } else {
                r = (Row) convert.getTag();
            }
            final App a = shown.get(pos);
            r.icon.setImageDrawable(a.icon);
            r.label.setText(a.label);
            r.pkg.setText(a.pkg);
            r.check.setOnCheckedChangeListener(null);
            r.check.setChecked(selected.contains(a.pkg));
            final CheckBox cb = r.check;
            cb.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                    if (v) selected.add(a.pkg); else selected.remove(a.pkg);
                    updateCount();
                }
            });
            r.root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { cb.toggle(); }
            });
            return convert;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView label, pkg;
        final CheckBox check;

        Row(Context c) {
            root = Ui.row(c);
            root.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8));
            root.setMinimumHeight(Ui.dp(c, 56));
            icon = new ImageView(c);
            root.addView(icon, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 8), 0);
            label = Ui.text(c, "", 15, Ui.FG, true);
            label.setSingleLine(true);
            pkg = Ui.text(c, "", 12, Ui.MUTED, false);
            pkg.setSingleLine(true);
            text.addView(label);
            text.addView(pkg);
            root.addView(text, Ui.weight());
            check = new CheckBox(c);
            check.setFocusable(false);
            root.addView(check);
        }
    }
}
