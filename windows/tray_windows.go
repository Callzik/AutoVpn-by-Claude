//go:build windows

package main

import (
	"os"
	"path/filepath"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Closing the window hides Dash to the notification area; the VPN keeps running.
// Real exit: "Выход" in the tray menu or "Отключить и выйти" on the page.

const (
	wmNull          = 0x0000
	wmClose         = 0x0010
	wmContextMenu   = 0x007B
	wmLButtonUp     = 0x0202
	wmLButtonDblClk = 0x0203
	wmRButtonUp     = 0x0205
	wmTray          = 0x8001 // WM_APP+1; WM_APP itself is webview2's dispatch message

	nimAdd    = 0
	nimModify = 1
	nimDelete = 2
	nifMsg    = 0x01
	nifIcon   = 0x02
	nifTip    = 0x04
	nifInfo   = 0x10
	niifInfo  = 0x01

	swHide    = 0
	swShow    = 5
	swRestore = 9

	mfString    = 0x0000
	mfSeparator = 0x0800
	tpmReturn   = 0x0100
	tpmNoNotify = 0x0080
	tpmRightBtn = 0x0002

	idOpen   = 1
	idToggle = 2
	idQuit   = 3
)

var (
	shell32              = windows.NewLazySystemDLL("shell32.dll")
	procShellNotifyIcon  = shell32.NewProc("Shell_NotifyIconW")
	procSetWindowLongPtr = user32.NewProc("SetWindowLongPtrW")
	procCallWindowProc   = user32.NewProc("CallWindowProcW")
	procIsIconic         = user32.NewProc("IsIconic")
	procLoadImage        = user32.NewProc("LoadImageW")
	procGetSysMetrics    = user32.NewProc("GetSystemMetrics")
	procCreatePopupMenu  = user32.NewProc("CreatePopupMenu")
	procAppendMenu       = user32.NewProc("AppendMenuW")
	procSetMenuDefault   = user32.NewProc("SetMenuDefaultItem")
	procTrackPopupMenu   = user32.NewProc("TrackPopupMenu")
	procDestroyMenu      = user32.NewProc("DestroyMenu")
	procGetCursorPos     = user32.NewProc("GetCursorPos")
	procPostMessage      = user32.NewProc("PostMessageW")
	procRegisterWinMsg   = user32.NewProc("RegisterWindowMessageW")
)

type notifyIconData struct {
	CbSize           uint32
	HWnd             uintptr
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         windows.GUID
	HBalloonIcon     uintptr
}

type Tray struct {
	mu        sync.Mutex
	hwnd      uintptr
	app       *App
	quit      func()
	nid       notifyIconData
	tip       string
	oldProc   uintptr
	recreated uint32 // TaskbarCreated: Explorer restarted, icon must be added again
	stop      chan struct{}
}

var tray *Tray // the window procedure is a plain callback, it needs a global

func copyUTF16(dst []uint16, s string) {
	u, _ := windows.UTF16FromString(s)
	if len(u) > len(dst) {
		u = u[:len(dst)-1]
		u = append(u, 0)
	}
	for i := range dst {
		dst[i] = 0
	}
	copy(dst, u)
}

func newTray(hwnd uintptr, app *App, quit func()) *Tray {
	t := &Tray{hwnd: hwnd, app: app, quit: quit, stop: make(chan struct{})}
	var hinst windows.Handle
	_ = windows.GetModuleHandleEx(0, nil, &hinst)
	cx, _, _ := procGetSysMetrics.Call(49)                                 // SM_CXSMICON
	cy, _, _ := procGetSysMetrics.Call(50)                                 // SM_CYSMICON
	icon, _, _ := procLoadImage.Call(uintptr(hinst), 2, 1, cx, cy, 0x8000) // IDI 2, IMAGE_ICON, LR_SHARED
	t.nid.CbSize = uint32(unsafe.Sizeof(t.nid))
	t.nid.HWnd = hwnd
	t.nid.UID = 1
	t.nid.UFlags = nifMsg | nifIcon | nifTip
	t.nid.UCallbackMessage = wmTray
	t.nid.HIcon = icon
	t.tip = tipText(app.Core.View())
	copyUTF16(t.nid.SzTip[:], t.tip)
	procShellNotifyIcon.Call(nimAdd, uintptr(unsafe.Pointer(&t.nid)))

	name, _ := windows.UTF16PtrFromString("TaskbarCreated")
	r, _, _ := procRegisterWinMsg.Call(uintptr(unsafe.Pointer(name)))
	t.recreated = uint32(r)

	tray = t
	// GWLP_WNDPROC = -4
	t.oldProc, _, _ = procSetWindowLongPtr.Call(hwnd, ^uintptr(3), windows.NewCallback(trayWndProc))
	go t.watch()
	return t
}

func tipText(v StateView) string {
	switch v.State {
	case StOn:
		if v.Server != "" {
			return "Dash — подключено · " + v.Server
		}
		return "Dash — подключено"
	case StConnecting:
		return "Dash — подключение…"
	case StWaiting:
		return "Dash — нет ответа от серверов"
	}
	return "Dash — отключено"
}

// watch keeps the tooltip in sync with the VPN state.
func (t *Tray) watch() {
	tk := time.NewTicker(2 * time.Second)
	defer tk.Stop()
	for {
		select {
		case <-t.stop:
			return
		case <-tk.C:
			s := tipText(t.app.Core.View())
			t.mu.Lock()
			if s != t.tip {
				t.tip = s
				t.nid.UFlags = nifTip
				copyUTF16(t.nid.SzTip[:], s)
				procShellNotifyIcon.Call(nimModify, uintptr(unsafe.Pointer(&t.nid)))
			}
			t.mu.Unlock()
		}
	}
}

func (t *Tray) Remove() {
	close(t.stop)
	t.mu.Lock()
	defer t.mu.Unlock()
	procShellNotifyIcon.Call(nimDelete, uintptr(unsafe.Pointer(&t.nid)))
}

func (t *Tray) readd() {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.nid.UFlags = nifMsg | nifIcon | nifTip
	procShellNotifyIcon.Call(nimAdd, uintptr(unsafe.Pointer(&t.nid)))
}

// balloon shows a one-time hint the first time the window goes to the tray.
func (t *Tray) hintOnce() {
	mark := filepath.Join(t.app.Dir, "tray-hint")
	if _, err := os.Stat(mark); err == nil {
		return
	}
	_ = os.WriteFile(mark, []byte("1"), 0600)
	t.mu.Lock()
	defer t.mu.Unlock()
	t.nid.UFlags = nifInfo
	t.nid.DwInfoFlags = niifInfo
	copyUTF16(t.nid.SzInfoTitle[:], "Dash работает в фоне")
	copyUTF16(t.nid.SzInfo[:], "VPN не отключён. Открыть — щелчок по значку, выйти — правой кнопкой → «Выход».")
	procShellNotifyIcon.Call(nimModify, uintptr(unsafe.Pointer(&t.nid)))
}

func (t *Tray) Show() {
	procShowWindow.Call(t.hwnd, swShow)
	if r, _, _ := procIsIconic.Call(t.hwnd); r != 0 {
		procShowWindow.Call(t.hwnd, swRestore)
	}
	procSetForegroun.Call(t.hwnd)
}

func (t *Tray) menu() {
	v := t.app.Core.View()
	m, _, _ := procCreatePopupMenu.Call()
	if m == 0 {
		return
	}
	add := func(id uintptr, text string) {
		p, _ := windows.UTF16PtrFromString(text)
		procAppendMenu.Call(m, mfString, id, uintptr(unsafe.Pointer(p)))
	}
	add(idOpen, "Открыть Dash")
	if v.State == StOff {
		add(idToggle, "Подключить")
	} else {
		add(idToggle, "Отключить")
	}
	procAppendMenu.Call(m, mfSeparator, 0, 0)
	add(idQuit, "Выход")
	procSetMenuDefault.Call(m, idOpen, 0)

	var pt struct{ X, Y int32 }
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	procSetForegroun.Call(t.hwnd) // otherwise the menu does not close on an outside click
	cmd, _, _ := procTrackPopupMenu.Call(m, tpmReturn|tpmNoNotify|tpmRightBtn,
		uintptr(pt.X), uintptr(pt.Y), 0, t.hwnd, 0)
	procPostMessage.Call(t.hwnd, wmNull, 0, 0)
	procDestroyMenu.Call(m)

	switch cmd {
	case idOpen:
		t.Show()
	case idToggle:
		if v.State != StOff {
			go t.app.Core.Disconnect("Отключено из трея")
		} else if !v.HasSubs {
			t.Show() // nothing to connect to: let the page ask for a subscription
		} else {
			go t.app.Core.Connect()
		}
	case idQuit:
		t.quit()
	}
}

func trayWndProc(hwnd, msg, wp, lp uintptr) uintptr {
	t := tray
	switch {
	case msg == wmClose:
		procShowWindow.Call(hwnd, swHide)
		t.hintOnce()
		return 0
	case msg == wmTray:
		switch lp & 0xFFFF {
		case wmLButtonUp, wmLButtonDblClk:
			t.Show()
		case wmRButtonUp, wmContextMenu:
			t.menu()
		}
		return 0
	case t.recreated != 0 && uint32(msg) == t.recreated:
		t.readd()
	}
	r, _, _ := procCallWindowProc.Call(t.oldProc, hwnd, msg, wp, lp)
	return r
}
