// sbhelper runs sing-box (via libbox) as a child process of the Android app.
//
// The app owns the VpnService. This process asks the app for the TUN file
// descriptor and the network interface list over a unix socket, and receives
// default-interface updates and the stop command on stdin.
//
// Usage: sbhelper <socket path> <config path> <work dir>
package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/experimental/libbox"
)

var sockPath string

/* ---------- iterators ---------- */

type strIter struct {
	v []string
	i int
}

func (s *strIter) Len() int32    { return int32(len(s.v)) }
func (s *strIter) HasNext() bool { return s.i < len(s.v) }
func (s *strIter) Next() string  { x := s.v[s.i]; s.i++; return x }

type ifIter struct {
	v []*libbox.NetworkInterface
	i int
}

func (s *ifIter) HasNext() bool                  { return s.i < len(s.v) }
func (s *ifIter) Next() *libbox.NetworkInterface { x := s.v[s.i]; s.i++; return x }

func prefixes(it libbox.RoutePrefixIterator) []string {
	var out []string
	if it == nil {
		return out
	}
	for it.HasNext() {
		out = append(out, it.Next().String())
	}
	return out
}

/* ---------- app socket ---------- */

func request(req map[string]any) (map[string]any, int, error) {
	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: sockPath, Net: "unix"})
	if err != nil {
		return nil, -1, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(15 * time.Second))
	body, _ := json.Marshal(req)
	if _, err = conn.Write(append(body, '\n')); err != nil {
		return nil, -1, err
	}
	var data []byte
	fd := -1
	buf := make([]byte, 64*1024)
	oob := make([]byte, syscall.CmsgSpace(4*4))
	for {
		n, oobn, _, _, rerr := conn.ReadMsgUnix(buf, oob)
		if oobn > 0 {
			msgs, perr := syscall.ParseSocketControlMessage(oob[:oobn])
			if perr == nil {
				for i := range msgs {
					fds, ferr := syscall.ParseUnixRights(&msgs[i])
					if ferr == nil && len(fds) > 0 && fd < 0 {
						fd = fds[0]
					}
				}
			}
		}
		data = append(data, buf[:n]...)
		if bytes.IndexByte(data, '\n') >= 0 || rerr != nil || n == 0 {
			break
		}
	}
	var resp map[string]any
	if err = json.Unmarshal(bytes.TrimSpace(data), &resp); err != nil {
		return nil, fd, fmt.Errorf("bad reply %q: %w", string(data), err)
	}
	if e, ok := resp["error"].(string); ok && e != "" {
		return resp, fd, errors.New(e)
	}
	return resp, fd, nil
}

/* ---------- platform ---------- */

type platform struct {
	mu        sync.Mutex
	listener  libbox.InterfaceUpdateListener
	defName   string
	defIndex  int32
	defMetered bool
	hasDef    bool
}

func (p *platform) LocalDNSTransport() libbox.LocalDNSTransport { return nil }
func (p *platform) UsePlatformAutoDetectInterfaceControl() bool  { return true }

// The app excludes itself from the VPN, so sockets of this process already bypass the tunnel.
func (p *platform) AutoDetectInterfaceControl(fd int32) error { return nil }

func (p *platform) OpenTun(options libbox.TunOptions) (int32, error) {
	req := map[string]any{
		"m":       "openTun",
		"mtu":     options.GetMTU(),
		"inet4":   prefixes(options.GetInet4Address()),
		"inet6":   prefixes(options.GetInet6Address()),
		"routes4": prefixes(options.GetInet4RouteRange()),
		"routes6": prefixes(options.GetInet6RouteRange()),
	}
	if dns, err := options.GetDNSServerAddress(); err == nil && dns != nil {
		req["dns"] = dns.Value
	}
	_, fd, err := request(req)
	if err != nil {
		return -1, err
	}
	if fd < 0 {
		return -1, errors.New("app did not send tun fd")
	}
	return int32(fd), nil
}

func (p *platform) WriteLog(message string) { fmt.Println(message) }
func (p *platform) UseProcFS() bool          { return false }
func (p *platform) FindConnectionOwner(int32, string, int32, string, int32) (int32, error) {
	return -1, errors.New("unsupported")
}
func (p *platform) PackageNameByUid(int32) (string, error)   { return "", errors.New("unsupported") }
func (p *platform) UIDByPackageName(string) (int32, error)  { return -1, errors.New("unsupported") }
func (p *platform) UnderNetworkExtension() bool              { return false }
func (p *platform) IncludeAllNetworks() bool                 { return false }
func (p *platform) ReadWIFIState() *libbox.WIFIState         { return nil }
func (p *platform) SystemCertificates() libbox.StringIterator { return &strIter{} }
func (p *platform) ClearDNSCache()                           {}
func (p *platform) SendNotification(*libbox.Notification) error { return nil }

func (p *platform) StartDefaultInterfaceMonitor(listener libbox.InterfaceUpdateListener) error {
	p.mu.Lock()
	p.listener = listener
	has, name, idx, metered := p.hasDef, p.defName, p.defIndex, p.defMetered
	p.mu.Unlock()
	if has {
		listener.UpdateDefaultInterface(name, idx, metered, false)
	}
	return nil
}

func (p *platform) CloseDefaultInterfaceMonitor(libbox.InterfaceUpdateListener) error {
	p.mu.Lock()
	p.listener = nil
	p.mu.Unlock()
	return nil
}

func (p *platform) GetInterfaces() (libbox.NetworkInterfaceIterator, error) {
	resp, _, err := request(map[string]any{"m": "interfaces"})
	if err != nil {
		return nil, err
	}
	var list []*libbox.NetworkInterface
	raw, _ := resp["list"].([]any)
	for _, item := range raw {
		m, ok := item.(map[string]any)
		if !ok {
			continue
		}
		ni := &libbox.NetworkInterface{
			Index:   int32(num(m["index"])),
			MTU:     int32(num(m["mtu"])),
			Name:    str(m["name"]),
			Flags:   int32(num(m["flags"])),
			Type:    int32(num(m["type"])),
			Metered: m["metered"] == true,
		}
		ni.Addresses = &strIter{v: strs(m["addrs"])}
		ni.DNSServer = &strIter{v: strs(m["dns"])}
		list = append(list, ni)
	}
	return &ifIter{v: list}, nil
}

func (p *platform) setDefault(name string, index int32, metered bool) {
	p.mu.Lock()
	p.defName, p.defIndex, p.defMetered, p.hasDef = name, index, metered, true
	l := p.listener
	p.mu.Unlock()
	if l != nil {
		l.UpdateDefaultInterface(name, index, metered, false)
	}
}

func num(v any) float64 {
	f, _ := v.(float64)
	return f
}

func str(v any) string {
	s, _ := v.(string)
	return s
}

func strs(v any) []string {
	raw, _ := v.([]any)
	var out []string
	for _, x := range raw {
		if s, ok := x.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

/* ---------- main ---------- */

func main() {
	if len(os.Args) < 4 {
		fmt.Fprintln(os.Stderr, "usage: sbhelper <socket> <config> <workdir>")
		os.Exit(64)
	}
	sockPath = os.Args[1]
	configPath := os.Args[2]
	workDir := os.Args[3]

	if err := libbox.Setup(&libbox.SetupOptions{
		BasePath:    workDir,
		WorkingPath: workDir,
		TempPath:    workDir + "/tmp",
	}); err != nil {
		fmt.Println("FATAL setup:", err)
		os.Exit(2)
	}

	p := &platform{}
	stop := make(chan struct{}, 1)

	// stdin: "iface <name> <index> <metered 0|1>", "noiface", "stop"
	go func() {
		sc := bufio.NewScanner(os.Stdin)
		for sc.Scan() {
			f := strings.Fields(sc.Text())
			if len(f) == 0 {
				continue
			}
			switch f[0] {
			case "iface":
				if len(f) >= 4 {
					idx, _ := strconv.Atoi(f[2])
					p.setDefault(f[1], int32(idx), f[3] == "1")
				}
			case "noiface":
				p.setDefault("", -1, false)
			case "stop":
				stop <- struct{}{}
				return
			}
		}
		stop <- struct{}{} // app went away
	}()

	content, err := os.ReadFile(configPath)
	if err != nil {
		fmt.Println("FATAL read config:", err)
		os.Exit(2)
	}
	svc, err := libbox.NewService(string(content), p)
	if err != nil {
		fmt.Println("FATAL create:", err)
		os.Exit(3)
	}
	if err = svc.Start(); err != nil {
		fmt.Println("FATAL start:", err)
		os.Exit(4)
	}
	fmt.Println("STARTED")

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT)
	select {
	case <-stop:
	case <-sig:
	}
	_ = svc.Close()
	fmt.Println("STOPPED")
}
