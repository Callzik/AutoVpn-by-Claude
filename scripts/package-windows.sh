#!/bin/bash
# Builds windows/Dash.exe and packs out/Dash-Windows-<version>.zip (Dash.exe + Прочитай.txt).
# Run scripts/fetch-cores.sh windows first; needs rsrc in PATH.
set -euo pipefail
cd "$(dirname "$0")/.."
. ./versions.env
V=$(sed -n 's/^const Version = "\(.*\)"/\1/p' windows/app.go)
[ -n "$V" ] || { echo "no Version in windows/app.go"; exit 1; }
windows/build.sh
D=out/pkg/Dash-Windows-$V
rm -rf out/pkg && mkdir -p "$D"
cp windows/Dash.exe "$D/"
sed -e "s/@VERSION@/$V/; s/@SING_BOX_VERSION@/$SING_BOX_VERSION/; s/@XRAY_VERSION@/$XRAY_VERSION/" \
  "windows/dist/Прочитай.txt" > "$D/Прочитай.txt"
rm -f "out/Dash-Windows-$V.zip"
(cd out/pkg && zip -q -r -9 -X "../Dash-Windows-$V.zip" "Dash-Windows-$V")
ls -la "out/Dash-Windows-$V.zip"
