package app.dash;

import java.util.ArrayList;
import java.util.List;

/** Main screen tiles: which exist, in which order, which are shown. Stored as "ping,wl,!speed,…" ("!" = hidden). */
final class Tiles {
    private Tiles() {}

    static final String PING = "ping", WL = "wl", SPEED = "speed", TRAFFIC = "traffic", IP = "ip", SERVER = "server";
    static final String[] ALL = {PING, WL, SPEED, TRAFFIC, IP, SERVER};

    static final class Item {
        final String id;
        boolean on;
        Item(String id, boolean on) { this.id = id; this.on = on; }
    }

    static String title(String id) {
        switch (id) {
            case PING: return "Пинг";
            case WL: return "Белые списки";
            case SPEED: return "Скорость";
            case TRAFFIC: return "Трафик";
            case IP: return "IP и страна";
            case SERVER: return "Сервер";
        }
        return id;
    }

    static String hint(String id) {
        switch (id) {
            case PING: return "Задержка до сервера, нажатие — перемерить";
            case WL: return "Включил ли оператор белые списки";
            case SPEED: return "Текущая скорость загрузки и отдачи";
            case TRAFFIC: return "Сколько скачано и отправлено за подключение";
            case IP: return "Внешний IP через VPN и флаг страны";
            case SERVER: return "Текущий сервер, нажатие — список серверов";
        }
        return "";
    }

    /** Half-width tiles go two per row, wide ones take a whole row. */
    static boolean wide(String id) {
        return IP.equals(id) || SERVER.equals(id);
    }

    static List<Item> load(Prefs p) {
        List<Item> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        String raw = p.homeTiles();
        if (raw != null) {
            for (String part : raw.split(",")) {
                part = part.trim();
                boolean on = !part.startsWith("!");
                String id = on ? part : part.substring(1);
                if (known(id) && !seen.contains(id)) {
                    out.add(new Item(id, on));
                    seen.add(id);
                }
            }
        }
        // tiles added in newer versions show up at their default place, switched on
        for (String id : ALL) {
            if (!seen.contains(id)) {
                int at = out.size();
                if (!SERVER.equals(id)) {
                    for (int i = 0; i < out.size(); i++) if (SERVER.equals(out.get(i).id)) { at = i; break; }
                }
                out.add(at, new Item(id, true));
            }
        }
        return out;
    }

    static void save(Prefs p, List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(',');
            if (!it.on) sb.append('!');
            sb.append(it.id);
        }
        p.homeTiles(sb.toString());
    }

    static String signature(Prefs p) {
        StringBuilder sb = new StringBuilder();
        for (Item it : load(p)) sb.append(it.on ? "" : "!").append(it.id).append(',');
        return sb.toString();
    }

    private static boolean known(String id) {
        for (String a : ALL) if (a.equals(id)) return true;
        return false;
    }
}
