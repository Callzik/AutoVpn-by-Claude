// signversion signs version.json for the Windows updater.
//
//	go run ./tools/signversion keygen   new key: private seed to ../../dash-sign.key (outside the repo), public key to updatekey.go
//	go run ./tools/signversion sign     writes ../version.json.sig
//
// Run from windows/. The private key is read from $DASH_SIGN_KEY (base64 seed) or from the key file;
// it must never be committed.
package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
)

const (
	keyFile  = "../../dash-sign.key"
	pubFile  = "updatekey.go"
	manifest = "../version.json"
)

func main() {
	if len(os.Args) != 2 {
		fail(errors.New("usage: signversion keygen|sign"))
	}
	if _, err := os.Stat(pubFile); err != nil {
		fail(errors.New("run from the windows/ directory"))
	}
	var err error
	switch os.Args[1] {
	case "keygen":
		err = keygen()
	case "sign":
		err = sign()
	default:
		err = errors.New("usage: signversion keygen|sign")
	}
	if err != nil {
		fail(err)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "signversion:", err)
	os.Exit(1)
}

func keygen() error {
	if _, err := os.Stat(keyFile); err == nil {
		return errors.New(keyFile + " already exists; delete it first if you really want a new key (old builds will stop accepting updates)")
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	if err := os.WriteFile(keyFile, []byte(base64.StdEncoding.EncodeToString(priv.Seed())+"\n"), 0600); err != nil {
		return err
	}
	src, err := os.ReadFile(pubFile)
	if err != nil {
		return err
	}
	re := regexp.MustCompile(`const updatePubKey = "[^"]*"`)
	out := re.ReplaceAll(src, []byte(`const updatePubKey = "`+base64.StdEncoding.EncodeToString(pub)+`"`))
	if err := os.WriteFile(pubFile, out, 0644); err != nil {
		return err
	}
	fmt.Println("private key:", keyFile, "(keep it safe, back it up, never commit)")
	fmt.Println("public key written to", pubFile)
	return nil
}

func loadKey() (ed25519.PrivateKey, error) {
	s := os.Getenv("DASH_SIGN_KEY")
	if s == "" {
		b, err := os.ReadFile(keyFile)
		if err != nil {
			return nil, errors.New("set DASH_SIGN_KEY or put the key into " + keyFile)
		}
		s = string(b)
	}
	seed, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil || len(seed) != ed25519.SeedSize {
		return nil, errors.New("bad signing key")
	}
	return ed25519.NewKeyFromSeed(seed), nil
}

func sign() error {
	priv, err := loadKey()
	if err != nil {
		return err
	}
	// the key must match the one built into Dash.exe, or every client rejects the update
	src, err := os.ReadFile(pubFile)
	if err != nil {
		return err
	}
	pub := base64.StdEncoding.EncodeToString(priv.Public().(ed25519.PublicKey))
	if !strings.Contains(string(src), `"`+pub+`"`) {
		return errors.New("signing key does not match updatePubKey in " + pubFile)
	}
	body, err := os.ReadFile(manifest)
	if err != nil {
		return err
	}
	sig := base64.StdEncoding.EncodeToString(ed25519.Sign(priv, body))
	if err := os.WriteFile(manifest+".sig", []byte(sig+"\n"), 0644); err != nil {
		return err
	}
	fmt.Println("signed", manifest)
	return nil
}
