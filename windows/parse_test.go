package main

import (
	"encoding/json"
	"flag"
	"os"
	"reflect"
	"testing"
)

// testdata/sub-cases.json is shared with the Android SubParser test: both parsers must give the same result.
const casesFile = "../testdata/sub-cases.json"

var update = flag.Bool("update", false, "rewrite expectations in "+casesFile+" from the current parser")

type subCase struct {
	Name     string      `json:"name"`
	Sub      string      `json:"sub"`
	Servers  []subServer `json:"servers"`
	Warnings int         `json:"warnings"`
}

type subServer struct {
	Name     string `json:"name"`
	Group    int    `json:"group"`
	Host     string `json:"host"`
	Outbound any    `json:"outbound"`
	Xray     any    `json:"xray,omitempty"`
}

// normalize turns maps/ints into what json.Unmarshal gives, so values compare with DeepEqual.
func normalize(v any) any {
	if v == nil {
		return nil
	}
	b, _ := json.Marshal(v)
	var out any
	_ = json.Unmarshal(b, &out)
	return out
}

func parseCase(c subCase) subCase {
	var warnings []string
	got := subCase{Name: c.Name, Sub: c.Sub, Servers: []subServer{}}
	for _, s := range ParseSub(c.Sub, &warnings) {
		ss := subServer{Name: s.Name, Group: s.Group, Host: s.Host, Outbound: normalize(s.Outbound)}
		if s.Xray != nil {
			ss.Xray = normalize(s.Xray)
		}
		got.Servers = append(got.Servers, ss)
	}
	got.Warnings = len(warnings)
	return got
}

func TestParseSubCases(t *testing.T) {
	data, err := os.ReadFile(casesFile)
	if err != nil {
		t.Fatal(err)
	}
	var cases []subCase
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	if *update {
		for i := range cases {
			cases[i] = parseCase(cases[i])
		}
		out, _ := json.MarshalIndent(cases, "", " ")
		if err := os.WriteFile(casesFile, append(out, '\n'), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	for _, c := range cases {
		t.Run(c.Name, func(t *testing.T) {
			got := parseCase(c)
			if got.Warnings != c.Warnings {
				t.Errorf("warnings: got %d, want %d", got.Warnings, c.Warnings)
			}
			if len(got.Servers) != len(c.Servers) {
				t.Fatalf("servers: got %d, want %d", len(got.Servers), len(c.Servers))
			}
			for i, want := range c.Servers {
				g := got.Servers[i]
				if g.Name != want.Name || g.Group != want.Group || g.Host != want.Host {
					t.Errorf("server %d: got %q/%d/%q, want %q/%d/%q", i, g.Name, g.Group, g.Host, want.Name, want.Group, want.Host)
				}
				if !reflect.DeepEqual(g.Outbound, want.Outbound) {
					t.Errorf("server %d outbound:\n got %s\nwant %s", i, jsonStr(g.Outbound), jsonStr(want.Outbound))
				}
				if !reflect.DeepEqual(g.Xray, want.Xray) {
					t.Errorf("server %d xray:\n got %s\nwant %s", i, jsonStr(g.Xray), jsonStr(want.Xray))
				}
			}
		})
	}
}

func TestB64(t *testing.T) {
	for in, want := range map[string]string{
		"aGVsbG8=":   "hello",
		"aGVsbG8":    "hello",
		"aGVs\nbG8=": "hello",
		"-_8":        "\xfb\xff",
		"+/8=":       "\xfb\xff",
	} {
		if got, ok := b64(in); !ok || got != want {
			t.Errorf("b64(%q) = %q, %v; want %q", in, got, ok, want)
		}
	}
	if _, ok := b64("not base64!"); ok {
		t.Error("b64 accepted garbage")
	}
}

func TestCleanName(t *testing.T) {
	for in, want := range map[string]string{
		"🇩🇪 Germany 🔥 | Fast": "🇩🇪 Germany | Fast",
		"| Москва |":          "Москва",
		"🔥🔥":                  "🔥🔥",
		"a|b":                 "a | b",
	} {
		if got := cleanName(in); got != want {
			t.Errorf("cleanName(%q) = %q, want %q", in, got, want)
		}
	}
}
