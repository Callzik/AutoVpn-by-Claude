#!/bin/bash
# Builds the Dash APK without Gradle: aapt2 + javac + dx + apksigner.
set -euo pipefail
export JAVA_TOOL_OPTIONS=
cd "$(dirname "$0")"
T=/home/claude/vpn/tools
ANDROID_JAR=$T/platforms/android-34/android.jar
B=build
rm -rf $B && mkdir -p $B/gen $B/classes $B/apk

echo "== resources"
$T/aapt2 compile --dir res -o $B/res.zip
$T/aapt2 link -o $B/base.apk -I $ANDROID_JAR --manifest AndroidManifest.xml \
  --java $B/gen -A assets --min-sdk-version 26 --target-sdk-version 33 \
  --version-code 26 --version-name 1.9.6 $B/res.zip

echo "== java"
javac -nowarn -encoding UTF-8 --release 8 -Xlint:-options -classpath $ANDROID_JAR -d $B/classes \
  $(find src $B/gen -name '*.java')

echo "== dex"
java -jar $T/dx.jar --dex --min-sdk-version=26 --output=$B/classes.dex $B/classes

echo "== package"
python3 - <<'EOF'
import zipfile
src = zipfile.ZipFile("build/base.apk")
out = zipfile.ZipFile("build/unsigned.apk", "w")
for info in src.infolist():
    data = src.read(info.filename)
    zi = zipfile.ZipInfo(info.filename, date_time=(2026, 1, 1, 0, 0, 0))
    zi.compress_type = info.compress_type
    zi.external_attr = info.external_attr
    if zi.compress_type == zipfile.ZIP_STORED:
        # zipalign: data of stored entries must start on a 4-byte boundary
        pos = out.fp.tell() + 30 + len(zi.filename.encode())
        pad = (-pos) % 4
        zi.extra = b"\x00" * pad
    out.writestr(zi, data)
def add(name, path, ctype=zipfile.ZIP_DEFLATED):
    zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
    zi.compress_type = ctype
    zi.external_attr = 0o100755 << 16 if name.endswith(".so") else 0o100644 << 16
    with open(path, "rb") as f:
        out.writestr(zi, f.read(), compresslevel=9)
add("classes.dex", "build/classes.dex")
add("lib/arm64-v8a/libsbhelper.so", "/home/claude/vpn/out/sbhelper-android-arm64")
add("lib/arm64-v8a/libxray.so", "/home/claude/vpn/out/xray-android-arm64")
out.close()
EOF

echo "== sign"
# signing key: path, alias and password come from the environment (or a local keystore.env), never from the repo
[ -f ../keystore.env ] && { set -a; . ../keystore.env; set +a; }
: "${DASH_KS:?set DASH_KS (keystore path)}" "${DASH_KS_PASS:?set DASH_KS_PASS}"
DASH_KS_ALIAS=${DASH_KS_ALIAS:-dash}
java -jar $T/apksigner.jar sign --ks "$DASH_KS" --ks-pass env:DASH_KS_PASS --ks-key-alias "$DASH_KS_ALIAS" \
  --min-sdk-version 26 --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out $B/Dash.apk $B/unsigned.apk
java -jar $T/apksigner.jar verify -v $B/Dash.apk | head -6
ls -la $B/Dash.apk
