//go:build windows

package main

import (
	_ "embed"
	"os"
	"os/exec"
	"path/filepath"
	"unsafe"

	"github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
)

//go:embed bin/sing-box.exe
var singBoxBin []byte

//go:embed bin/xray.exe
var xrayBin []byte

var (
	user32           = windows.NewLazySystemDLL("user32.dll")
	procMessageBox   = user32.NewProc("MessageBoxW")
	procFindWindow   = user32.NewProc("FindWindowW")
	procShowWindow   = user32.NewProc("ShowWindow")
	procSetForegroun = user32.NewProc("SetForegroundWindow")
)

func msgBox(text string) {
	t, _ := windows.UTF16PtrFromString(text)
	c, _ := windows.UTF16PtrFromString("Dash")
	procMessageBox.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x40)
}

// one copy only: a second launch brings the running window forward
func singleInstance() bool {
	name, _ := windows.UTF16PtrFromString("Local\\Dash-by-Claude")
	_, err := windows.CreateMutex(nil, false, name)
	if err == windows.ERROR_ALREADY_EXISTS {
		cls, _ := windows.UTF16PtrFromString("webview")
		title, _ := windows.UTF16PtrFromString("Dash")
		if h, _, _ := procFindWindow.Call(uintptr(unsafe.Pointer(cls)), uintptr(unsafe.Pointer(title))); h != 0 {
			procShowWindow.Call(h, 9) // SW_RESTORE
			procSetForegroun.Call(h)
		}
		return false
	}
	return true
}

// writeIfChanged extracts a bundled core unless the same file is already there.
func writeIfChanged(path string, data []byte) error {
	if st, err := os.Stat(path); err == nil && st.Size() == int64(len(data)) {
		return nil
	}
	tmp := path + ".new"
	if err := os.WriteFile(tmp, data, 0700); err != nil {
		return err
	}
	_ = os.Remove(path)
	return os.Rename(tmp, path)
}

/* child processes die together with Dash, even if it is killed */

var job windows.Handle

func initJob() {
	h, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	if _, err := windows.SetInformationJobObject(h, windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info))); err != nil {
		windows.CloseHandle(h)
		return
	}
	job = h
}

func attachToJob(cmd *exec.Cmd) {
	if job == 0 || cmd.Process == nil {
		return
	}
	ph, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(cmd.Process.Pid))
	if err != nil {
		return
	}
	defer windows.CloseHandle(ph)
	_ = windows.AssignProcessToJobObject(job, ph)
}

func main() {
	if !singleInstance() {
		return
	}
	if !windows.GetCurrentProcessToken().IsElevated() {
		msgBox("Dash нужен запуск от имени администратора: без этого Windows не даёт создать VPN-подключение.")
		return
	}
	initJob()
	binDir := filepath.Join(dataRoot(), "bin", Version)
	if err := os.MkdirAll(binDir, 0700); err == nil {
		err1 := writeIfChanged(filepath.Join(binDir, singBoxExe), singBoxBin)
		err2 := writeIfChanged(filepath.Join(binDir, xrayExe), xrayBin)
		if err1 != nil || err2 != nil {
			msgBox("Не удалось распаковать ядро в " + binDir + ". Возможно, его заблокировал антивирус.")
			return
		}
	}
	app, err := NewApp(binDir)
	if err != nil {
		msgBox("Не удалось запустить: " + err.Error())
		return
	}
	done := make(chan struct{})
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		AutoFocus: true,
		DataPath:  filepath.Join(app.Dir, "webview"),
		WindowOptions: webview2.WindowOptions{
			Title: "Dash", Width: 440, Height: 820, IconId: 2, Center: true,
		},
	})
	if w == nil {
		// no WebView2 runtime: use the browser, quit from the page
		app.API.quit = func() { close(done) }
		_ = exec.Command("rundll32", "url.dll,FileProtocolHandler", app.URL()).Start()
		<-done
		app.Core.Disconnect("Выход")
		return
	}
	app.API.quit = func() { w.Dispatch(func() { w.Terminate() }) }
	w.SetSize(440, 820, webview2.HintMin)
	w.Navigate(app.URL())
	w.Run()
	app.Core.Disconnect("Выход")
	w.Destroy()
}
