//go:build windows

package main

import (
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	singBoxExe = "sing-box.exe"
	xrayExe    = "xray.exe"
)

func bypassProcs() []string { return []string{"xray.exe", "Dash.exe"} }

func hideWindow(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
}

func afterStart(cmd *exec.Cmd) { attachToJob(cmd) }

func killProc(cmd *exec.Cmd) {
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

func machineID() string {
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\Microsoft\Cryptography`, registry.QUERY_VALUE|registry.WOW64_64KEY)
	if err != nil {
		return ""
	}
	defer k.Close()
	v, _, err := k.GetStringValue("MachineGuid")
	if err != nil {
		return ""
	}
	return strings.ReplaceAll(strings.ToLower(v), "-", "")
}

func osVersion() string {
	v := windows.RtlGetVersion()
	return itoa(int(v.MajorVersion)) + "." + itoa(int(v.MinorVersion)) + "." + itoa(int(v.BuildNumber))
}

func deviceModel() string {
	h, _ := os.Hostname()
	if h == "" {
		return "Windows PC"
	}
	return h
}

func dataRoot() string {
	if d := os.Getenv("LOCALAPPDATA"); d != "" {
		return d + `\Dash`
	}
	return `C:\Dash`
}

// system and own processes that make no sense to exclude from the VPN
var skipProcs = map[string]bool{"system": true, "[system process]": true, "registry": true, "smss.exe": true, "csrss.exe": true,
	"wininit.exe": true, "services.exe": true, "lsass.exe": true, "winlogon.exe": true, "svchost.exe": true, "dwm.exe": true,
	"fontdrvhost.exe": true, "conhost.exe": true, "sihost.exe": true, "runtimebroker.exe": true, "dash.exe": true,
	"sing-box.exe": true, "xray.exe": true, "msedgewebview2.exe": true, "explorer.exe": true, "taskhostw.exe": true,
	"ctfmon.exe": true, "dllhost.exe": true, "searchhost.exe": true, "startmenuexperiencehost.exe": true,
	"shellexperiencehost.exe": true, "textinputhost.exe": true, "lsaiso.exe": true, "memory compression": true,
	"spoolsv.exe": true, "wudfhost.exe": true, "audiodg.exe": true, "securityhealthservice.exe": true,
	"smartscreen.exe": true, "searchindexer.exe": true, "wmiprvse.exe": true, "applicationframehost.exe": true,
	"systemsettings.exe": true, "lockapp.exe": true, "useroobebroker.exe": true, "dashost.exe": true, "msmpeng.exe": true,
	"nissrv.exe": true, "sgrmbroker.exe": true, "mpdefendercoreservice.exe": true, "securityhealthsystray.exe": true}

// runningApps lists exe names of running processes (unique, sorted), without system ones.
func runningApps() []string {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return nil
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	seen := map[string]bool{}
	var out []string
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		name := windows.UTF16ToString(e.ExeFile[:])
		low := strings.ToLower(name)
		if name == "" || skipProcs[low] || seen[low] || !strings.HasSuffix(low, ".exe") {
			continue
		}
		seen[low] = true
		out = append(out, name)
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i]) < strings.ToLower(out[j]) })
	return out
}

// swapExe puts the downloaded build in place of the running Dash.exe (a running exe can be renamed, not overwritten).
func swapExe(newExe string) (string, error) {
	self, err := os.Executable()
	if err != nil {
		return "", err
	}
	old := self + ".old"
	_ = os.Remove(old)
	if err := os.Rename(self, old); err != nil {
		return "", err
	}
	if err := copyFile(newExe, self); err != nil {
		_ = os.Remove(self)
		_ = os.Rename(old, self)
		return "", err
	}
	return self, nil
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// cleanupOldExe removes the previous build left after an update.
func cleanupOldExe() {
	if self, err := os.Executable(); err == nil {
		_ = os.Remove(self + ".old")
		_ = os.RemoveAll(filepath.Join(dataRoot(), "update"))
	}
}

// waitPid waits (up to 20 s) for the previous Dash to exit after an update.
func waitPid(pid int) {
	h, err := windows.OpenProcess(windows.SYNCHRONIZE, false, uint32(pid))
	if err != nil {
		return
	}
	defer windows.CloseHandle(h)
	_, _ = windows.WaitForSingleObject(h, 20000)
}
