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
