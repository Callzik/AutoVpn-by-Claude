package main

import (
	"bufio"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	StOff        = "off"
	StConnecting = "connecting"
	StOn         = "on"
	StWaiting    = "waiting"

	subRefresh = 12 * time.Hour
)

// Core runs the cores and does what the Android BoxVpnService does: subscriptions, auto choice, watchdog.
type Core struct {
	mu      sync.Mutex
	prefs   *Prefs
	dataDir string
	binDir  string
	ruleDir string
	log     *Log

	gen    int
	stopCh chan struct{}

	state, phase, errMsg string
	since                time.Time

	servers    []*Server
	byTag      map[string]*Server
	sig        string
	hasRegular bool
	hasLte     bool

	clash   *Clash
	sbProc  *exec.Cmd
	xrProc  *exec.Cmd
	xrayCfg string

	activeGroup    string
	regularBlocked bool
	lastRegularTry time.Time
	deadCount      int
	switching      bool

	serverName, group  string
	ping, alive, gsize int
	pinned             string
	pings              map[string]int
	pinging, pingingC  bool
	banner             string
	bannerTone         int
	bannerAt           time.Time
	lastServers        string
	pendingRetry       *time.Timer
	crashes            []time.Time
}

func NewCore(prefs *Prefs, dataDir, binDir, ruleDir string, log *Log) *Core {
	return &Core{prefs: prefs, dataDir: dataDir, binDir: binDir, ruleDir: ruleDir, log: log,
		state: StOff, pings: map[string]int{}, byTag: map[string]*Server{}}
}

/* ---------- state helpers ---------- */

// alive reports whether the run started as generation g is still current (not stopped or restarted).
func (c *Core) current(g int) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.gen == g && c.state != StOff
}

func (c *Core) setState(g int, st, phase string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.gen != g || c.state == StOff {
		return false
	}
	if st == StOn && c.state != StOn {
		c.since = time.Now()
	}
	c.state = st
	if phase != "" || st == StOn {
		c.phase = phase
	}
	return true
}

func (c *Core) setPhase(g int, p string) {
	c.mu.Lock()
	if c.gen == g {
		c.phase = p
	}
	c.mu.Unlock()
}

func (c *Core) showBanner(text string, tone int) {
	c.log.Add(text)
	c.mu.Lock()
	c.banner, c.bannerTone, c.bannerAt = text, tone, time.Now()
	c.mu.Unlock()
}

func (c *Core) groupLabel(g string) string {
	if g == GroupNameLTE {
		return "резервные серверы"
	}
	return "основные серверы"
}

/* ---------- connect / disconnect ---------- */

func (c *Core) Toggle() {
	c.mu.Lock()
	st := c.state
	c.mu.Unlock()
	if st == StOff {
		c.Connect()
	} else {
		c.Disconnect("Отключено")
	}
}

func (c *Core) Connect() {
	c.mu.Lock()
	if c.state != StOff {
		c.mu.Unlock()
		return
	}
	c.gen++
	g := c.gen
	c.stopCh = make(chan struct{})
	c.state, c.phase, c.errMsg = StConnecting, "Загрузка подписок", ""
	c.serverName, c.ping, c.regularBlocked = "", -1, false
	c.mu.Unlock()
	go func() {
		if err := c.boot(g); err != nil {
			c.log.Add("Ошибка запуска: " + err.Error())
			c.fail(g, err.Error())
		}
	}()
}

func (c *Core) fail(g int, msg string) {
	c.mu.Lock()
	if c.gen != g || c.state == StOff {
		c.mu.Unlock()
		return
	}
	c.mu.Unlock()
	c.Disconnect("")
	c.mu.Lock()
	c.errMsg = msg
	c.mu.Unlock()
}

func (c *Core) Disconnect(reason string) {
	c.mu.Lock()
	if c.state == StOff {
		c.mu.Unlock()
		return
	}
	c.gen++
	if c.stopCh != nil {
		close(c.stopCh)
		c.stopCh = nil
	}
	if c.pendingRetry != nil {
		c.pendingRetry.Stop()
	}
	c.state, c.phase = StOff, ""
	c.switching, c.pinging, c.pingingC = false, false, false
	sb, xr := c.sbProc, c.xrProc
	c.sbProc, c.xrProc, c.clash = nil, nil, nil
	c.mu.Unlock()
	if reason != "" {
		c.log.Add(reason)
	}
	killProc(sb)
	killProc(xr)
}

func (c *Core) boot(g int) error {
	urls := c.prefs.Get().Subs
	if len(urls) == 0 {
		return fmt.Errorf("Добавьте ссылку на подписку")
	}
	if len(urls) > 1 {
		c.setPhase(g, fmt.Sprintf("Загрузка подписок (%d)", len(urls)))
	} else {
		c.setPhase(g, "Загрузка подписки")
	}
	entries := c.loadSubs(urls)
	if !c.current(g) {
		return nil
	}
	var warnings, stubs []string
	merged := ApplyOff(MergeSubs(entries, &warnings, &stubs), c.prefs.OffSet())
	for _, s := range stubs {
		c.log.Add("Вместо серверов заглушка — " + s)
	}
	usable := 0
	for _, s := range merged {
		if s.Group != GroupExcluded {
			usable++
		}
	}
	if len(merged) == 0 {
		if len(stubs) > 0 {
			return fmt.Errorf("Сервис подписки вместо серверов прислал заглушку: %s", stubs[0])
		}
		return fmt.Errorf("Не удалось скачать подписку. Проверьте ссылку и интернет")
	}
	if usable == 0 {
		return fmt.Errorf("Нет серверов для подключения: все отключены или не поддерживаются")
	}
	c.prefs.MarkUpdated()
	c.applyServers(merged, warnings)
	if err := c.startCore(g); err != nil {
		return err
	}
	go c.loops(g)
	return nil
}

/* ---------- subscriptions ---------- */

var httpDirect = &http.Client{Timeout: 25 * time.Second, Transport: &http.Transport{Proxy: nil}}

func (c *Core) fetch(u string) (string, string, error) {
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return "", "", err
	}
	req.Header.Set("User-Agent", "v2rayNG/1.10.19")
	req.Header.Set("Accept", "*/*")
	req.Header.Set("x-hwid", c.hwid())
	req.Header.Set("x-device-os", "Windows")
	req.Header.Set("x-ver-os", osVersion())
	req.Header.Set("x-device-model", deviceModel())
	req.Header.Set("x-app-version", "Dash-Windows/"+Version)
	resp, err := httpDirect.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 20<<20))
	if err != nil {
		return "", "", err
	}
	if strings.TrimSpace(string(b)) == "" {
		return "", "", fmt.Errorf("пустой ответ")
	}
	return string(b), profileTitle(resp.Header.Get("profile-title")), nil
}

func profileTitle(h string) string {
	h = strings.TrimSpace(h)
	if strings.HasPrefix(h, "base64:") {
		b, err := base64.StdEncoding.DecodeString(strings.TrimSpace(h[7:]))
		if err != nil {
			return ""
		}
		h = strings.TrimSpace(string(b))
	}
	if r := []rune(h); len(r) > 40 {
		h = string(r[:40])
	}
	return h
}

func (c *Core) hwid() string {
	if id := machineID(); id != "" {
		return id
	}
	d := c.prefs.Get()
	if d.HWID == "" {
		id := randHex(8)
		c.prefs.Update(func(p *PrefsData) { p.HWID = id })
		return id
	}
	return d.HWID
}

// loadSubs downloads all subscriptions at once; a failing one falls back to its saved copy.
func (c *Core) loadSubs(urls []string) []SubEntry {
	res := make([]*SubEntry, len(urls))
	var wg sync.WaitGroup
	for i, u := range urls {
		if !isHTTP(u) {
			res[i] = &SubEntry{URL: u, Name: c.prefs.SubLabel(u), Body: u}
			continue
		}
		wg.Add(1)
		go func(i int, u string) {
			defer wg.Done()
			body, title, err := c.fetch(u)
			if title != "" {
				c.prefs.Update(func(d *PrefsData) { d.Names[u] = title })
			}
			name := c.prefs.SubLabel(u)
			cache := c.prefs.Cache(u)
			if err != nil {
				msg := name + ": не удалось скачать (" + err.Error() + ")"
				if cache != "" {
					msg += ", используется сохранённая копия"
					res[i] = &SubEntry{URL: u, Name: name, Body: cache}
				}
				c.log.Add(msg)
				return
			}
			var w []string
			if stubReason(ParseSub(body, &w)) != "" {
				if cache != "" {
					c.log.Add(name + ": вместо серверов заглушка, используется сохранённая копия")
					body = cache
				}
			} else {
				c.prefs.SetCache(u, body)
				c.log.Add(name + ": подписка загружена")
			}
			res[i] = &SubEntry{URL: u, Name: name, Body: body}
		}(i, u)
	}
	wg.Wait()
	var out []SubEntry
	for _, e := range res {
		if e != nil {
			out = append(out, *e)
		}
	}
	return out
}

func signature(list []*Server) string {
	var sb strings.Builder
	for _, s := range list {
		sb.WriteString(fmt.Sprintf("%d|%s|%s|%s\n", s.Group, s.Name, jsonStr(s.Outbound), jsonStr(s.Xray)))
	}
	return sb.String()
}

func (c *Core) applyServers(list []*Server, warnings []string) {
	for _, w := range warnings {
		c.log.Add(w)
	}
	reg, lte, ex := 0, 0, 0
	byTag := map[string]*Server{}
	for _, s := range list {
		byTag[s.Tag] = s
		switch s.Group {
		case GroupRegular:
			reg++
		case GroupLTE:
			lte++
		default:
			ex++
		}
	}
	subs := map[string]bool{}
	for _, s := range list {
		subs[s.Sub] = true
	}
	summary := fmt.Sprintf("Серверов: %d · основных %d, резервных %d, не участвуют %d", len(list), reg, lte, ex)
	if len(subs) > 1 {
		summary += fmt.Sprintf(" · подписок %d", len(subs))
	}
	c.mu.Lock()
	c.servers, c.byTag, c.sig = list, byTag, signature(list)
	c.hasRegular, c.hasLte = reg > 0, lte > 0
	c.lastServers = summary
	c.pings = map[string]int{}
	c.mu.Unlock()
	c.log.Add(summary)
}

/* ---------- cores ---------- */

func freePort() int {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 19190
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func (c *Core) desiredGroup() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.hasRegular {
		return GroupNameRegular
	}
	return GroupNameLTE
}

func (c *Core) other(g string) string {
	c.mu.Lock()
	defer c.mu.Unlock()
	if g == GroupNameLTE {
		if c.hasRegular {
			return GroupNameRegular
		}
		return ""
	}
	if c.hasLte {
		return GroupNameLTE
	}
	return ""
}

func (c *Core) startCore(g int) error {
	group := c.desiredGroup()
	c.mu.Lock()
	c.activeGroup = group
	servers := c.servers
	c.mu.Unlock()

	// Xray for xhttp servers: local socks with fresh credentials per start
	var xs []*Server
	for _, s := range servers {
		if s.Xray != nil && s.Group != GroupExcluded {
			xs = append(xs, s)
		}
	}
	xrayCfg := ""
	if len(xs) > 0 {
		user, pass := randHex(6), randHex(12)
		for _, s := range xs {
			s.Outbound["server_port"] = freePort()
			s.Outbound["username"] = user
			s.Outbound["password"] = pass
		}
		xrayCfg = buildXrayConfig(xs, user, pass)
		c.log.Add(fmt.Sprintf("xhttp-серверов: %d, они идут через ядро Xray", len(xs)))
	}

	if !c.current(g) {
		return nil
	}
	if xrayCfg != "" {
		xp := filepath.Join(c.dataDir, "xray.json")
		_ = os.WriteFile(xp, []byte(xrayCfg), 0600)
		xp2, err := c.startProc(g, "Xray", filepath.Join(c.binDir, xrayExe), []string{"run", "-c", xp}, false)
		if err != nil {
			c.log.Add("Не удалось запустить Xray: " + err.Error() + " — xhttp-серверы недоступны")
		} else {
			xp2.mu.Lock()
			xp2.starting = false
			xp2.mu.Unlock()
			c.mu.Lock()
			c.xrProc = xp2.cmd
			c.mu.Unlock()
		}
	}
	c.setPhase(g, "Запуск ядра")
	// the TUN address may be taken by another VPN adapter: try the next one
	first := c.prefs.Get().TunAddr
	var lastErr error
	for i := 0; i < len(tunAddrs); i++ {
		idx := (first + i) % len(tunAddrs)
		ok, retry, err := c.startSingBox(g, servers, group, idx)
		if !c.current(g) {
			return nil
		}
		if ok {
			if idx != first {
				c.prefs.Update(func(d *PrefsData) { d.TunAddr = idx })
			}
			lastErr = nil
			break
		}
		lastErr = err
		if !retry {
			return err
		}
		c.log.Add("Адрес " + tunAddrs[idx][0] + " занят другим адаптером, пробуется следующий")
	}
	if lastErr != nil {
		return lastErr
	}
	c.setPhase(g, "Поиск самого быстрого сервера ("+c.groupLabel(group)+")")
	working := c.ensureWorkingGroup(g, group, true)
	if working == "" {
		c.setState(g, StWaiting, "Ни один сервер не отвечает. Повторная попытка через несколько секунд")
		c.scheduleRetry(g, 15*time.Second)
	} else {
		c.setState(g, StOn, "")
		if pn := c.prefs.Get().Pinned; pn != "" && !c.applyPin(pn) {
			c.prefs.Update(func(d *PrefsData) { d.Pinned = "" })
		}
	}
	c.refreshStatus()
	return nil
}

// proc is a running core; done is closed when it exits.
type proc struct {
	cmd      *exec.Cmd
	done     chan struct{}
	mu       sync.Mutex
	fatal    string
	starting bool
}

func (p *proc) lastFatal() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.fatal
}

func (p *proc) exited() bool {
	select {
	case <-p.done:
		return true
	default:
		return false
	}
}

// TUN addresses to try: another VPN client's adapter may already hold one of them.
var tunAddrs = [][2]string{
	{"10.255.250.1/30", "fd7a:7576:706e::1/126"},
	{"100.127.250.1/30", "fd7a:7576:706f::1/126"},
	{"172.31.250.1/30", "fd7a:7576:7070::1/126"},
	{"192.168.250.1/30", "fd7a:7576:7071::1/126"},
}

// startSingBox writes the config with TUN address #idx and starts the core.
// retry=true when the failure is an address conflict worth another address.
func (c *Core) startSingBox(g int, servers []*Server, group string, idx int) (ok, retry bool, err error) {
	port, secret := freePort(), randHex(16)
	cfg, err := BuildSingBox(servers, BuildOptions{
		RuDirect: c.prefs.Get().RuDirect, BlockedVPN: c.prefs.Get().BlockedVPN,
		RuleDir: c.ruleDir, Secret: secret, ClashPort: port, InitialGroup: group,
		TunName: "Dash", TunAddr4: tunAddrs[idx][0], TunAddr6: tunAddrs[idx][1], BypassProcs: bypassProcs()})
	if err != nil {
		return false, false, err
	}
	cfgPath := filepath.Join(c.dataDir, "config.json")
	if err := os.WriteFile(cfgPath, []byte(cfg), 0600); err != nil {
		return false, false, err
	}
	p, err := c.startProc(g, "", filepath.Join(c.binDir, singBoxExe), []string{"run", "-c", cfgPath, "-D", c.dataDir, "--disable-color"}, true)
	if err != nil {
		return false, false, fmt.Errorf("ядро не запустилось: %v", err)
	}
	clash := NewClash(port, secret)
	c.mu.Lock()
	c.sbProc, c.clash = p.cmd, clash
	c.mu.Unlock()
	deadline := time.Now().Add(25 * time.Second)
	for !clash.Alive() {
		if !c.current(g) {
			return false, false, nil
		}
		if p.exited() {
			f := strings.ToLower(p.lastFatal())
			conflict := strings.Contains(f, "already exists") || strings.Contains(f, "address") && strings.Contains(f, "tun")
			return false, conflict, fmt.Errorf("ядро завершилось при запуске, подробности в журнале")
		}
		if time.Now().After(deadline) {
			killProc(p.cmd)
			return false, false, fmt.Errorf("ядро не отвечает")
		}
		time.Sleep(300 * time.Millisecond)
	}
	p.mu.Lock()
	p.starting = false
	p.mu.Unlock()
	return true, false, nil
}

var (
	reAnsi  = regexp.MustCompile("\x1b\\[[;\\d]*m")
	reStamp = regexp.MustCompile(`^([+-]\d{4} )?\d{4}[-/]\d\d[-/]\d\d \d\d:\d\d:\d\d(\.\d+)? `)
)

func (c *Core) startProc(g int, prefix, bin string, args []string, critical bool) (*proc, error) {
	cmd := exec.Command(bin, args...)
	p := &proc{cmd: cmd, done: make(chan struct{}), starting: true}
	cmd.Dir = c.dataDir
	cmd.Env = append(os.Environ(), "XRAY_LOCATION_ASSET="+c.dataDir)
	hideWindow(cmd)
	pr, pw := io.Pipe()
	cmd.Stdout, cmd.Stderr = pw, pw
	if err := cmd.Start(); err != nil {
		return nil, err
	}
	afterStart(cmd)
	go func() {
		sc := bufio.NewScanner(pr)
		sc.Buffer(make([]byte, 64*1024), 1024*1024)
		for sc.Scan() {
			line := strings.TrimSpace(reStamp.ReplaceAllString(reAnsi.ReplaceAllString(sc.Text(), ""), ""))
			if line == "" || strings.Contains(line, "inbound connection") || strings.Contains(line, "outbound connection") ||
				strings.Contains(line, "packet connection") || strings.Contains(line, "[Info]") ||
				strings.Contains(line, "Penetrates Everything") || strings.Contains(line, "platform for anti-censorship") {
				continue
			}
			if strings.Contains(line, "FATAL") || strings.Contains(line, "Failed to start") {
				p.mu.Lock()
				p.fatal = line
				p.mu.Unlock()
			}
			if prefix != "" {
				line = prefix + ": " + line
			}
			c.log.Add(line)
		}
	}()
	go func() {
		_ = cmd.Wait()
		pw.Close()
		close(p.done)
		p.mu.Lock()
		starting := p.starting
		p.mu.Unlock()
		if starting || !c.current(g) {
			return // startCore deals with failures while starting
		}
		c.mu.Lock()
		mine := c.sbProc == cmd || c.xrProc == cmd
		c.mu.Unlock()
		if !mine {
			return
		}
		if critical {
			c.coreCrashed(g)
		} else {
			c.log.Add("Ядро Xray остановилось — xhttp-серверы недоступны, остальные работают")
		}
	}()
	return p, nil
}

// coreCrashed restarts the core by itself; gives up after 3 crashes in 10 minutes.
func (c *Core) coreCrashed(g int) {
	c.mu.Lock()
	now := time.Now()
	var recent []time.Time
	for _, t := range c.crashes {
		if now.Sub(t) < 10*time.Minute {
			recent = append(recent, t)
		}
	}
	recent = append(recent, now)
	c.crashes = recent
	n := len(recent)
	c.mu.Unlock()
	if n > 3 {
		c.log.Add("Ядро остановилось несколько раз подряд")
		c.fail(g, "Ядро останавливается снова и снова, подробности в журнале")
		return
	}
	c.log.Add("Ядро остановилось — перезапуск")
	c.Restart()
}

/* ---------- group logic ---------- */

func (c *Core) cl() *Clash {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.clash
}

func (c *Core) countGroup(g string) int {
	want := GroupRegular
	if g == GroupNameLTE {
		want = GroupLTE
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	n := 0
	for _, s := range c.servers {
		if s.Group == want {
			n++
		}
	}
	return n
}

// ensureWorkingGroup tests the group, falls back to the other one. Returns the group in use or "".
func (c *Core) ensureWorkingGroup(g int, group string, initial bool) string {
	cl := c.cl()
	if cl == nil {
		return ""
	}
	res := cl.TestGroup(group, 5000)
	c.log.Add(fmt.Sprintf("Пинг (%s): отвечают %d из %d", c.groupLabel(group), len(res), c.countGroup(group)))
	use := group
	if len(res) == 0 {
		if alt := c.other(group); alt != "" {
			r2 := cl.TestGroup(alt, 5000)
			c.log.Add(fmt.Sprintf("Пинг (%s): отвечают %d из %d", c.groupLabel(alt), len(r2), c.countGroup(alt)))
			if len(r2) > 0 {
				use, res = alt, r2
				if !initial {
					c.showBanner(c.groupLabel(group)+" не отвечают — переключено на "+c.groupLabel(alt), 2)
				}
			}
		}
	}
	if len(res) == 0 || !c.current(g) {
		return ""
	}
	blocked := use == GroupNameLTE && group == GroupNameRegular
	if blocked {
		c.log.Add("Основные серверы не отвечают — используются резервные")
	}
	_ = cl.Select(Selector, use)
	c.mu.Lock()
	c.regularBlocked = blocked
	c.activeGroup, c.group, c.alive = use, use, len(res)
	c.mu.Unlock()
	return use
}

func (c *Core) scheduleRetry(g int, d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.gen != g || c.state == StOff {
		return
	}
	if c.pendingRetry != nil {
		c.pendingRetry.Stop()
	}
	c.pendingRetry = time.AfterFunc(d, func() { c.reevaluate(g, "retry") })
}

func (c *Core) pinnedTag() string {
	cl := c.cl()
	if cl == nil {
		return ""
	}
	sel := cl.Now(Selector)
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.byTag[sel]; ok {
		return sel
	}
	return ""
}

func (c *Core) reevaluate(g int, cause string) {
	if !c.current(g) {
		return
	}
	cl := c.cl()
	if cl == nil {
		return
	}
	c.mu.Lock()
	waiting := c.state == StWaiting
	active := c.activeGroup
	blocked := c.regularBlocked
	lastTry := c.lastRegularTry
	prev := c.serverName
	c.mu.Unlock()
	if c.pinnedTag() != "" {
		if waiting {
			c.setState(g, StOn, "")
		}
		c.refreshStatus()
		return
	}
	want := c.desiredGroup()
	if want != active && blocked && cause == "periodic" && time.Since(lastTry) < 10*time.Minute {
		want = active // stay on white-list servers for now
	}
	if want == GroupNameRegular && want != active {
		c.mu.Lock()
		c.lastRegularTry = time.Now()
		c.mu.Unlock()
	}
	c.mu.Lock()
	c.switching = true
	c.mu.Unlock()
	used := ""
	if want != active || waiting {
		used = c.ensureWorkingGroup(g, want, false)
	} else {
		cur := cl.Now(active)
		if d := cl.LastDelay(cur); d > 0 && cur != "" && cause != "resume" {
			used = active
		} else if res := cl.TestGroup(active, 5000); len(res) > 0 {
			used = active
			c.mu.Lock()
			c.alive = len(res)
			c.mu.Unlock()
		} else {
			used = c.ensureWorkingGroup(g, active, false)
		}
	}
	c.mu.Lock()
	c.switching = false
	c.mu.Unlock()
	if !c.current(g) {
		return
	}
	if used == "" {
		c.setState(g, StWaiting, "Ни один сервер не отвечает. Повторная попытка через несколько секунд")
		c.scheduleRetry(g, 15*time.Second)
		return
	}
	if waiting {
		c.setState(g, StOn, "")
		if pn := c.prefs.Get().Pinned; pn != "" && !c.applyPin(pn) {
			c.prefs.Update(func(d *PrefsData) { d.Pinned = "" })
		}
	}
	c.refreshStatus()
	c.mu.Lock()
	name := c.serverName
	c.mu.Unlock()
	if prev != "" && name != prev {
		c.log.Add("Сервер сменился: " + prev + " → " + name)
	}
}

func (c *Core) refreshStatus() {
	cl := c.cl()
	if cl == nil {
		return
	}
	sel := cl.Now(Selector)
	if sel == "" {
		return
	}
	c.mu.Lock()
	picked, isServer := c.byTag[sel]
	c.mu.Unlock()
	var tag, group, pinned string
	if isServer {
		tag = sel
		group = GroupNameRegular
		if picked.Group == GroupLTE {
			group = GroupNameLTE
		}
		pinned = picked.Name
	} else {
		group = sel
		tag = cl.Now(sel)
	}
	ping := -1
	if tag != "" {
		ping = cl.LastDelay(tag)
	}
	gs := c.countGroup(group)
	c.mu.Lock()
	defer c.mu.Unlock()
	c.group, c.pinned, c.gsize = group, pinned, gs
	if s, ok := c.byTag[tag]; ok {
		c.serverName = s.Name
	} else {
		c.serverName = tag
	}
	if ping > 0 || c.ping <= 0 {
		c.ping = ping
	}
}

/* ---------- loops: status, watchdog, periodic, subscription update ---------- */

func (c *Core) loops(g int) {
	c.mu.Lock()
	stop := c.stopCh
	c.mu.Unlock()
	if stop == nil {
		return
	}
	status := time.NewTicker(5 * time.Second)
	watch := time.NewTicker(20 * time.Second)
	periodic := time.NewTicker(120 * time.Second)
	subs := time.NewTicker(30 * time.Minute)
	defer status.Stop()
	defer watch.Stop()
	defer periodic.Stop()
	defer subs.Stop()
	last := time.Now()
	for {
		select {
		case <-stop:
			return
		case <-status.C:
			// a long gap between ticks means the PC was asleep: old connections are dead
			if time.Since(last) > 30*time.Second {
				c.log.Add("Выход из сна — проверка соединения")
				if cl := c.cl(); cl != nil {
					cl.CloseAll()
				}
				go c.reevaluate(g, "resume")
			}
			last = time.Now()
			c.refreshStatus()
		case <-watch.C:
			c.watch(g)
		case <-periodic.C:
			c.reevaluate(g, "periodic")
		case <-subs.C:
			c.checkSub(g)
		}
	}
}

func (c *Core) watch(g int) {
	c.mu.Lock()
	ok := c.gen == g && c.state == StOn && !c.switching
	group := c.activeGroup
	c.mu.Unlock()
	cl := c.cl()
	if !ok || cl == nil {
		return
	}
	if pt := c.pinnedTag(); pt != "" {
		c.watchPinned(g, pt)
		return
	}
	tag := cl.Now(group)
	if tag == "" {
		return
	}
	if d := cl.Delay(tag, 5000); d > 0 {
		c.mu.Lock()
		c.deadCount, c.ping = 0, d
		c.mu.Unlock()
		return
	}
	c.mu.Lock()
	c.deadCount++
	dc := c.deadCount
	name := tag
	if s, ok := c.byTag[tag]; ok {
		name = s.Name
	}
	c.mu.Unlock()
	c.log.Add(fmt.Sprintf("Сервер %s не ответил (%d)", name, dc))
	if dc < 2 {
		return
	}
	c.mu.Lock()
	c.deadCount = 0
	c.mu.Unlock()
	res := cl.GroupCheck(group, 5000)
	now := cl.Now(group)
	if len(res) == 0 {
		if mine := cl.TestGroup(group, 4000); len(mine) == 0 {
			c.reevaluate(g, "dead")
			return
		}
		cl.GroupCheck(group, 5000)
		now = cl.Now(group)
	}
	c.refreshStatus()
	if now != tag {
		cl.CloseAll()
		c.mu.Lock()
		n := c.serverName
		c.mu.Unlock()
		c.showBanner("Сервер перестал отвечать — переключено на "+n, 2)
	}
}

func (c *Core) watchPinned(g int, tag string) {
	cl := c.cl()
	if d := cl.Delay(tag, 5000); d > 0 {
		c.mu.Lock()
		c.deadCount, c.ping = 0, d
		c.mu.Unlock()
		return
	}
	c.mu.Lock()
	c.deadCount++
	dc := c.deadCount
	c.mu.Unlock()
	c.log.Add(fmt.Sprintf("Выбранный сервер не ответил (%d)", dc))
	if dc < 2 {
		return
	}
	c.mu.Lock()
	c.deadCount = 0
	c.mu.Unlock()
	c.prefs.Update(func(d *PrefsData) { d.Pinned = "" })
	grp := c.desiredGroup()
	if c.ensureWorkingGroup(g, grp, false) == "" {
		_ = cl.Select(Selector, grp)
		c.setState(g, StWaiting, "Ни один сервер не отвечает. Повторная попытка через несколько секунд")
		c.scheduleRetry(g, 15*time.Second)
	}
	c.refreshStatus()
	c.showBanner("Выбранный сервер не отвечает — включён автовыбор", 2)
}

func (c *Core) checkSub(g int) {
	if !c.current(g) || time.Since(time.Unix(c.prefs.Get().SubUpdated, 0)) < subRefresh {
		return
	}
	urls := c.prefs.Get().Subs
	anyHTTP := false
	for _, u := range urls {
		anyHTTP = anyHTTP || isHTTP(u)
	}
	if !anyHTTP {
		return
	}
	entries := c.loadSubs(urls)
	var warnings, stubs []string
	fresh := ApplyOff(MergeSubs(entries, &warnings, &stubs), c.prefs.OffSet())
	if len(fresh) == 0 || !c.current(g) {
		c.log.Add("Автообновление: подписки не скачались, повтор позже")
		return
	}
	c.prefs.MarkUpdated()
	c.mu.Lock()
	same := signature(fresh) == c.sig
	c.mu.Unlock()
	if same {
		c.log.Add("Автообновление: подписки не изменились")
		return
	}
	c.log.Add("Автообновление: серверы в подписках изменились, перезапуск ядра")
	c.Restart()
}

// Restart reconnects from scratch (new subscriptions, servers turned on/off, settings).
func (c *Core) Restart() {
	c.mu.Lock()
	on := c.state != StOff
	c.mu.Unlock()
	if !on {
		return
	}
	c.Disconnect("Переподключение")
	go func() {
		time.Sleep(1500 * time.Millisecond)
		c.Connect()
	}()
}

/* ---------- manual choice and pings ---------- */

func (c *Core) applyPin(name string) bool {
	cl := c.cl()
	if cl == nil {
		return false
	}
	c.mu.Lock()
	var tag string
	for _, s := range c.servers {
		if s.Group != GroupExcluded && s.Name == name {
			tag = s.Tag
		}
	}
	c.mu.Unlock()
	if tag == "" {
		return false
	}
	_ = cl.Select(Selector, tag)
	c.mu.Lock()
	c.deadCount = 0
	c.mu.Unlock()
	c.log.Add("Сервер выбран вручную: " + name)
	return true
}

// Pin: name "" returns to the automatic choice.
func (c *Core) Pin(name string) {
	c.prefs.Update(func(d *PrefsData) { d.Pinned = name })
	c.mu.Lock()
	g := c.gen
	st := c.state
	c.mu.Unlock()
	if st != StOn && st != StWaiting {
		return
	}
	go func() {
		c.mu.Lock()
		c.switching = true
		c.mu.Unlock()
		if name == "" {
			c.log.Add("Возврат к автовыбору")
			grp := c.desiredGroup()
			if c.ensureWorkingGroup(g, grp, false) == "" {
				if cl := c.cl(); cl != nil {
					_ = cl.Select(Selector, grp)
				}
				c.setState(g, StWaiting, "Ни один сервер не отвечает. Повторная попытка через несколько секунд")
				c.scheduleRetry(g, 15*time.Second)
			}
		} else if !c.applyPin(name) {
			c.prefs.Update(func(d *PrefsData) { d.Pinned = "" })
		}
		c.mu.Lock()
		c.switching = false
		c.mu.Unlock()
		c.refreshStatus()
	}()
}

func (c *Core) PingAll() {
	c.mu.Lock()
	cl := c.clash
	if cl == nil || c.pinging || c.state == StOff {
		c.mu.Unlock()
		return
	}
	c.pinging = true
	c.pings = map[string]int{}
	var tags []string
	for _, s := range c.servers {
		if s.Group != GroupExcluded {
			tags = append(tags, s.Tag)
		}
	}
	c.mu.Unlock()
	go func() {
		cl.TestTags(tags, 5000, func(tag string, d int) {
			c.mu.Lock()
			c.pings[tag] = d
			c.mu.Unlock()
		})
		c.mu.Lock()
		c.pinging = false
		c.mu.Unlock()
	}()
}

func (c *Core) PingCurrent() {
	c.mu.Lock()
	cl := c.clash
	if cl == nil || c.pingingC || c.state != StOn {
		c.mu.Unlock()
		return
	}
	c.pingingC = true
	group := c.activeGroup
	c.mu.Unlock()
	go func() {
		tag := c.pinnedTag()
		if tag == "" {
			tag = cl.Now(group)
		}
		d := -1
		if tag != "" {
			d = cl.Delay(tag, 5000)
		}
		c.mu.Lock()
		c.ping = d
		if tag != "" {
			c.pings[tag] = d
		}
		c.pingingC = false
		c.mu.Unlock()
		if d > 0 {
			c.log.Add(fmt.Sprintf("Пинг текущего сервера: %d мс", d))
		} else {
			c.log.Add("Пинг текущего сервера: нет ответа")
		}
	}()
}

/* ---------- snapshots for the UI ---------- */

type StateView struct {
	State          string `json:"state"`
	Phase          string `json:"phase"`
	Error          string `json:"error"`
	Since          int64  `json:"since"`
	Server         string `json:"server"`
	Ping           int    `json:"ping"`
	Alive          int    `json:"alive"`
	Group          string `json:"group"`
	Pinned         string `json:"pinned"`
	Switching      bool   `json:"switching"`
	RegularBlocked bool   `json:"regularBlocked"`
	PingingCurrent bool   `json:"pingingCurrent"`
	Pinging        bool   `json:"pinging"`
	Banner         string `json:"banner"`
	BannerTone     int    `json:"bannerTone"`
	Summary        string `json:"summary"`
	Version        string `json:"version"`
	HasSubs        bool   `json:"hasSubs"`
}

func (c *Core) View() StateView {
	c.mu.Lock()
	defer c.mu.Unlock()
	v := StateView{State: c.state, Phase: c.phase, Error: c.errMsg, Server: c.serverName, Ping: c.ping,
		Alive: c.alive, Group: c.group, Pinned: c.pinned, Switching: c.switching, RegularBlocked: c.regularBlocked,
		PingingCurrent: c.pingingC, Pinging: c.pinging, Summary: c.lastServers, Version: Version,
		HasSubs: len(c.prefs.Get().Subs) > 0}
	if c.state == StOn {
		v.Since = c.since.Unix()
	}
	if c.banner != "" && time.Since(c.bannerAt) < 7*time.Second && c.state != StOff {
		v.Banner, v.BannerTone = c.banner, c.bannerTone
	}
	return v
}

type ServerView struct {
	Name  string `json:"name"`
	Group string `json:"group"`
	Sub   string `json:"sub"`
	Off   bool   `json:"off"`
	Ping  int    `json:"ping"` // 0 unknown, -1 no answer
	Xhttp bool   `json:"xhttp"`
}

func (c *Core) Servers() []ServerView {
	c.mu.Lock()
	list := c.servers
	pings := map[string]int{}
	for k, v := range c.pings {
		pings[k] = v
	}
	c.mu.Unlock()
	if len(list) == 0 {
		var w, st []string
		list = ApplyOff(MergeSubs(c.prefs.CachedEntries(), &w, &st), c.prefs.OffSet())
	}
	off := c.prefs.OffSet()
	var out []ServerView
	for _, s := range list {
		if s.Group == GroupExcluded && !s.Off {
			continue
		}
		g := "regular"
		if s.OrigGroup == GroupLTE {
			g = "lte"
		}
		out = append(out, ServerView{Name: s.Name, Group: g, Sub: s.Sub, Off: off[s.Name], Ping: pings[s.Tag], Xhttp: s.Xray != nil})
	}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if a.Off != b.Off {
			return !a.Off
		}
		if a.Group != b.Group {
			return a.Group == "regular"
		}
		key := func(p int) int {
			if p > 0 {
				return p
			}
			if p == 0 {
				return 100000
			}
			return 200000
		}
		return key(a.Ping) < key(b.Ping)
	})
	return out
}

func (c *Core) SetOff(name string, off bool) {
	c.prefs.Update(func(d *PrefsData) {
		var out []string
		for _, n := range d.Off {
			if n != name {
				out = append(out, n)
			}
		}
		if off {
			out = append(out, name)
			if d.Pinned == name {
				d.Pinned = ""
			}
		}
		d.Off = out
	})
}
