//go:build windows

package main

import (
	"crypto/sha256"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Dash runs elevated, so everything it executes or feeds to the cores must be out of reach of
// ordinary (non-admin) processes of the same user. Its data lives in %ProgramData%\Dash,
// owned by Administrators with a protected DACL: SYSTEM and Administrators full control,
// OWNER RIGHTS limited to READ_CONTROL so the implicit owner rights (WRITE_DAC) never apply
// even when a file ends up owned by the user's own SID. A non-elevated token has Administrators
// as deny-only, so it can neither read nor change anything inside.
const adminOnlySDDL = "O:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;0x20000;;;OW)"

func dataRoot() string {
	pd := os.Getenv("ProgramData")
	if pd == "" {
		pd = `C:\ProgramData`
	}
	return filepath.Join(pd, "Dash")
}

func oldDataRoot() string {
	if d := os.Getenv("LOCALAPPDATA"); d != "" {
		return d + `\Dash`
	}
	return ""
}

func isReparse(path string) bool {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return true
	}
	a, err := windows.GetFileAttributes(p)
	if err != nil {
		return false
	}
	return a&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0
}

// trustedDir reports whether dir is a real directory owned by Administrators or SYSTEM
// with a protected DACL, i.e. created by Dash and not planted by someone else.
func trustedDir(dir string) bool {
	if isReparse(dir) {
		return false
	}
	if st, err := os.Lstat(dir); err != nil || !st.IsDir() {
		return false
	}
	sd, err := windows.GetNamedSecurityInfo(dir, windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return false
	}
	owner, _, err := sd.Owner()
	if err != nil || owner == nil {
		return false
	}
	if !owner.IsWellKnown(windows.WinBuiltinAdministratorsSid) && !owner.IsWellKnown(windows.WinLocalSystemSid) {
		return false
	}
	ctl, _, err := sd.Control()
	return err == nil && ctl&windows.SE_DACL_PROTECTED != 0
}

// prepareDataRoot makes sure %ProgramData%\Dash exists with the admin-only ACL.
// Anything else found at that path (a junction, a file, a folder created by another account)
// is moved aside, never followed or reused.
func prepareDataRoot() error {
	root := dataRoot()
	if _, err := os.Lstat(root); err == nil && !trustedDir(root) {
		if isReparse(root) {
			if err := os.Remove(root); err != nil { // removes the link itself, not its target
				return err
			}
		} else {
			aside := root + ".untrusted-" + strconv.FormatInt(time.Now().Unix(), 10)
			if err := os.Rename(root, aside); err != nil {
				return err
			}
		}
	}
	if _, err := os.Lstat(root); err != nil {
		sd, err := windows.SecurityDescriptorFromString(adminOnlySDDL)
		if err != nil {
			return err
		}
		sa := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}
		p, _ := windows.UTF16PtrFromString(root)
		if err := windows.CreateDirectory(p, &sa); err != nil {
			return err
		}
		if !trustedDir(root) {
			return errors.New("не удалось защитить папку " + root)
		}
		migrateOldData(root)
	}
	return nil
}

// migrateOldData copies settings and cached subscriptions from the old per-user folder once.
func migrateOldData(root string) {
	old := oldDataRoot()
	if old == "" {
		return
	}
	cp := func(rel string) {
		src := filepath.Join(old, rel)
		if isReparse(src) {
			return
		}
		b, err := os.ReadFile(src)
		if err != nil || len(b) > 8<<20 {
			return
		}
		dst := filepath.Join(root, rel)
		_ = os.MkdirAll(filepath.Dir(dst), 0700)
		_ = os.WriteFile(dst, b, 0600)
	}
	cp("settings.json")
	cp("tray-hint")
	if ents, err := os.ReadDir(filepath.Join(old, "subs")); err == nil {
		for _, e := range ents {
			if !e.IsDir() && strings.HasSuffix(e.Name(), ".txt") {
				cp(filepath.Join("subs", e.Name()))
			}
		}
	}
}

/* ---------- cores are checked against the copies built into Dash.exe right before each start ---------- */

var (
	coreMu   sync.Mutex
	coreSums = map[string][32]byte{}
)

func registerCore(path string, data []byte) {
	coreMu.Lock()
	coreSums[strings.ToLower(filepath.Clean(path))] = sha256.Sum256(data)
	coreMu.Unlock()
}

// lockVerified opens path so nobody can write, rename or delete it while the handle is open,
// and checks its SHA-256. The caller starts the process, then closes the handle.
func lockVerified(path string, want [32]byte) (windows.Handle, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0, err
	}
	h, err := windows.CreateFile(p, windows.GENERIC_READ, windows.FILE_SHARE_READ, nil,
		windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL|windows.FILE_FLAG_OPEN_REPARSE_POINT, 0)
	if err != nil {
		return 0, err
	}
	f := os.NewFile(uintptr(h), path)
	hs := sha256.New()
	_, err = io.Copy(hs, f)
	if err == nil {
		_, err = f.Seek(0, io.SeekStart)
	}
	var got [32]byte
	copy(got[:], hs.Sum(nil))
	if err != nil || got != want {
		f.Close()
		if err == nil {
			err = errors.New("файл изменён посторонней программой")
		}
		return 0, err
	}
	// keep the handle open: os.File must not close it when garbage-collected
	dup, derr := dupHandle(h)
	f.Close()
	if derr != nil {
		return 0, derr
	}
	return dup, nil
}

func dupHandle(h windows.Handle) (windows.Handle, error) {
	var out windows.Handle
	self := windows.CurrentProcess()
	err := windows.DuplicateHandle(self, h, self, &out, 0, false, windows.DUPLICATE_SAME_ACCESS)
	return out, err
}

// verifyCore locks a bundled core and checks it before it is started; release() unlocks it.
func verifyCore(bin string) (func(), error) {
	coreMu.Lock()
	want, ok := coreSums[strings.ToLower(filepath.Clean(bin))]
	coreMu.Unlock()
	if !ok {
		return nil, errors.New("неизвестное ядро " + filepath.Base(bin))
	}
	h, err := lockVerified(bin, want)
	if err != nil {
		return nil, errors.New(filepath.Base(bin) + ": " + err.Error())
	}
	return func() { windows.CloseHandle(h) }, nil
}

func fileSum(path string) ([32]byte, error) {
	var out [32]byte
	f, err := os.Open(path)
	if err != nil {
		return out, err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return out, err
	}
	copy(out[:], h.Sum(nil))
	return out, nil
}
