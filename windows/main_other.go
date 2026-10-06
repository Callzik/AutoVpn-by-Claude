//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/signal"
)

// Test build for Linux: serves the UI and runs the cores from AUTOVPN_BIN.
func main() {
	bin := os.Getenv("AUTOVPN_BIN")
	app, err := NewApp(bin)
	if err != nil {
		fmt.Println(err)
		os.Exit(1)
	}
	done := make(chan struct{})
	app.API.quit = func() { app.Core.Disconnect("Выход"); close(done) }
	fmt.Println(app.URL())
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt)
	select {
	case <-sig:
	case <-done:
	}
	app.Core.Disconnect("Выход")
}
