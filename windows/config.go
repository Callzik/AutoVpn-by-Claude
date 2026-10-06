package main

import (
	"fmt"
	"path/filepath"
	"strconv"
)

const (
	GroupNameRegular = "auto-regular"
	GroupNameLTE     = "auto-lte"
	Selector         = "proxy"
)

// TestURL is a var only so the Linux test build can point it at a local page.
var TestURL = "https://www.gstatic.com/generate_204"

// testMixedPort > 0 (Linux test build only) replaces TUN with a local proxy inbound.
var testMixedPort = 0

type BuildOptions struct {
	RuDirect     bool
	BlockedVPN   bool
	RuleDir      string
	Secret       string
	ClashPort    int
	InitialGroup string
	TunName      string
	// processes that must bypass the tunnel (our app and the bundled Xray)
	BypassProcs []string
}

// BuildSingBox makes the sing-box config: TUN for all traffic, selector "proxy" over two urltest groups.
func BuildSingBox(servers []*Server, opt BuildOptions) (string, error) {
	var regular, lte, serverOutbounds []any
	for _, s := range servers {
		if s.Group == GroupExcluded {
			continue
		}
		serverOutbounds = append(serverOutbounds, s.Outbound)
		if s.Group == GroupLTE {
			lte = append(lte, s.Tag)
		} else {
			regular = append(regular, s.Tag)
		}
	}
	if len(regular) == 0 && len(lte) == 0 {
		return "", fmt.Errorf("в подписках нет подходящих серверов")
	}
	var groups []any
	if len(regular) > 0 {
		groups = append(groups, GroupNameRegular)
	}
	if len(lte) > 0 {
		groups = append(groups, GroupNameLTE)
	}
	def := groups[0]
	for _, g := range groups {
		if g == opt.InitialGroup {
			def = g
		}
	}
	selectable := append(append(append([]any{}, groups...), regular...), lte...)
	outbounds := []any{obj{"type": "selector", "tag": Selector, "outbounds": selectable, "default": def,
		"interrupt_exist_connections": true}}
	if len(regular) > 0 {
		outbounds = append(outbounds, urltest(GroupNameRegular, regular))
	}
	if len(lte) > 0 {
		outbounds = append(outbounds, urltest(GroupNameLTE, lte))
	}
	outbounds = append(outbounds, serverOutbounds...)
	outbounds = append(outbounds, obj{"type": "direct", "tag": "direct"})

	rs := func(tag, file string) obj {
		return obj{"type": "local", "tag": tag, "format": "binary", "path": filepath.Join(opt.RuleDir, file)}
	}
	ruleSets := arr(
		rs("ru-inside", "geosite-ru-available-only-inside.srs"),
		rs("ru-blocked", "geosite-ru-blocked.srs"),
		rs("ru-blocked-ip", "geoip-ru-blocked.srs"),
		rs("geosite-ru", "geosite-category-ru.srs"),
		rs("geoip-ru", "geoip-ru.srs"))
	ruSuffix := arr("ru", "su", "xn--p1ai")

	dnsRules := []any{obj{"rule_set": arr("ru-inside"), "server": "dns-direct"}}
	if opt.BlockedVPN {
		dnsRules = append(dnsRules, obj{"rule_set": arr("ru-blocked"), "server": "dns-remote"})
	}
	if opt.RuDirect {
		dnsRules = append(dnsRules, obj{"domain_suffix": ruSuffix, "server": "dns-direct"},
			obj{"rule_set": arr("geosite-ru"), "server": "dns-direct"})
	}
	dns := obj{
		"servers": arr(
			obj{"type": "udp", "tag": "dns-direct", "server": "77.88.8.8"},
			obj{"type": "https", "tag": "dns-remote", "server": "1.1.1.1", "detour": Selector}),
		"rules":    dnsRules,
		"final":    "dns-remote",
		"strategy": "ipv4_only",
	}

	rules := []any{
		obj{"action": "sniff"},
		obj{"protocol": "dns", "action": "hijack-dns"},
	}
	if len(opt.BypassProcs) > 0 {
		procs := make([]any, len(opt.BypassProcs))
		for i, p := range opt.BypassProcs {
			procs[i] = p
		}
		rules = append(rules, obj{"process_name": procs, "outbound": "direct"})
	}
	rules = append(rules,
		obj{"ip_is_private": true, "outbound": "direct"},
		obj{"rule_set": arr("ru-inside"), "outbound": "direct"})
	if opt.BlockedVPN {
		rules = append(rules, obj{"rule_set": arr("ru-blocked", "ru-blocked-ip"), "outbound": Selector})
	}
	if opt.RuDirect {
		rules = append(rules, obj{"domain_suffix": ruSuffix, "outbound": "direct"},
			obj{"rule_set": arr("geosite-ru", "geoip-ru"), "outbound": "direct"})
	}
	route := obj{
		"rules":                   rules,
		"rule_set":                ruleSets,
		"final":                   Selector,
		"auto_detect_interface":   true,
		"find_process":            len(opt.BypassProcs) > 0,
		"default_domain_resolver": "dns-direct",
	}
	tun := obj{"type": "tun", "tag": "tun-in",
		"address":      arr("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
		"mtu":          9000,
		"auto_route":   true,
		"strict_route": true,
		"stack":        "mixed"}
	if opt.TunName != "" {
		tun["interface_name"] = opt.TunName
	}
	if testMixedPort > 0 {
		tun = obj{"type": "mixed", "tag": "mixed-in", "listen": "127.0.0.1", "listen_port": testMixedPort}
	}
	root := obj{
		"log":       obj{"level": "info", "timestamp": true},
		"dns":       dns,
		"inbounds":  arr(tun),
		"outbounds": outbounds,
		"route":     route,
		"experimental": obj{"clash_api": obj{
			"external_controller": "127.0.0.1:" + strconv.Itoa(opt.ClashPort),
			"secret":              opt.Secret}},
	}
	return jsonStr(root), nil
}

func urltest(tag string, members []any) obj {
	return obj{"type": "urltest", "tag": tag, "outbounds": members, "url": TestURL, "interval": "3m",
		"tolerance": 100, "interrupt_exist_connections": false}
}

/* ---------- several subscriptions ---------- */

type SubEntry struct {
	URL, Name, Body string
}

// MergeSubs parses every subscription, keeps names unique across them and renumbers tags.
func MergeSubs(entries []SubEntry, warnings, stubs *[]string) []*Server {
	var out []*Server
	for _, e := range entries {
		var w []string
		list := ParseSub(e.Body, &w)
		for _, x := range w {
			*warnings = append(*warnings, e.Name+": "+x)
		}
		if stub := stubReason(list); stub != "" {
			*stubs = append(*stubs, e.Name+": «"+stub+"»")
			continue
		}
		for _, s := range list {
			s.Sub = e.Name
			out = append(out, s)
		}
	}
	names := map[string]bool{}
	for i, s := range out {
		base, name := s.Name, s.Name
		for k := 2; names[name]; k++ {
			name = fmt.Sprintf("%s (%d)", base, k)
		}
		s.Name = name
		names[name] = true
		s.Tag = "srv" + strconv.Itoa(i+1)
		s.Outbound["tag"] = s.Tag
	}
	return out
}

// ApplyOff marks servers the user turned off: listed, but left out of the core.
func ApplyOff(list []*Server, off map[string]bool) []*Server {
	for _, s := range list {
		s.OrigGroup = s.Group
		if s.Group != GroupExcluded && off[s.Name] {
			s.Off = true
			s.Group = GroupExcluded
			s.ExcludeReason = "Отключён вручную"
		}
	}
	return list
}

// stubReason: panels answer with fake entries (0.0.0.0, port 1) carrying a message.
func stubReason(list []*Server) string {
	if len(list) == 0 {
		return ""
	}
	for _, s := range list {
		h := s.Host
		p := 443
		if s.Xray == nil {
			h = str(s.Outbound["server"])
			p = numOf(s.Outbound["server_port"])
			if v, ok := s.Outbound["server_port"].(int); ok {
				p = v
			}
		}
		fake := h == "" || h == "0.0.0.0" || (len(h) > 4 && h[:4] == "127.") || h == "::" || p <= 1
		if !fake {
			return ""
		}
	}
	msg := ""
	for _, s := range list {
		if msg != "" {
			msg += " / "
		}
		msg += s.Name
	}
	return msg
}
