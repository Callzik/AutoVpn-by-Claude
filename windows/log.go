package main

import (
	"strings"
	"sync"
	"time"
)

// Log keeps the last lines for the journal screen.
type Log struct {
	mu    sync.Mutex
	lines []string
}

func (l *Log) Add(s string) {
	s = strings.TrimSpace(s)
	if s == "" {
		return
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	l.lines = append(l.lines, time.Now().Format("15:04:05")+"  "+s)
	if len(l.lines) > 500 {
		l.lines = l.lines[len(l.lines)-500:]
	}
}

func (l *Log) Text() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	var sb strings.Builder
	for i := len(l.lines) - 1; i >= 0; i-- {
		sb.WriteString(l.lines[i])
		sb.WriteByte('\n')
	}
	return sb.String()
}
