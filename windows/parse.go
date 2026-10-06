package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
)

const (
	GroupRegular  = 0
	GroupLTE      = 1
	GroupExcluded = 2
)

// Server is one server from a subscription, already converted to a sing-box outbound.
type Server struct {
	RawName       string
	Name          string
	Tag           string
	Group         int
	OrigGroup     int
	Off           bool
	ExcludeReason string
	Host          string
	Sub           string
	Outbound      map[string]any
	// Xray outbound for transports sing-box lacks (xhttp); Outbound is then a socks hop to the bundled Xray.
	Xray     map[string]any
	XrayDeps []map[string]any
}

func (s *Server) GroupLabel() string {
	switch s.Group {
	case GroupLTE:
		return "LTE"
	case GroupRegular:
		return "обычный"
	}
	return "не участвует"
}

type obj = map[string]any

func arr(v ...any) []any { return v }

// ParseSub parses a subscription body (base64, list of links, Xray/sing-box JSON). Names are unique.
func ParseSub(text string, warnings *[]string) []*Server {
	out := parseRaw(text, warnings)
	seen := map[string]int{}
	for _, s := range out {
		k := seen[s.Name]
		seen[s.Name] = k + 1
		if k > 0 {
			s.Name = fmt.Sprintf("%s (%d)", s.Name, k+1)
		}
	}
	return out
}

func parseRaw(text string, warnings *[]string) []*Server {
	var out []*Server
	body := strings.TrimSpace(text)
	if body == "" {
		return out
	}
	if !strings.HasPrefix(body, "[") && !strings.HasPrefix(body, "{") && !strings.Contains(body, "://") {
		if d, ok := b64(body); ok {
			t := strings.TrimSpace(d)
			if strings.Contains(t, "://") || strings.HasPrefix(t, "[") || strings.HasPrefix(t, "{") {
				body = t
			}
		}
	}
	if strings.HasPrefix(body, "[") || strings.HasPrefix(body, "{") {
		var root any
		if err := json.Unmarshal([]byte(body), &root); err != nil {
			*warnings = append(*warnings, "Не удалось разобрать JSON-подписку: "+err.Error())
			return out
		}
		for i, s := range parseJSONSub(root, warnings) {
			s.Tag = "srv" + strconv.Itoa(i+1)
			s.Outbound["tag"] = s.Tag
			classify(s)
			out = append(out, s)
		}
		return out
	}
	n := 0
	for _, line := range regexp.MustCompile(`[\r\n]+`).Split(body, -1) {
		line = strings.TrimSpace(line)
		if line == "" || !strings.Contains(line, "://") {
			continue
		}
		s, err := parseLink(line, warnings)
		if err != nil {
			*warnings = append(*warnings, "Не удалось разобрать ссылку: "+shortLink(line)+" ("+err.Error()+")")
			continue
		}
		if s == nil {
			continue
		}
		n++
		s.Tag = "srv" + strconv.Itoa(n)
		s.Outbound["tag"] = s.Tag
		classify(s)
		out = append(out, s)
	}
	return out
}

func classify(s *Server) {
	n := strings.ToLower(s.RawName)
	switch {
	case strings.Contains(n, "авто") || strings.Contains(n, "auto"):
		s.Group = GroupExcluded
		s.ExcludeReason = "Автовыбор провайдера, дублирует встроенный"
	case strings.Contains(n, "lte") || strings.Contains(n, "белые списки") || strings.Contains(n, "белый список") || strings.Contains(n, "whitelist"):
		s.Group = GroupLTE
	case strings.Contains(s.RawName, "🇷🇺") || strings.Contains(n, "россия") || strings.Contains(n, "russia"):
		s.Group = GroupExcluded
		s.ExcludeReason = "Российские сайты и так идут напрямую"
	default:
		s.Group = GroupRegular
	}
}

var (
	reSpaces   = regexp.MustCompile(`\s+`)
	reEdgePipe = regexp.MustCompile(`^[|\s]+|[|\s]+$`)
	rePipe     = regexp.MustCompile(`\s*\|\s*`)
)

// cleanName keeps letters, digits, punctuation and flag emoji; drops other pictographs.
func cleanName(raw string) string {
	var sb strings.Builder
	rs := []rune(raw)
	for i := 0; i < len(rs); i++ {
		r := rs[i]
		if r >= 0x1F1E6 && r <= 0x1F1FF {
			sb.WriteRune(r)
			if i+1 < len(rs) && rs[i+1] >= 0x1F1E6 && rs[i+1] <= 0x1F1FF {
				sb.WriteRune(rs[i+1])
				i++
			}
			sb.WriteRune(' ')
		} else if unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.IsSpace(r) || strings.ContainsRune("|#-_.,()+/:", r) {
			sb.WriteRune(r)
		} else {
			sb.WriteRune(' ')
		}
	}
	s := strings.TrimSpace(reSpaces.ReplaceAllString(sb.String(), " "))
	s = reEdgePipe.ReplaceAllString(s, "")
	s = rePipe.ReplaceAllString(s, " | ")
	if s == "" {
		return strings.TrimSpace(raw)
	}
	return s
}

func newServer(rawName, host string) *Server {
	s := &Server{Host: host}
	s.RawName = strings.TrimSpace(rawName)
	if s.RawName == "" {
		s.RawName = host
	}
	s.Name = cleanName(s.RawName)
	return s
}

func xrayPlaceholder() map[string]any {
	return obj{"type": "socks", "tag": "", "server": "127.0.0.1", "server_port": 0, "version": "5"}
}

func isXhttp(t string) bool { return t == "xhttp" || t == "splithttp" }

/* ---------- share links ---------- */

func parseLink(link string, warnings *[]string) (*Server, error) {
	scheme := strings.ToLower(link[:strings.Index(link, "://")])
	switch scheme {
	case "vless", "trojan":
		return parseVlessTrojan(link, scheme, warnings)
	case "vmess":
		return parseVmess(link, warnings)
	case "ss":
		return parseSs(link)
	case "hy2", "hysteria2":
		return parseHy2(link)
	}
	*warnings = append(*warnings, "Протокол "+scheme+" не поддерживается: "+shortLink(link))
	return nil, nil
}

type parts struct {
	user, host, fragment string
	port                 int
	query                map[string]string
}

func (p *parts) q(k string) string { return p.query[k] }

func dec(s string) string {
	v, err := url.QueryUnescape(strings.ReplaceAll(s, "+", "%2B"))
	if err != nil {
		return s
	}
	return v
}

func partsOf(link string) (*parts, error) {
	p := &parts{query: map[string]string{}}
	rest := link[strings.Index(link, "://")+3:]
	if h := strings.Index(rest, "#"); h >= 0 {
		p.fragment = dec(rest[h+1:])
		rest = rest[:h]
	}
	query := ""
	if q := strings.Index(rest, "?"); q >= 0 {
		query = rest[q+1:]
		rest = rest[:q]
	}
	rest = strings.TrimSuffix(rest, "/")
	at := strings.LastIndex(rest, "@")
	if at < 0 {
		return nil, fmt.Errorf("нет @")
	}
	p.user = dec(rest[:at])
	hp := rest[at+1:]
	var portStr string
	if strings.HasPrefix(hp, "[") {
		c := strings.Index(hp, "]")
		if c < 0 || c+2 > len(hp) {
			return nil, fmt.Errorf("адрес")
		}
		p.host = hp[1:c]
		portStr = hp[c+2:]
	} else {
		c := strings.LastIndex(hp, ":")
		if c < 0 {
			return nil, fmt.Errorf("нет порта")
		}
		p.host = hp[:c]
		portStr = hp[c+1:]
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		return nil, fmt.Errorf("порт")
	}
	p.port = port
	for _, kv := range strings.Split(query, "&") {
		if kv == "" {
			continue
		}
		if eq := strings.Index(kv, "="); eq < 0 {
			p.query[dec(kv)] = ""
		} else {
			p.query[dec(kv[:eq])] = dec(kv[eq+1:])
		}
	}
	return p, nil
}

func parseVlessTrojan(link, typ string, warnings *[]string) (*Server, error) {
	p, err := partsOf(link)
	if err != nil {
		return nil, err
	}
	s := newServer(p.fragment, p.host)
	o := obj{"type": typ, "tag": "", "server": p.host, "server_port": p.port}
	if typ == "vless" {
		o["uuid"] = p.user
		if f := p.q("flow"); f != "" {
			o["flow"] = f
		}
		o["packet_encoding"] = "xudp"
	} else {
		o["password"] = p.user
	}
	security := p.q("security")
	if typ == "trojan" && security == "" {
		security = "tls"
	}
	if isXhttp(p.q("type")) {
		s.Xray = xrayFromLink(typ, p, security)
		s.Outbound = xrayPlaceholder()
		return s, nil
	}
	if !applyStream(o, p.query, security, p.host, warnings, s.RawName) {
		return nil, nil
	}
	s.Outbound = o
	return s, nil
}

func xrayFromLink(typ string, p *parts, security string) map[string]any {
	var settings obj
	if typ == "vless" {
		enc := p.q("encryption")
		if enc == "" {
			enc = "none"
		}
		user := obj{"id": p.user, "encryption": enc}
		if f := p.q("flow"); f != "" {
			user["flow"] = f
		}
		settings = obj{"vnext": arr(obj{"address": p.host, "port": p.port, "users": arr(user)})}
	} else {
		settings = obj{"servers": arr(obj{"address": p.host, "port": p.port, "password": p.user})}
	}
	path := p.q("path")
	if path == "" {
		path = "/"
	}
	xh := obj{"path": path}
	if h := p.q("host"); h != "" {
		xh["host"] = h
	}
	mode := p.q("mode")
	if mode == "" {
		mode = "auto"
	}
	xh["mode"] = mode
	if e := p.q("extra"); e != "" {
		var extra any
		if json.Unmarshal([]byte(e), &extra) == nil {
			xh["extra"] = extra
		}
	}
	st := obj{"network": "xhttp", "xhttpSettings": xh}
	sni := p.q("sni")
	if sni == "" {
		sni = p.q("peer")
	}
	fp := p.q("fp")
	switch security {
	case "reality":
		if sni == "" {
			sni = p.host
		}
		if fp == "" {
			fp = "chrome"
		}
		r := obj{"serverName": sni, "fingerprint": fp, "publicKey": p.q("pbk"), "shortId": p.q("sid")}
		if v := p.q("spx"); v != "" {
			r["spiderX"] = v
		}
		if v := p.q("pqv"); v != "" {
			r["mldsa65Verify"] = v
		}
		st["security"] = "reality"
		st["realitySettings"] = r
	case "tls":
		if sni == "" {
			sni = p.q("host")
		}
		if sni == "" {
			sni = p.host
		}
		t := obj{"serverName": sni}
		if fp != "" {
			t["fingerprint"] = fp
		}
		if a := p.q("alpn"); a != "" {
			t["alpn"] = toAny(strings.Split(a, ","))
		}
		if v := p.q("allowInsecure"); v == "1" || v == "true" {
			t["allowInsecure"] = true
		}
		st["security"] = "tls"
		st["tlsSettings"] = t
	default:
		st["security"] = "none"
	}
	return obj{"protocol": typ, "settings": settings, "streamSettings": st}
}

func toAny(ss []string) []any {
	out := make([]any, len(ss))
	for i, s := range ss {
		out[i] = s
	}
	return out
}

func num(v string, def int) int {
	v = strings.TrimSpace(v)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func parseVmess(link string, warnings *[]string) (*Server, error) {
	js, ok := b64(link[len("vmess://"):])
	if !ok {
		return nil, fmt.Errorf("vmess: не base64")
	}
	var raw map[string]any
	if err := json.Unmarshal([]byte(js), &raw); err != nil {
		return nil, fmt.Errorf("vmess: %v", err)
	}
	m := map[string]string{}
	for k, v := range raw {
		switch t := v.(type) {
		case string:
			m[k] = t
		case float64:
			m[k] = strconv.FormatFloat(t, 'f', -1, 64)
		case bool:
			m[k] = strconv.FormatBool(t)
		}
	}
	host := m["add"]
	name := m["ps"]
	if name == "" {
		name = host
	}
	s := newServer(name, host)
	scy := m["scy"]
	if scy == "" {
		scy = "auto"
	}
	o := obj{"type": "vmess", "tag": "", "server": host, "server_port": num(m["port"], 443), "uuid": m["id"],
		"alter_id": num(m["aid"], 0), "security": scy, "packet_encoding": "xudp"}
	netw := m["net"]
	if netw == "" {
		netw = "tcp"
	}
	q := map[string]string{"type": netw, "headerType": m["type"], "host": m["host"], "path": m["path"],
		"serviceName": m["path"], "sni": m["sni"], "alpn": m["alpn"], "fp": m["fp"]}
	sec := ""
	if m["tls"] == "tls" {
		sec = "tls"
	}
	if !applyStream(o, q, sec, host, warnings, s.RawName) {
		return nil, nil
	}
	s.Outbound = o
	return s, nil
}

func parseSs(link string) (*Server, error) {
	rest := link[len("ss://"):]
	fragment := ""
	if h := strings.Index(rest, "#"); h >= 0 {
		fragment = rest[h+1:]
		rest = rest[:h]
	}
	if q := strings.Index(rest, "?"); q >= 0 {
		rest = rest[:q]
	}
	var userInfo, hostPort string
	if at := strings.LastIndex(rest, "@"); at >= 0 {
		userInfo, hostPort = rest[:at], rest[at+1:]
		if d, ok := b64(dec(userInfo)); ok && strings.Contains(d, ":") {
			userInfo = d
		} else {
			userInfo = dec(userInfo)
		}
	} else {
		d, ok := b64(rest)
		if !ok {
			return nil, fmt.Errorf("ss: формат")
		}
		at = strings.LastIndex(d, "@")
		if at < 0 {
			return nil, fmt.Errorf("ss: формат")
		}
		userInfo, hostPort = d[:at], d[at+1:]
	}
	c := strings.Index(userInfo, ":")
	if c < 0 {
		return nil, fmt.Errorf("ss: формат")
	}
	pc := strings.LastIndex(hostPort, ":")
	if pc < 0 {
		return nil, fmt.Errorf("ss: порт")
	}
	host := strings.NewReplacer("[", "", "]", "").Replace(hostPort[:pc])
	port, err := strconv.Atoi(regexp.MustCompile(`[^0-9]`).ReplaceAllString(hostPort[pc+1:], ""))
	if err != nil {
		return nil, fmt.Errorf("ss: порт")
	}
	s := newServer(dec(fragment), host)
	s.Outbound = obj{"type": "shadowsocks", "tag": "", "server": host, "server_port": port,
		"method": userInfo[:c], "password": userInfo[c+1:]}
	return s, nil
}

func parseHy2(link string) (*Server, error) {
	p, err := partsOf(link)
	if err != nil {
		return nil, err
	}
	s := newServer(p.fragment, p.host)
	sni := p.q("sni")
	if sni == "" {
		sni = p.host
	}
	tls := obj{"enabled": true, "server_name": sni, "alpn": arr("h3")}
	if p.q("insecure") == "1" {
		tls["insecure"] = true
	}
	o := obj{"type": "hysteria2", "tag": "", "server": p.host, "server_port": p.port, "password": p.user, "tls": tls}
	if v := p.q("obfs"); v != "" {
		o["obfs"] = obj{"type": v, "password": p.q("obfs-password")}
	}
	s.Outbound = o
	return s, nil
}

var reEd = regexp.MustCompile(`[?&]ed=(\d+)`)

// applyStream adds tls and transport sections; false when the transport is unsupported.
func applyStream(o obj, q map[string]string, security, host string, warnings *[]string, name string) bool {
	v := func(k string) string { return strings.TrimSpace(q[k]) }
	sni := v("sni")
	if sni == "" {
		sni = v("peer")
	}
	hostHeader := v("host")
	fp := v("fp")
	switch security {
	case "reality":
		s := sni
		if s == "" {
			s = host
		}
		f := fp
		if f == "" {
			f = "chrome"
		}
		o["tls"] = obj{"enabled": true, "server_name": s, "utls": obj{"enabled": true, "fingerprint": f},
			"reality": obj{"enabled": true, "public_key": v("pbk"), "short_id": v("sid")}}
	case "tls", "xtls":
		s := sni
		if s == "" {
			s = hostHeader
		}
		if s == "" {
			s = host
		}
		t := obj{"enabled": true, "server_name": s}
		if a := v("allowInsecure"); a == "1" || a == "true" {
			t["insecure"] = true
		}
		if a := v("alpn"); a != "" {
			t["alpn"] = toAny(strings.Split(a, ","))
		}
		if fp != "" {
			t["utls"] = obj{"enabled": true, "fingerprint": fp}
		}
		o["tls"] = t
	}
	typ := v("type")
	if typ == "" {
		typ = "tcp"
	}
	path := v("path")
	orSlash := func(p string) string {
		if p == "" {
			return "/"
		}
		return p
	}
	hosts := func() any {
		if hostHeader == "" {
			return nil
		}
		return toAny(strings.Split(hostHeader, ","))
	}
	switch typ {
	case "tcp", "raw":
		if v("headerType") == "http" {
			t := obj{"type": "http", "path": orSlash(path)}
			if h := hosts(); h != nil {
				t["host"] = h
			}
			o["transport"] = t
		}
		return true
	case "ws":
		t := obj{"type": "ws"}
		if m := reEd.FindStringSubmatch(path); m != nil {
			n, _ := strconv.Atoi(m[1])
			t["max_early_data"] = n
			t["early_data_header_name"] = "Sec-WebSocket-Protocol"
			path = reEd.ReplaceAllString(path, "")
		}
		t["path"] = orSlash(path)
		if hostHeader != "" {
			t["headers"] = obj{"Host": hostHeader}
		}
		o["transport"] = t
		return true
	case "grpc":
		o["transport"] = obj{"type": "grpc", "service_name": v("serviceName")}
		return true
	case "http", "h2":
		t := obj{"type": "http", "path": orSlash(path)}
		if h := hosts(); h != nil {
			t["host"] = h
		}
		o["transport"] = t
		return true
	case "httpupgrade":
		t := obj{"type": "httpupgrade", "path": orSlash(path)}
		if hostHeader != "" {
			t["host"] = hostHeader
		}
		o["transport"] = t
		return true
	}
	*warnings = append(*warnings, "«"+name+"»: транспорт "+typ+" не поддерживается ядром sing-box, сервер пропущен")
	return false
}

func b64(s string) (string, bool) {
	t := reSpaces.ReplaceAllString(strings.TrimSpace(s), "")
	if t == "" {
		return "", false
	}
	t = strings.NewReplacer("-", "+", "_", "/").Replace(t)
	t = strings.TrimRight(t, "=")
	b, err := base64.RawStdEncoding.DecodeString(t)
	if err != nil {
		return "", false
	}
	return string(b), true
}

func shortLink(l string) string {
	r := []rune(l)
	if len(r) > 48 {
		return string(r[:48]) + "…"
	}
	return l
}
