package app.dash;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/** Main screen builder: show or hide tiles and change their order. */
public class HomeLayoutActivity extends Activity {
    private Prefs prefs;
    private List<Tiles.Item> items;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        items = Tiles.load(prefs);
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
        top.addView(Ui.text(c, "Главный экран", 22, Ui.FG, true));
        col.addView(top);

        TextView hint = Ui.text(c, "Переключатель показывает или прячет плашку, стрелки меняют порядок. "
                + "Узкие плашки встают по две в ряд.", 13, Ui.MUTED, false);
        hint.setLineSpacing(0, 1.15f);
        col.addView(hint, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 10));

        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 16));

        TextView reset = Ui.button(c, "Вернуть как было", false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                prefs.homeTiles(null);
                items = Tiles.load(prefs);
                renderList();
            }
        });
        col.addView(reset, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 20));
        renderList();
    }

    private void renderList() {
        Context c = this;
        list.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            final Tiles.Item it = items.get(i);
            LinearLayout row = Ui.row(c);
            row.setBackground(Ui.round(c, Ui.SURFACE, 16, Ui.LINE));
            row.setPadding(Ui.dp(c, 8), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 10));
            row.setAlpha(it.on ? 1f : 0.55f);

            LinearLayout arrows = new LinearLayout(c);
            arrows.setOrientation(LinearLayout.VERTICAL);
            arrows.addView(arrow(c, "▲", idx > 0, new Runnable() {
                @Override public void run() { move(idx, -1); }
            }));
            arrows.addView(arrow(c, "▼", idx < items.size() - 1, new Runnable() {
                @Override public void run() { move(idx, 1); }
            }));
            row.addView(arrows);

            LinearLayout text = new LinearLayout(c);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 8), 0);
            String size = Tiles.wide(it.id) ? "широкая" : "узкая";
            text.addView(Ui.text(c, Tiles.title(it.id) + "  ·  " + size, 15, Ui.FG, true));
            TextView h = Ui.text(c, Tiles.hint(it.id), 12, Ui.MUTED, false);
            text.addView(h, Ui.lp(c, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 2));
            row.addView(text, Ui.weight());

            Switch sw = new Switch(c);
            sw.setChecked(it.on);
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    it.on = on;
                    Tiles.save(prefs, items);
                    renderList();
                }
            });
            row.addView(sw);

            list.addView(row, Ui.lp(c, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, i == 0 ? 0 : 8));
        }
    }

    private TextView arrow(Context c, String s, boolean enabled, final Runnable r) {
        TextView t = Ui.text(c, s, 13, enabled ? Ui.FG : Ui.LINE2, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4));
        if (enabled) {
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { r.run(); }
            });
        }
        return t;
    }

    private void move(int i, int d) {
        int j = i + d;
        if (j < 0 || j >= items.size()) return;
        Collections.swap(items, i, j);
        Tiles.save(prefs, items);
        renderList();
    }
}
