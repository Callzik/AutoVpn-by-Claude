package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Clash talks to the sing-box Clash API on localhost.
type Clash struct {
	base, secret string
	hc           *http.Client
}

func NewClash(port int, secret string) *Clash {
	return &Clash{base: "http://127.0.0.1:" + strconv.Itoa(port), secret: secret,
		hc: &http.Client{Transport: &http.Transport{Proxy: nil, DisableKeepAlives: true}}}
}

func (c *Clash) call(method, path string, body any, timeout time.Duration) ([]byte, error) {
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = strings.NewReader(string(b))
	}
	req, err := http.NewRequest(method, c.base+path, rd)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.secret)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	cl := *c.hc
	cl.Timeout = timeout
	resp, err := cl.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		return b, fmt.Errorf("HTTP %d %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	return b, nil
}

func esc(s string) string { return url.PathEscape(s) }

func (c *Clash) Alive() bool {
	_, err := c.call("GET", "/", nil, 2*time.Second)
	return err == nil
}

func (c *Clash) Select(selector, name string) error {
	_, err := c.call("PUT", "/proxies/"+esc(selector), map[string]string{"name": name}, 4*time.Second)
	return err
}

func (c *Clash) proxy(tag string) (map[string]any, error) {
	b, err := c.call("GET", "/proxies/"+esc(tag), nil, 4*time.Second)
	if err != nil {
		return nil, err
	}
	var o map[string]any
	err = json.Unmarshal(b, &o)
	return o, err
}

// Now: member currently chosen by a selector or urltest group.
func (c *Clash) Now(group string) string {
	o, err := c.proxy(group)
	if err != nil {
		return ""
	}
	return str(o["now"])
}

func (c *Clash) Members(group string) []string {
	o, err := c.proxy(group)
	if err != nil {
		return nil
	}
	var out []string
	for _, x := range l(o["all"]) {
		out = append(out, str(x))
	}
	return out
}

// LastDelay of one outbound, -1 when unknown or failed.
func (c *Clash) LastDelay(tag string) int {
	o, err := c.proxy(tag)
	if err != nil {
		return -1
	}
	h := l(o["history"])
	if len(h) == 0 {
		return -1
	}
	d := numOf(m(h[len(h)-1])["delay"])
	if d <= 0 {
		return -1
	}
	return d
}

// Delay measures one outbound now, -1 on failure.
func (c *Clash) Delay(tag string, timeoutMs int) int {
	path := "/proxies/" + esc(tag) + "/delay?url=" + url.QueryEscape(TestURL) + "&timeout=" + strconv.Itoa(timeoutMs)
	b, err := c.call("GET", path, nil, time.Duration(timeoutMs+4000)*time.Millisecond)
	if err != nil {
		return -1
	}
	var o map[string]any
	if json.Unmarshal(b, &o) != nil {
		return -1
	}
	d := numOf(o["delay"])
	if d <= 0 {
		return -1
	}
	return d
}

// TestTags measures outbounds in parallel, reporting each result as it arrives.
func (c *Clash) TestTags(tags []string, timeoutMs int, cb func(tag string, delay int)) {
	sem := make(chan struct{}, 48)
	var wg sync.WaitGroup
	for _, t := range tags {
		wg.Add(1)
		sem <- struct{}{}
		go func(tag string) {
			defer wg.Done()
			defer func() { <-sem }()
			cb(tag, c.Delay(tag, timeoutMs))
		}(t)
	}
	wg.Wait()
}

// TestGroup measures every member of a group; returns tag → delay for those that answered.
func (c *Clash) TestGroup(group string, timeoutMs int) map[string]int {
	res := map[string]int{}
	var mu sync.Mutex
	c.TestTags(c.Members(group), timeoutMs, func(tag string, d int) {
		if d > 0 {
			mu.Lock()
			res[tag] = d
			mu.Unlock()
		}
	})
	return res
}

// GroupCheck makes a urltest group re-test and re-select.
func (c *Clash) GroupCheck(group string, timeoutMs int) map[string]int {
	path := "/group/" + esc(group) + "/delay?url=" + url.QueryEscape(TestURL) + "&timeout=" + strconv.Itoa(timeoutMs)
	b, err := c.call("GET", path, nil, time.Duration(timeoutMs+20000)*time.Millisecond)
	res := map[string]int{}
	if err != nil {
		return res
	}
	var o map[string]any
	_ = json.Unmarshal(b, &o)
	for k, v := range o {
		if d := numOf(v); d > 0 {
			res[k] = d
		}
	}
	return res
}

func (c *Clash) CloseAll() { _, _ = c.call("DELETE", "/connections", nil, 4*time.Second) }
