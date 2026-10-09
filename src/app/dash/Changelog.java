package app.dash;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What's-new entries shown once after an update. Add a line here whenever a version ships
 * something worth telling the user about; MainActivity shows every entry newer than the
 * version they last saw, then remembers the current one.
 */
public final class Changelog {
    private Changelog() {}

    // versionCode (AndroidManifest) -> short bullet points, impersonal wording.
    private static final Map<Integer, String[]> ENTRIES = new LinkedHashMap<>();
    static {
        ENTRIES.put(30, new String[]{
                "После обновления показывается список изменений",
                "В настройках появилась кнопка «Проверить обновление»",
        });
        ENTRIES.put(31, new String[]{
                "Пинг до 300 мс теперь считается хорошим и подсвечивается зелёным",
        });
        ENTRIES.put(32, new String[]{
                "Конструктор главного экрана: плашки можно прятать и переставлять (Настройки → Главный экран или долгое нажатие на плашку)",
                "Новые плашки: скорость, трафик за подключение, внешний IP с флагом страны",
        });
        ENTRIES.put(33, new String[]{
                "Исправлены уязвимости: другие приложения больше не могут запускать экраны через Dash и выключать VPN через виджет",
        });
        ENTRIES.put(34, new String[]{
                "VPN для точки доступа: устройства, подключённые к телефону, могут ходить через VPN по прокси (Настройки → Раздача)",
        });
    }

    /** Entries for every version newer than {@code fromCode} up to and including {@code toCode}, oldest first. */
    static java.util.List<String> since(int fromCode, int toCode) {
        java.util.List<Integer> codes = new java.util.ArrayList<>(ENTRIES.keySet());
        java.util.Collections.sort(codes);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int code : codes) {
            if (code > fromCode && code <= toCode) {
                for (String line : ENTRIES.get(code)) out.add(line);
            }
        }
        return out;
    }
}
