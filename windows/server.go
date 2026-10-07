package main

import (
	"embed"
	"encoding/json"
	"io/fs"
	"net"
	"net/http"
	"strings"
)

//go:embed ui
var uiFS embed.FS

type API struct {
	core  *Core
	prefs *Prefs
	log   *Log
	upd   *Updater
	token string
	quit  func()
}

func (a *API) handler() http.Handler {
	mux := http.NewServeMux()
	sub, _ := fs.Sub(uiFS, "ui")
	mux.Handle("/", http.FileServer(http.FS(sub)))
	api := func(path string, f func(w http.ResponseWriter, r *http.Request)) {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			// only our own page knows the token; a custom header cannot be sent cross-site without CORS
			if r.Header.Get("X-Token") != a.token {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			w.Header().Set("Cache-Control", "no-store")
			f(w, r)
		})
	}
	writeJSON := func(w http.ResponseWriter, v any) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		_ = json.NewEncoder(w).Encode(v)
	}
	body := func(r *http.Request) map[string]any {
		var m map[string]any
		_ = json.NewDecoder(r.Body).Decode(&m)
		if m == nil {
			m = map[string]any{}
		}
		return m
	}
	api("/api/state", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"s": a.core.View(), "update": a.upd.View()})
	})
	api("/api/update/install", func(w http.ResponseWriter, r *http.Request) { a.upd.Install(); writeJSON(w, true) })
	api("/api/update/check", func(w http.ResponseWriter, r *http.Request) { a.upd.Check(); writeJSON(w, a.upd.View()) })
	api("/api/apps", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"bypass": a.prefs.Get().BypassApps, "running": runningApps()})
	})
	api("/api/apps/set", func(w http.ResponseWriter, r *http.Request) {
		var list []string
		seen := map[string]bool{}
		if arr, ok := body(r)["list"].([]any); ok {
			for _, v := range arr {
				n := strings.TrimSpace(str(v))
				if n == "" || seen[strings.ToLower(n)] {
					continue
				}
				if !strings.HasSuffix(strings.ToLower(n), ".exe") {
					n += ".exe"
				}
				seen[strings.ToLower(n)] = true
				list = append(list, n)
			}
		}
		a.prefs.Update(func(d *PrefsData) { d.BypassApps = list })
		a.core.Restart()
		writeJSON(w, true)
	})
	api("/api/toggle", func(w http.ResponseWriter, r *http.Request) { a.core.Toggle(); writeJSON(w, true) })
	api("/api/servers", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"servers": a.core.Servers(), "pinned": a.prefs.Get().Pinned})
	})
	api("/api/pin", func(w http.ResponseWriter, r *http.Request) {
		a.core.Pin(str(body(r)["name"]))
		writeJSON(w, true)
	})
	api("/api/off", func(w http.ResponseWriter, r *http.Request) {
		b := body(r)
		off, _ := b["off"].(bool)
		a.core.SetOff(str(b["name"]), off)
		writeJSON(w, true)
	})
	api("/api/apply", func(w http.ResponseWriter, r *http.Request) { a.core.Restart(); writeJSON(w, true) })
	api("/api/ping-all", func(w http.ResponseWriter, r *http.Request) { a.core.PingAll(); writeJSON(w, true) })
	api("/api/ping-current", func(w http.ResponseWriter, r *http.Request) { a.core.PingCurrent(); writeJSON(w, true) })
	api("/api/subs", func(w http.ResponseWriter, r *http.Request) {
		d := a.prefs.Get()
		var list []map[string]string
		for _, u := range d.Subs {
			list = append(list, map[string]string{"url": u, "name": a.prefs.SubLabel(u)})
		}
		writeJSON(w, map[string]any{"subs": list, "updated": d.SubUpdated, "ruDirect": d.RuDirect, "blockedVpn": d.BlockedVPN, "autoStart": d.AutoStart,
			"summary": a.core.View().Summary})
	})
	api("/api/subs/add", func(w http.ResponseWriter, r *http.Request) {
		u := strings.TrimSpace(str(body(r)["url"]))
		if !looksLikeLink(u) {
			writeJSON(w, map[string]any{"ok": false, "error": "Это не похоже на ссылку на подписку"})
			return
		}
		if !a.prefs.AddSub(u) {
			writeJSON(w, map[string]any{"ok": false, "error": "Эта подписка уже добавлена"})
			return
		}
		a.core.Restart()
		writeJSON(w, map[string]any{"ok": true})
	})
	api("/api/subs/remove", func(w http.ResponseWriter, r *http.Request) {
		a.prefs.RemoveSub(str(body(r)["url"]))
		a.core.Restart()
		writeJSON(w, true)
	})
	api("/api/settings", func(w http.ResponseWriter, r *http.Request) {
		b := body(r)
		a.prefs.Update(func(d *PrefsData) {
			if v, ok := b["ruDirect"].(bool); ok {
				d.RuDirect = v
			}
			if v, ok := b["blockedVpn"].(bool); ok {
				d.BlockedVPN = v
			}
			if v, ok := b["autoStart"].(bool); ok {
				d.AutoStart = v
			}
		})
		if _, only := b["autoStart"]; !only || len(b) > 1 {
			a.core.Restart()
		}
		writeJSON(w, true)
	})
	api("/api/refresh", func(w http.ResponseWriter, r *http.Request) {
		a.prefs.Update(func(d *PrefsData) { d.SubUpdated = 0 })
		a.core.Restart()
		writeJSON(w, true)
	})
	api("/api/log", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, map[string]string{"text": a.log.Text()}) })
	api("/api/quit", func(w http.ResponseWriter, r *http.Request) { writeJSON(w, true); go a.quit() })
	return mux
}

func looksLikeLink(v string) bool {
	low := strings.ToLower(v)
	for _, p := range []string{"https://", "http://", "vless://", "vmess://", "trojan://", "ss://", "hy2://", "hysteria2://"} {
		if strings.HasPrefix(low, p) && len(v) > len(p)+3 {
			return true
		}
	}
	return false
}

// Serve starts the local UI server on 127.0.0.1 and returns its address.
func (a *API) Serve() (string, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	go func() { _ = http.Serve(ln, a.handler()) }()
	return ln.Addr().String(), nil
}
