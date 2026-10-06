//go:build windows

package main

import (
	"os"
	"os/exec"
	"strings"
	"syscall"

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
