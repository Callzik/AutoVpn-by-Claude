# Dash

Android VPN client on the sing-box core. Paste a subscription link, press one button.

- automatic best-server selection (no server list)
- Russian sites direct, everything else and RU-blocked sites through VPN
- separate server groups: regular / white-list ("LTE"), chosen by detected operator white lists
- Quick Settings tile, home widget, per-app exclusions, subscription auto-update
- subscriptions: share links (vless, vmess, trojan, ss, hy2) and Xray/sing-box JSON

`helper/main.go` goes into `cmd/sbhelper` of sing-box 1.12 and is built for android/arm64; the binary is packaged as `lib/arm64-v8a/libsbhelper.so`. `build.sh` builds the APK without Gradle (aapt2, javac, dx, apksigner). Releases contain the signed APK.

Windows auto-update accepts `version.json` only with a valid ed25519 signature in `version.json.sig`; the public key is built into Dash.exe (`windows/updatekey.go`). The private key is created once by the `update-key` workflow inside CI and kept only encrypted in `keystore/update-sign.key.enc` (AES-256, password = the `DASH_KS_PASS` secret). Both release workflows re-sign `version.json` automatically (`scripts/sign-version.sh`).

## Build

Versions of the cores and the toolchain are pinned in [`versions.env`](versions.env); [`.github/workflows/build.yml`](.github/workflows/build.yml) runs the tests and builds the APK and the Windows zip on every push (artifacts of the run).

- `scripts/fetch-cores.sh android` builds sbhelper and fetches Xray into `out/`, then `TOOLS=<dir with aapt2, dx.jar, apksigner.jar> ANDROID_JAR=… CORES_DIR=out ./build.sh` (signing key: `DASH_KS`, `DASH_KS_PASS`).
- `scripts/fetch-cores.sh windows && scripts/package-windows.sh` gives `out/Dash-Windows-<version>.zip`.
- Tests: `scripts/test-android.sh` and `cd windows && go test ./...` both run [`testdata/sub-cases.json`](testdata/sub-cases.json), so the two subscription parsers stay in sync.
- CI signs the APK with the release key from the `DASH_KEYSTORE_B64` / `DASH_KS_PASS` / `DASH_KS_ALIAS` secrets; without them it uses a throwaway key (`-test-key` APK, won't install over a release).

## О проекте

Проект создан с помощью Claude (Anthropic), ИИ-ассистента: код, сборка и отладка выполнены в диалоге с ним.

## Скачать

Готовый APK (arm64) — в [Releases](https://github.com/Callzik/DashVPN-by-Claude/releases) (тег `android-v…`), там же SHA-256. Разреши установку из неизвестных источников и вставь ссылку подписки.

Серверы с транспортом xhttp идут через встроенное ядро [Xray-core](https://github.com/XTLS/Xray-core) (официальный релиз v26.9.30, лицензия MPL-2.0, файл `lib/arm64-v8a/libxray.so`), остальные — через sing-box.


## Windows

Готовая сборка — в [Releases](https://github.com/Callzik/DashVPN-by-Claude/releases) (тег `windows-v…`): распакуйте и запустите `Dash.exe` (нужны права администратора для VPN-адаптера, Windows 10/11 x64).
То же, что на телефоне: одна кнопка, автовыбор сервера, несколько подписок, список серверов с пингом, ручной выбор и отключение серверов, xhttp через Xray, российские сайты напрямую. Весь трафик ПК идёт через TUN-адаптер.
Исходники — в папке [`windows/`](windows/) (Go + WebView2, ядра sing-box и Xray-core — официальные сборки).

## Выпуск релиза

Бинарники в git не коммитятся. Сначала код и номер версии (`build.sh`, `windows/main*.go`) мержатся в main, затем в отдельную ветку от main пушатся `dist/Dash-<версия>.apk` и/или `dist/Dash-Windows-<версия>.zip` (`git add -f`, папка в `.gitignore`) и `version.json` с новыми `version`/`code`. Workflow [`release.yml`](.github/workflows/release.yml) создаёт релиз `android-v<версия>` / `windows-v<версия>`, прописывает в `version.json` на main ссылку и `sha256` и удаляет ветку. Приложения сверяют SHA-256 скачанного файла перед установкой.

### Android одной кнопкой

Ключ подписи APK лежит в `keystore/dash-release.p12` (зашифрован паролем, пароль только в секрете репозитория `DASH_KS_PASS`). Чтобы выпустить Android: поднять версию в `build.sh` и `AndroidManifest.xml`, смержить в main, затем Actions → `release-android` → Run workflow. Workflow соберёт подписанный APK, создаст релиз `android-v<версия>` и пропишет `url` и `sha256` в `version.json`. Ключ нельзя менять: приложение обновится поверх старого только при той же подписи.
