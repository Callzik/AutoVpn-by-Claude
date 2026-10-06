#!/bin/bash
# Builds Dash.exe for Windows (amd64). Needs Go 1.24+ and akavel/rsrc.
# The cores are the official releases: put sing-box.exe (SagerNet/sing-box v1.12.25 windows-amd64)
# and xray.exe (XTLS/Xray-core v26.9.30 Xray-windows-64) into ./bin before building.
set -euo pipefail
cd "$(dirname "$0")"
[ -f bin/sing-box.exe ] && [ -f bin/xray.exe ] || { echo "put sing-box.exe and xray.exe into ./bin"; exit 1; }
go mod tidy
rsrc -manifest dash.manifest -ico dash.ico -arch amd64 -o rsrc_windows_amd64.syso
GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-H windowsgui -s -w" -o Dash.exe .
