package main

import (
	"embed"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
)

const Version = "1.5.2"

//go:embed assets/rules/*.srs
var rulesFS embed.FS

func itoa(n int) string { return strconv.Itoa(n) }

// extractRules writes the bundled rule-sets next to the data.
func extractRules(dir string) error {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	return fs.WalkDir(rulesFS, "assets/rules", func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		b, _ := rulesFS.ReadFile(p)
		return os.WriteFile(filepath.Join(dir, filepath.Base(p)), b, 0600)
	})
}

// App wires everything: data folder, core, local UI.
type App struct {
	Dir   string
	Prefs *Prefs
	Log   *Log
	Core  *Core
	API   *API
	Upd   *Updater
	Addr  string
}

func NewApp(binDir string, connect bool) (*App, error) {
	dir := dataRoot()
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	ruleDir := filepath.Join(dir, "rules")
	if err := extractRules(ruleDir); err != nil {
		return nil, err
	}
	a := &App{Dir: dir, Prefs: LoadPrefs(dir), Log: &Log{}}
	a.Core = NewCore(a.Prefs, dir, binDir, ruleDir, a.Log)
	a.Upd = NewUpdater(dir, a.Log)
	a.API = &API{core: a.Core, prefs: a.Prefs, log: a.Log, upd: a.Upd, token: randHex(16)}
	addr, err := a.API.Serve()
	if err != nil {
		return nil, err
	}
	a.Addr = addr
	a.Log.Add("Dash " + Version + " для Windows")
	if d := a.Prefs.Get(); (d.AutoStart || connect) && len(d.Subs) > 0 {
		a.Core.Connect()
	}
	return a, nil
}

func (a *App) URL() string { return "http://" + a.Addr + "/#" + a.API.token }
