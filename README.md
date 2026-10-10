# Dash

VPN-клиент для Android и Windows на ядрах sing-box и Xray. Вставил ссылку подписки — нажал одну кнопку.

## Возможности

- **Без списка серверов.** Dash сам выбирает самый быстрый.
- **Раздельная маршрутизация.** Российские сайты идут напрямую, остальные и заблокированные в РФ — через VPN.
- **Белые списки.** Две группы серверов: обычные и «LTE». Если оператор включил белые списки, Dash это определит и переключится сам.
- **Подписки.** Ссылки `vless`, `vmess`, `trojan`, `ss`, `hy2` и JSON-конфиги Xray / sing-box. Подписка обновляется автоматически.
- **Android.** Плитка в шторке, виджет на рабочий стол, исключения для приложений.
- **Windows.** Автообновление принимает только версии с подписью ed25519.

## Установка

- **Android** — подписанный APK из [Releases](../../releases).
- **Windows** — архив `Dash-Windows-<версия>.zip` из [Releases](../../releases).

## Сборка

Версии ядер и инструментов зафиксированы в [`versions.env`](versions.env). [`build.yml`](.github/workflows/build.yml) на каждый push прогоняет тесты и собирает APK и архив для Windows (лежат в артефактах запуска).

**Android**

```sh
scripts/fetch-cores.sh android
TOOLS=<папка с aapt2, dx.jar, apksigner.jar> ANDROID_JAR=… CORES_DIR=out ./build.sh
```

`fetch-cores.sh` собирает sbhelper и скачивает Xray в `out/`. `build.sh` собирает APK без Gradle (aapt2, javac, dx, apksigner). Ключ подписи — переменные `DASH_KS`, `DASH_KS_PASS`.

`helper/main.go` кладётся в `cmd/sbhelper` sing-box 1.12 и собирается под android/arm64; бинарник упаковывается как `lib/arm64-v8a/libsbhelper.so`.

**Windows**

```sh
scripts/fetch-cores.sh windows && scripts/package-windows.sh
```

Результат — `out/Dash-Windows-<версия>.zip`.

**Тесты**

```sh
scripts/test-android.sh
cd windows && go test ./...
```

Оба прогоняют [`testdata/sub-cases.json`](testdata/sub-cases.json), чтобы парсеры подписок на Android и Windows не расходились.

---

Создано с помощью Claude (Anthropic).
