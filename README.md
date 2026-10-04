# AutoVPN

Android VPN client on the sing-box core. Paste a subscription link, press one button.

- automatic best-server selection (no server list)
- Russian sites direct, everything else and RU-blocked sites through VPN
- separate server groups: regular / white-list ("LTE"), chosen by detected operator white lists
- Quick Settings tile, home widget, per-app exclusions, subscription auto-update
- subscriptions: share links (vless, vmess, trojan, ss, hy2) and Xray/sing-box JSON

`helper/main.go` goes into `cmd/sbhelper` of sing-box 1.12 and is built for android/arm64; the binary is packaged as `lib/arm64-v8a/libsbhelper.so`. `build.sh` builds the APK without Gradle (aapt2, javac, dx, apksigner). Releases contain the signed APK.

## О проекте

Проект создан с помощью Claude (Anthropic), ИИ-ассистента: код, сборка и отладка выполнены в диалоге с ним.

## Скачать

Готовый APK (arm64): [`apk/AutoVPN-0.9.apk`](apk/AutoVPN-0.9.apk). Разреши установку из неизвестных источников и вставь ссылку подписки.
