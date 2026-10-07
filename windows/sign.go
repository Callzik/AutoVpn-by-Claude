package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"strings"
)

// verifyManifest checks the ed25519 signature (base64) of the raw version.json bytes
// against updatePubKey, so a version.json changed by anyone without the release key is ignored.
func verifyManifest(body, sig []byte) error {
	pk, err := base64.StdEncoding.DecodeString(updatePubKey)
	if err != nil || len(pk) != ed25519.PublicKeySize {
		return errors.New("в сборке нет ключа обновлений")
	}
	s, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(sig)))
	if err != nil || len(s) != ed25519.SignatureSize {
		return errors.New("подпись повреждена")
	}
	if !ed25519.Verify(ed25519.PublicKey(pk), body, s) {
		return errors.New("подпись не сходится")
	}
	return nil
}
