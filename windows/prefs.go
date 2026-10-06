package main

import (
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Prefs is stored as JSON in the data folder; subscription bodies live in subs/<hash>.txt.
type Prefs struct {
	mu   sync.Mutex
	path string
	dir  string
	D    PrefsData
}

type PrefsData struct {
	Subs       []string          `json:"subs"`
	Names      map[string]string `json:"names"`
	Pinned     string            `json:"pinned"`
	Off        []string          `json:"off"`
	RuDirect   bool              `json:"ru_direct"`
	BlockedVPN bool              `json:"blocked_vpn"`
	SubUpdated int64             `json:"sub_updated"`
	HWID       string            `json:"hwid"`
	TunAddr    int               `json:"tun_addr"`
	AutoStart  bool              `json:"connect_on_start"`
}

func LoadPrefs(dir string) *Prefs {
	p := &Prefs{path: filepath.Join(dir, "settings.json"), dir: dir}
	p.D = PrefsData{RuDirect: true, BlockedVPN: true, Names: map[string]string{}}
	if b, err := os.ReadFile(p.path); err == nil {
		_ = json.Unmarshal(b, &p.D)
	}
	if p.D.Names == nil {
		p.D.Names = map[string]string{}
	}
	return p
}

func (p *Prefs) save() {
	b, _ := json.MarshalIndent(p.D, "", "  ")
	tmp := p.path + ".tmp"
	if os.WriteFile(tmp, b, 0600) == nil {
		_ = os.Rename(tmp, p.path)
	}
}

func (p *Prefs) Update(f func(d *PrefsData)) {
	p.mu.Lock()
	defer p.mu.Unlock()
	f(&p.D)
	p.save()
}

func (p *Prefs) Get() PrefsData {
	p.mu.Lock()
	defer p.mu.Unlock()
	d := p.D
	d.Subs = append([]string{}, p.D.Subs...)
	d.Off = append([]string{}, p.D.Off...)
	names := map[string]string{}
	for k, v := range p.D.Names {
		names[k] = v
	}
	d.Names = names
	return d
}

func (p *Prefs) OffSet() map[string]bool {
	set := map[string]bool{}
	for _, n := range p.Get().Off {
		set[n] = true
	}
	return set
}

func (p *Prefs) AddSub(u string) bool {
	u = strings.TrimSpace(u)
	ok := false
	p.Update(func(d *PrefsData) {
		for _, x := range d.Subs {
			if x == u {
				return
			}
		}
		d.Subs = append(d.Subs, u)
		d.SubUpdated = 0
		ok = true
	})
	return ok
}

func (p *Prefs) RemoveSub(u string) {
	p.Update(func(d *PrefsData) {
		var out []string
		for _, x := range d.Subs {
			if x != u {
				out = append(out, x)
			}
		}
		d.Subs = out
		delete(d.Names, u)
		d.SubUpdated = 0
	})
	_ = os.Remove(p.cachePath(u))
}

func (p *Prefs) cachePath(u string) string {
	h := sha1.Sum([]byte(u))
	return filepath.Join(p.dir, "subs", hex.EncodeToString(h[:8])+".txt")
}

func (p *Prefs) Cache(u string) string {
	b, err := os.ReadFile(p.cachePath(u))
	if err != nil {
		return ""
	}
	return string(b)
}

func (p *Prefs) SetCache(u, body string) {
	_ = os.MkdirAll(filepath.Join(p.dir, "subs"), 0700)
	_ = os.WriteFile(p.cachePath(u), []byte(body), 0600)
}

func (p *Prefs) MarkUpdated() { p.Update(func(d *PrefsData) { d.SubUpdated = time.Now().Unix() }) }

func isHTTP(u string) bool { return strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://") }

// SubLabel: the panel's profile-title, else the host, else the key type.
func (p *Prefs) SubLabel(u string) string {
	if n := p.Get().Names[u]; n != "" {
		return n
	}
	if isHTTP(u) {
		rest := u[strings.Index(u, "://")+3:]
		if i := strings.IndexAny(rest, "/?#"); i >= 0 {
			rest = rest[:i]
		}
		if i := strings.LastIndex(rest, "@"); i >= 0 {
			rest = rest[i+1:]
		}
		if rest != "" {
			return rest
		}
		return "Подписка"
	}
	if i := strings.Index(u, "://"); i > 0 {
		return "Ключ " + u[:i]
	}
	return "Ключ"
}

// CachedEntries: what is saved locally, without network.
func (p *Prefs) CachedEntries() []SubEntry {
	var out []SubEntry
	for _, u := range p.Get().Subs {
		e := SubEntry{URL: u, Name: p.SubLabel(u)}
		if isHTTP(u) {
			e.Body = p.Cache(u)
		} else {
			e.Body = u
		}
		if e.Body != "" {
			out = append(out, e)
		}
	}
	return out
}
