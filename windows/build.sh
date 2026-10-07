#!/bin/bash
# Builds Dash.exe for Windows (amd64). Needs Go 1.24+ and akavel/rsrc.
# The cores are the official releases (versions.env): scripts/fetch-cores.sh windows puts
# sing-box.exe and xray.exe into ./bin. scripts/package-windows.sh builds and zips the release.
set -euo pipefail
cd "$(dirname "$0")"
[ -f bin/sing-box.exe ] && [ -f bin/xray.exe ] || { echo "put sing-box.exe and xray.exe into ./bin"; exit 1; }
[ -n "${DASH_NO_UPDATE_KEY:-}" ] || grep -q 'updatePubKey = "[A-Za-z0-9+/=]\{44\}"' updatekey.go || { echo "no update key: run go run ./tools/signversion keygen"; exit 1; }
go mod verify
rsrc -manifest dash.manifest -ico dash.ico -arch amd64 -o rsrc_windows_amd64.syso
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-H windowsgui -s -w" -o Dash.exe .
