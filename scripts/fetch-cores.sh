#!/bin/bash
# Fetches the pinned cores (see versions.env) and checks their sha256.
#   android: builds sbhelper from sing-box sources + helper/main.go, takes Xray from the official release
#            -> out/sbhelper-android-arm64, out/xray-android-arm64
#   windows: official sing-box.exe and xray.exe -> windows/bin/
# Needs Go (GO_VERSION), git, curl, unzip, sha256sum.
set -euo pipefail
cd "$(dirname "$0")/.."
. ./versions.env
ROOT=$PWD
OUT=$ROOT/out
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OUT"

fetch() { # url sha256 file
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c --quiet - || { echo "sha256 mismatch: $1"; exit 1; }
}

android() {
  echo "== sbhelper (sing-box $SING_BOX_VERSION)"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "v$SING_BOX_VERSION" \
    https://github.com/SagerNet/sing-box "$TMP/sing-box"
  [ "$(git -C "$TMP/sing-box" rev-parse HEAD)" = "$SING_BOX_COMMIT" ] || { echo "sing-box tag moved"; exit 1; }
  mkdir -p "$TMP/sing-box/cmd/sbhelper"
  cp helper/main.go "$TMP/sing-box/cmd/sbhelper/"
  # -checklinkname=0: libbox links into runtime internals (same flag as sing-box's own build_libbox)
  (cd "$TMP/sing-box" && CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath \
    -tags "$SING_BOX_TAGS" \
    -ldflags "-X github.com/sagernet/sing-box/constant.Version=$SING_BOX_VERSION -s -w -buildid= -checklinkname=0" \
    -o "$OUT/sbhelper-android-arm64" ./cmd/sbhelper)

  echo "== xray (Xray-core $XRAY_VERSION)"
  fetch "https://github.com/XTLS/Xray-core/releases/download/v$XRAY_VERSION/Xray-android-arm64-v8a.zip" \
    "$XRAY_ANDROID_SHA256" "$TMP/xray.zip"
  unzip -q -o "$TMP/xray.zip" xray -d "$TMP/xray"
  cp "$TMP/xray/xray" "$OUT/xray-android-arm64"
  sha256sum "$OUT"/sbhelper-android-arm64 "$OUT"/xray-android-arm64
}

windows() {
  mkdir -p windows/bin
  echo "== sing-box.exe $SING_BOX_VERSION"
  fetch "https://github.com/SagerNet/sing-box/releases/download/v$SING_BOX_VERSION/sing-box-$SING_BOX_VERSION-windows-amd64.zip" \
    "$SING_BOX_WINDOWS_SHA256" "$TMP/sb.zip"
  unzip -q -o -j "$TMP/sb.zip" "sing-box-$SING_BOX_VERSION-windows-amd64/sing-box.exe" -d windows/bin
  echo "== xray.exe $XRAY_VERSION"
  fetch "https://github.com/XTLS/Xray-core/releases/download/v$XRAY_VERSION/Xray-windows-64.zip" \
    "$XRAY_WINDOWS_SHA256" "$TMP/xray.zip"
  unzip -q -o "$TMP/xray.zip" xray.exe -d windows/bin
  sha256sum windows/bin/*.exe
}

case "${1:-}" in
  android) android ;;
  windows) windows ;;
  *) echo "usage: $0 android|windows"; exit 64 ;;
esac
