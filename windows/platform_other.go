//go:build !windows

package main

import (
	"os"
	"strconv"
	"os/exec"
	"path/filepath"
)

const (
	singBoxExe = "sing-box"
	xrayExe    = "xray"
)

func bypassProcs() []string { return nil }

func init() {
	if u := os.Getenv("DASH_TEST_URL"); u != "" {
		TestURL = u
	}
	if p := os.Getenv("DASH_TEST_MIXED"); p != "" {
		testMixedPort, _ = strconv.Atoi(p)
	}
}

func hideWindow(cmd *exec.Cmd) {}

func afterStart(cmd *exec.Cmd) {}

func killProc(cmd *exec.Cmd) {
	if cmd != nil && cmd.Process != nil {
		_ = cmd.Process.Kill()
	}
}

func machineID() string { return "" }
func osVersion() string  { return "linux" }
func deviceModel() string {
	h, _ := os.Hostname()
	return h
}
func dataRoot() string {
	if d := os.Getenv("DASH_DIR"); d != "" {
		return d
	}
	return filepath.Join(os.TempDir(), "dash-test")
}
