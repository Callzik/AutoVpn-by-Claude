#!/bin/bash
# Signs version.json (in the given repo checkout) for the Windows updater; key from keystore/update-sign.key.enc + DASH_KS_PASS.
set -euo pipefail
cd "${1:-.}"
[ -f keystore/update-sign.key.enc ] || { echo "no keystore/update-sign.key.enc: run the update-key workflow once"; exit 0; }
cd windows
DASH_SIGN_KEY=$(openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:DASH_KS_PASS -in ../keystore/update-sign.key.enc) \
  go run ./tools/signversion sign
