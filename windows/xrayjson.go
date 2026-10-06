package main

import (
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
)

var sbTypes = map[string]bool{"vless": true, "vmess": true, "trojan": true, "shadowsocks": true, "hysteria2": true, "tuic": true}

func m(o any) map[string]any {
	v, _ := o.(map[string]any)
	return v
}

func l(o any) []any {
	v, _ := o.([]any)
	return v
}

func first(o any) map[string]any {
	ls := l(o)
	if len(ls) == 0 {
		return nil
	}
	return m(ls[0])
}

func str(o any) string {
	switch v := o.(type) {
	case nil:
		return ""
	case string:
		return strings.TrimSpace(v)
	case float64:
		return strconv.FormatFloat(v, 'f', -1, 64)
	case int:
		return strconv.Itoa(v)
	}
	return strings.TrimSpace(fmt.Sprint(o))
}

func firstStr(o any) string {
	if ls := l(o); ls != nil {
		if len(ls) == 0 {
			return ""
		}
		return str(ls[0])
	}
	return str(o)
}

func joinList(o any) string {
	ls := l(o)
	if ls == nil {
		return str(o)
	}
	var parts []string
	for _, x := range ls {
		parts = append(parts, str(x))
	}
	return strings.Join(parts, ",")
}

func numOf(o any) int {
	if f, ok := o.(float64); ok {
		return int(f)
	}
	n, _ := strconv.Atoi(str(o))
	return n
}

func deepCopy(o map[string]any) map[string]any {
	b, _ := json.Marshal(o)
	var out map[string]any
	_ = json.Unmarshal(b, &out)
	return out
}

func jsonStr(o any) string {
	b, _ := json.Marshal(o)
	return string(b)
}

// parseJSONSub converts Xray full configs (Remnawave etc.) or sing-box outbound lists.
func parseJSONSub(root any, warnings *[]string) []*Server {
	var configs []any
	if ls := l(root); ls != nil {
		configs = ls
	} else {
		configs = []any{root}
	}
	var out []*Server
	seen := map[string]bool{}
	for _, c := range configs {
		cfg := m(c)
		if cfg == nil {
			continue
		}
		remarks := str(cfg["remarks"])
		obs := l(cfg["outbounds"])
		if obs == nil && cfg["type"] != nil {
			obs = []any{cfg}
		}
		var fromCfg []*Server
		for _, ob := range obs {
			o := m(ob)
			if o == nil {
				continue
			}
			var s *Server
			var err error
			if _, ok := o["protocol"]; ok {
				s, err = fromXray(o, obs, remarks, warnings)
			} else {
				s = fromSingBox(o, remarks)
			}
			if err != nil {
				*warnings = append(*warnings, "«"+remarks+"»: не удалось разобрать сервер ("+err.Error()+")")
				continue
			}
			if s != nil {
				fromCfg = append(fromCfg, s)
			}
		}
		if len(fromCfg) > 1 {
			for i, s := range fromCfg {
				s.RawName = fmt.Sprintf("%s #%d", s.RawName, i+1)
				s.Name = cleanName(s.RawName)
			}
		}
		for _, s := range fromCfg {
			base := s.RawName
			if i := strings.LastIndex(base, " #"); i >= 0 {
				if _, err := strconv.Atoi(base[i+2:]); err == nil {
					base = base[:i]
				}
			}
			key := jsonStr(s.Outbound) + jsonStr(s.Xray) + "|" + base
			if !seen[key] {
				seen[key] = true
				out = append(out, s)
			}
		}
	}
	return out
}

func fromSingBox(o map[string]any, remarks string) *Server {
	typ := str(o["type"])
	if !sbTypes[typ] || o["server"] == nil {
		return nil
	}
	name := remarks
	if name == "" {
		name = str(o["tag"])
	}
	s := newServer(name, str(o["server"]))
	cp := deepCopy(o)
	delete(cp, "detour")
	delete(cp, "domain_resolver")
	s.Outbound = cp
	return s
}

func fromXray(o map[string]any, all []any, remarks string, warnings *[]string) (*Server, error) {
	proto := str(o["protocol"])
	settings := m(o["settings"])
	if settings == nil {
		return nil, nil
	}
	name := remarks
	if name == "" {
		name = str(o["tag"])
	}
	var sb obj
	var host string
	switch proto {
	case "vless", "vmess":
		vn := first(settings["vnext"])
		if vn == nil {
			return nil, nil
		}
		host = str(vn["address"])
		user := first(vn["users"])
		if user == nil {
			return nil, nil
		}
		sb = obj{"type": proto, "tag": "", "server": host, "server_port": numOf(vn["port"]), "uuid": str(user["id"])}
		if proto == "vless" {
			if f := str(user["flow"]); f != "" {
				sb["flow"] = f
			}
		} else {
			sb["alter_id"] = numOf(user["alterId"])
			sec := str(user["security"])
			if sec == "" {
				sec = "auto"
			}
			sb["security"] = sec
		}
		sb["packet_encoding"] = "xudp"
	case "trojan", "shadowsocks":
		sv := first(settings["servers"])
		if sv == nil {
			return nil, nil
		}
		host = str(sv["address"])
		sb = obj{"type": proto, "tag": "", "server": host, "server_port": numOf(sv["port"]), "password": str(sv["password"])}
		if proto == "shadowsocks" {
			sb["method"] = str(sv["method"])
		}
	default:
		return nil, nil // freedom, blackhole, dns …
	}
	s := newServer(name, host)
	st := m(o["streamSettings"])
	if st == nil {
		st = obj{}
	}
	netw := str(st["network"])
	if isXhttp(netw) {
		s.Xray = deepCopy(o)
		delete(s.Xray, "tag")
		s.XrayDeps = xrayDeps(st, all)
		s.Outbound = xrayPlaceholder()
		return s, nil
	}
	q := map[string]string{}
	if netw == "" {
		netw = "tcp"
	}
	q["type"] = netw
	security := str(st["security"])
	if security == "none" {
		security = ""
	}
	tls := m(st["tlsSettings"])
	reality := m(st["realitySettings"])
	if security == "reality" && reality != nil {
		q["sni"] = str(reality["serverName"])
		q["fp"] = str(reality["fingerprint"])
		q["pbk"] = str(reality["publicKey"])
		q["sid"] = str(reality["shortId"])
	} else if tls != nil {
		q["sni"] = str(tls["serverName"])
		q["fp"] = str(tls["fingerprint"])
		if b, _ := tls["allowInsecure"].(bool); b {
			q["allowInsecure"] = "1"
		}
		if a := l(tls["alpn"]); len(a) > 0 {
			q["alpn"] = joinList(a)
		}
	}
	switch netw {
	case "ws":
		if ws := m(st["wsSettings"]); ws != nil {
			q["path"] = str(ws["path"])
			h := str(ws["host"])
			if hdr := m(ws["headers"]); h == "" && hdr != nil {
				h = str(hdr["Host"])
			}
			q["host"] = h
		}
	case "grpc":
		if g := m(st["grpcSettings"]); g != nil {
			q["serviceName"] = str(g["serviceName"])
		}
	case "httpupgrade":
		if h := m(st["httpupgradeSettings"]); h != nil {
			q["path"] = str(h["path"])
			q["host"] = str(h["host"])
		}
	case "h2", "http":
		if h := m(st["httpSettings"]); h != nil {
			q["path"] = str(h["path"])
			q["host"] = joinList(h["host"])
		}
	case "tcp", "raw":
		t := m(st["tcpSettings"])
		if t == nil {
			t = m(st["rawSettings"])
		}
		if t != nil {
			if header := m(t["header"]); header != nil && str(header["type"]) == "http" {
				q["headerType"] = "http"
				if req := m(header["request"]); req != nil {
					q["path"] = firstStr(req["path"])
					if hh := m(req["headers"]); hh != nil {
						q["host"] = joinList(hh["Host"])
					}
				}
			}
		}
	}
	if !applyStream(sb, q, security, host, warnings, s.RawName) {
		return nil, nil
	}
	s.Outbound = sb
	return s, nil
}

func xrayDeps(st map[string]any, all []any) []map[string]any {
	var out []map[string]any
	var want []string
	collectDialer(st, &want)
	for i := 0; i < len(want) && i < 8; i++ {
		tag := want[i]
		for _, ob := range all {
			d := m(ob)
			if d == nil || str(d["tag"]) != tag {
				continue
			}
			dup := false
			for _, e := range out {
				dup = dup || str(e["tag"]) == tag
			}
			if !dup {
				out = append(out, deepCopy(d))
				collectDialer(m(d["streamSettings"]), &want)
			}
			break
		}
	}
	return out
}

func collectDialer(st map[string]any, into *[]string) {
	if st == nil {
		return
	}
	if so := m(st["sockopt"]); so != nil && str(so["dialerProxy"]) != "" {
		*into = append(*into, str(so["dialerProxy"]))
	}
	if xh := m(st["xhttpSettings"]); xh != nil {
		if extra := m(xh["extra"]); extra != nil {
			collectDialer(m(extra["downloadSettings"]), into)
		}
	}
}

// buildXrayConfig: one password-protected local socks inbound per server, routed to its outbound.
func buildXrayConfig(servers []*Server, user, pass string) string {
	var inbounds, outbounds, rules []any
	outbounds = append(outbounds, obj{"protocol": "blackhole", "tag": "block"})
	for _, s := range servers {
		if s.Xray == nil {
			continue
		}
		in, out := "in-"+s.Tag, "out-"+s.Tag
		inbounds = append(inbounds, obj{"tag": in, "listen": "127.0.0.1", "port": s.Outbound["server_port"], "protocol": "socks",
			"settings": obj{"auth": "password", "accounts": arr(obj{"user": user, "pass": pass}), "udp": true, "ip": "127.0.0.1"}})
		ob := deepCopy(s.Xray)
		ob["tag"] = out
		rename := map[string]string{}
		for k, d := range s.XrayDeps {
			rename[str(d["tag"])] = fmt.Sprintf("%s-via%d", out, k+1)
		}
		retagStream(m(ob["streamSettings"]), rename)
		outbounds = append(outbounds, ob)
		for _, d0 := range s.XrayDeps {
			d := deepCopy(d0)
			d["tag"] = rename[str(d0["tag"])]
			retagStream(m(d["streamSettings"]), rename)
			outbounds = append(outbounds, d)
		}
		rules = append(rules, obj{"type": "field", "inboundTag": arr(in), "outboundTag": out})
	}
	return jsonStr(obj{"log": obj{"loglevel": "warning", "access": "none"}, "inbounds": inbounds, "outbounds": outbounds,
		"routing": obj{"domainStrategy": "AsIs", "rules": rules}})
}

func retagStream(st map[string]any, rename map[string]string) {
	if st == nil {
		return
	}
	if so := m(st["sockopt"]); so != nil {
		if _, ok := so["dialerProxy"]; ok {
			t := str(so["dialerProxy"])
			if r, ok := rename[t]; ok {
				so["dialerProxy"] = r
			} else {
				delete(so, "dialerProxy") // an unknown chain would stop Xray from starting
			}
		}
	}
	if xh := m(st["xhttpSettings"]); xh != nil {
		if extra := m(xh["extra"]); extra != nil {
			retagStream(m(extra["downloadSettings"]), rename)
		}
	}
}
