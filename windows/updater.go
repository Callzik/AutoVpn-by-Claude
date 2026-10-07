package main

import (
	"archive/zip"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const versionURL = "https://raw.githubusercontent.com/Callzik/DashVPN-by-Claude/main/version.json"

// Updater checks GitHub for a newer Windows build and swaps Dash.exe in place.
type Updater struct {
	mu       sync.Mutex
	log      *Log
	dir      string
	newVer   string
	url      string
	progress int // -1 idle, 0..100 downloading, 101 restarting
	err      string
	// restart is set by main: stops the VPN, starts the new exe and exits
	restart func(exe string)
}

type UpdateView struct {
	Version  string `json:"version"`
	Progress int    `json:"progress"`
	Error    string `json:"error"`
}

func NewUpdater(dir string, log *Log) *Updater {
	u := &Updater{dir: dir, log: log, progress: -1}
	go func() {
		time.Sleep(8 * time.Second)
		for {
			u.Check()
			time.Sleep(6 * time.Hour)
		}
	}()
	return u
}

func (u *Updater) View() UpdateView {
	u.mu.Lock()
	defer u.mu.Unlock()
	return UpdateView{Version: u.newVer, Progress: u.progress, Error: u.err}
}

// newer reports whether dotted version a > b.
func newer(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			return x > y
		}
	}
	return false
}

func (u *Updater) Check() {
	cl := &http.Client{Timeout: 20 * time.Second}
	resp, err := cl.Get(versionURL)
	if err != nil {
		u.log.Add("Проверка обновлений: нет связи с GitHub")
		return
	}
	defer resp.Body.Close()
	var v struct {
		Windows struct {
			Version string `json:"version"`
			URL     string `json:"url"`
		} `json:"windows"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(resp.Body).Decode(&v) != nil || v.Windows.URL == "" {
		return
	}
	u.mu.Lock()
	defer u.mu.Unlock()
	if newer(v.Windows.Version, Version) {
		if u.newVer != v.Windows.Version {
			u.log.Add("Доступна версия " + v.Windows.Version)
		}
		u.newVer, u.url = v.Windows.Version, v.Windows.URL
	} else {
		u.newVer, u.url = "", ""
	}
}

type progressWriter struct {
	u           *Updater
	done, total int64
}

func (p *progressWriter) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	if p.total > 0 {
		p.u.mu.Lock()
		p.u.progress = int(p.done * 100 / p.total)
		p.u.mu.Unlock()
	}
	return len(b), nil
}

// Install downloads the zip, extracts Dash.exe next to the running one and restarts into it.
func (u *Updater) Install() {
	u.mu.Lock()
	if u.progress >= 0 || u.url == "" {
		u.mu.Unlock()
		return
	}
	url, ver := u.url, u.newVer
	u.progress, u.err = 0, ""
	u.mu.Unlock()
	go func() {
		exe, err := u.fetch(url)
		if err != nil {
			u.log.Add("Обновление не удалось: " + err.Error())
			u.mu.Lock()
			u.progress, u.err = -1, "Не удалось загрузить обновление"
			u.mu.Unlock()
			return
		}
		u.log.Add("Установка версии " + ver)
		u.mu.Lock()
		u.progress = 101
		u.mu.Unlock()
		if u.restart != nil {
			u.restart(exe)
		}
	}()
}

func (u *Updater) fetch(url string) (string, error) {
	dir := filepath.Join(u.dir, "update")
	_ = os.RemoveAll(dir)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	cl := &http.Client{Timeout: 10 * time.Minute}
	resp, err := cl.Get(url)
	if err != nil {
		return "", errors.New("нет связи с GitHub")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", errors.New("GitHub ответил " + resp.Status)
	}
	zpath := filepath.Join(dir, "update.zip")
	f, err := os.Create(zpath)
	if err != nil {
		return "", err
	}
	_, err = io.Copy(io.MultiWriter(f, &progressWriter{u: u, total: resp.ContentLength}), resp.Body)
	f.Close()
	if err != nil {
		return "", errors.New("загрузка прервалась")
	}
	zr, err := zip.OpenReader(zpath)
	if err != nil {
		return "", errors.New("архив повреждён")
	}
	defer zr.Close()
	for _, e := range zr.File {
		if strings.EqualFold(filepath.Base(e.Name), "Dash.exe") {
			src, err := e.Open()
			if err != nil {
				return "", err
			}
			out := filepath.Join(dir, "Dash.exe")
			dst, err := os.Create(out)
			if err != nil {
				src.Close()
				return "", err
			}
			_, err = io.Copy(dst, src)
			src.Close()
			dst.Close()
			if err != nil {
				return "", err
			}
			if st, _ := os.Stat(out); st == nil || st.Size() < 5<<20 {
				return "", errors.New("в архиве неполный Dash.exe")
			}
			return out, nil
		}
	}
	return "", errors.New("в архиве нет Dash.exe")
}

func (u *Updater) fail(msg string) {
	u.mu.Lock()
	u.progress, u.err = -1, msg
	u.mu.Unlock()
}
