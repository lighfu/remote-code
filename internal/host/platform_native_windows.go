//go:build windows

package host

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/Microsoft/go-winio"
	"github.com/UserExistsError/conpty"
	"golang.org/x/sys/windows"
)

func platformPipeName(scope, digest string) string {
	return `\\.\pipe\remote-code-` + scope + "-" + digest
}
func platformListenPipe(path string) (net.Listener, error) {
	return winio.ListenPipe(path, &winio.PipeConfig{SecurityDescriptor: "D:P(A;;GA;;;SY)(A;;GA;;;OW)", InputBufferSize: 8192, OutputBufferSize: 8192})
}
func platformDialPipe(path string) (net.Conn, error) {
	timeout := 2 * time.Second
	return winio.DialPipe(path, &timeout)
}
func platformCommand(ctx context.Context, path string, args ...string) *exec.Cmd {
	c := exec.CommandContext(ctx, path, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return c
}
func HostCommand(ctx context.Context, path string, args ...string) *exec.Cmd {
	return platformCommand(ctx, path, args...)
}
func platformDetached(path string, args ...string) *exec.Cmd {
	c := exec.Command(path, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: windows.CREATE_NEW_PROCESS_GROUP | windows.DETACHED_PROCESS}
	return c
}
func platformKillProcess(pid int) error {
	if pid <= 0 || pid == os.Getpid() {
		return errors.New("invalid process target")
	}
	p, e := windows.OpenProcess(windows.PROCESS_TERMINATE, false, uint32(pid))
	if e != nil {
		return e
	}
	defer windows.CloseHandle(p)
	return windows.TerminateProcess(p, 1)
}
func platformIsProcess(pid int, name string) bool {
	p, e := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, uint32(pid))
	if e != nil {
		return false
	}
	defer windows.CloseHandle(p)
	var buf [32768]uint16
	n := uint32(len(buf))
	if windows.QueryFullProcessImageName(p, 0, &buf[0], &n) != nil {
		return false
	}
	parts := strings.Split(windows.UTF16ToString(buf[:n]), `\`)
	return strings.EqualFold(parts[len(parts)-1], name)
}
func platformPTY(shell, cwd string, cols, rows int) (terminalPTY, error) {
	p, err := conpty.Start(syscall.EscapeArg(shell)+" -NoLogo", conpty.ConPtyWorkDir(cwd), conpty.ConPtyDimensions(cols, rows), conpty.ConPtyEnv(os.Environ()))
	if err != nil {
		return nil, err
	}
	return &platformSafePTY{ConPty: p}, nil
}

type platformSafePTY struct {
	*conpty.ConPty
	once     sync.Once
	closeErr error
}

func (p *platformSafePTY) Close() error {
	p.once.Do(func() { p.closeErr = p.ConPty.Close() })
	return p.closeErr
}

type platformProcess struct {
	PID, Parent   uint32
	Name, Command string
}

func platformProcesses() ([]platformProcess, error) {
	h, e := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if e != nil {
		return nil, e
	}
	defer windows.CloseHandle(h)
	entry := windows.ProcessEntry32{Size: uint32(unsafe.Sizeof(windows.ProcessEntry32{}))}
	var out []platformProcess
	for e = windows.Process32First(h, &entry); e == nil; e = windows.Process32Next(h, &entry) {
		out = append(out, platformProcess{PID: entry.ProcessID, Parent: entry.ParentProcessID, Name: windows.UTF16ToString(entry.ExeFile[:])})
	}
	hosts := map[uint32]bool{}
	shells := map[uint32]bool{}
	for _, p := range out {
		n := strings.ToLower(p.Name)
		if n == "claude.exe" || n == "codex.exe" || n == "antigravity.exe" {
			hosts[p.PID] = true
		}
	}
	for _, p := range out {
		n := strings.ToLower(p.Name)
		if hosts[p.Parent] && (n == "pwsh.exe" || n == "powershell.exe" || n == "cmd.exe" || n == "bash.exe") {
			shells[p.PID] = true
		}
	}
	for i := range out {
		if shells[out[i].PID] || shells[out[i].Parent] {
			out[i].Command = platformProcessCommand(out[i].PID)
		}
	}
	return out, nil
}

func platformProcessCommand(pid uint32) string {
	p, e := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if e != nil {
		return ""
	}
	defer windows.CloseHandle(p)
	buffer := make([]byte, 65536)
	var returned uint32
	status, _, _ := windows.NewLazySystemDLL("ntdll.dll").NewProc("NtQueryInformationProcess").Call(uintptr(p), 60, uintptr(unsafe.Pointer(&buffer[0])), uintptr(len(buffer)), uintptr(unsafe.Pointer(&returned)))
	if status != 0 {
		return ""
	}
	length := *(*uint16)(unsafe.Pointer(&buffer[0]))
	offset := int(unsafe.Sizeof(uintptr(0)))
	pointer := *(*uintptr)(unsafe.Pointer(&buffer[offset]))
	base := uintptr(unsafe.Pointer(&buffer[0]))
	if length == 0 || int(length)%2 != 0 || pointer < base || pointer+uintptr(length) > base+uintptr(len(buffer)) {
		return ""
	}
	return windows.UTF16ToString(unsafe.Slice((*uint16)(unsafe.Pointer(&buffer[int(pointer-base)])), int(length)/2))
}

var consoleKernel = windows.NewLazySystemDLL("kernel32.dll")
var consoleAttach = consoleKernel.NewProc("AttachConsole")
var consoleFree = consoleKernel.NewProc("FreeConsole")
var consoleHandler = consoleKernel.NewProc("SetConsoleCtrlHandler")
var consoleInfo = consoleKernel.NewProc("GetConsoleScreenBufferInfo")
var consoleRead = consoleKernel.NewProc("ReadConsoleOutputW")
var consoleWrite = consoleKernel.NewProc("WriteConsoleInputW")
var consoleTitle = consoleKernel.NewProc("GetConsoleTitleW")
var consoleCtrl = consoleKernel.NewProc("GenerateConsoleCtrlEvent")

type consoleCoord struct{ X, Y int16 }
type consoleRect struct{ Left, Top, Right, Bottom int16 }
type consoleBufferInfo struct {
	Size, Cursor consoleCoord
	Attr         uint16
	Window       consoleRect
	Max          consoleCoord
}
type consoleChar struct{ Char, Attr uint16 }
type consoleKey struct {
	Type                   uint16
	Pad                    uint16
	Down                   int32
	Repeat, VK, Scan, Char uint16
	Control                uint32
}

func consolePack(c consoleCoord) uintptr {
	return uintptr(uint32(uint16(c.X)) | uint32(uint16(c.Y))<<16)
}
func consoleANSI(w int) int { return ((w & 1) << 2) | (w & 2) | ((w & 4) >> 2) | (w & 8) }
func platformConsoleScreen(h windows.Handle) (M, error) {
	var info consoleBufferInfo
	ok, _, e := consoleInfo.Call(uintptr(h), uintptr(unsafe.Pointer(&info)))
	if ok == 0 {
		return nil, e
	}
	w := int(info.Window.Right - info.Window.Left + 1)
	rows := int(info.Window.Bottom - info.Window.Top + 1)
	if w < 1 || w > 1000 || rows < 1 || rows > 500 {
		return nil, errors.New("invalid console dimensions")
	}
	buf := make([]consoleChar, w*rows)
	region := info.Window
	ok, _, e = consoleRead.Call(uintptr(h), uintptr(unsafe.Pointer(&buf[0])), consolePack(consoleCoord{int16(w), int16(rows)}), 0, uintptr(unsafe.Pointer(&region)))
	if ok == 0 {
		return nil, e
	}
	lines := make([]any, rows)
	for y := 0; y < rows; y++ {
		runs := []any{}
		var chars []uint16
		attr := -1
		flush := func() {
			if len(chars) == 0 {
				return
			}
			text := windows.UTF16ToString(chars)
			fg, bg := consoleANSI(attr&15), consoleANSI((attr>>4)&15)
			if attr&15 == 7 {
				fg = -1
			}
			if (attr>>4)&15 == 0 {
				bg = -1
			}
			flags := 0
			if attr&0x4000 != 0 {
				flags |= 8
			}
			if attr&0x8000 != 0 {
				flags |= 4
			}
			if fg == -1 && bg == -1 && flags == 0 {
				runs = append(runs, []any{text})
			} else {
				runs = append(runs, []any{text, fg, bg, flags})
			}
			chars = nil
		}
		for x := 0; x < w; x++ {
			c := buf[y*w+x]
			if c.Attr&0x200 != 0 {
				continue
			}
			a := int(c.Attr & 0xc0ff)
			if a != attr {
				flush()
				attr = a
			}
			ch := c.Char
			if ch == 0 {
				ch = ' '
			}
			chars = append(chars, ch)
		}
		flush()
		lines[y] = runs
	}
	var title [512]uint16
	consoleTitle.Call(uintptr(unsafe.Pointer(&title[0])), uintptr(len(title)))
	return M{"type": "screen", "w": w, "h": rows, "cx": int(info.Cursor.X - info.Window.Left), "cy": int(info.Cursor.Y - info.Window.Top), "title": windows.UTF16ToString(title[:]), "lines": lines}, nil
}
func platformConsoleInput(h windows.Handle, text string) error {
	seqs := map[string]uint16{"\x1b[A": 0x26, "\x1bOA": 0x26, "\x1b[B": 0x28, "\x1bOB": 0x28, "\x1b[C": 0x27, "\x1bOC": 0x27, "\x1b[D": 0x25, "\x1bOD": 0x25, "\x1b[H": 0x24, "\x1bOH": 0x24, "\x1b[F": 0x23, "\x1bOF": 0x23, "\x1b[5~": 0x21, "\x1b[6~": 0x22, "\x1b[3~": 0x2e}
	var keys []consoleKey
	add := func(vk, ch uint16, ctrl uint32) {
		k := consoleKey{Type: 1, Down: 1, Repeat: 1, VK: vk, Char: ch, Control: ctrl}
		keys = append(keys, k)
		k.Down = 0
		keys = append(keys, k)
	}
	for len(text) > 0 {
		found := false
		for seq, vk := range seqs {
			if strings.HasPrefix(text, seq) {
				add(vk, 0, 0x100)
				text = text[len(seq):]
				found = true
				break
			}
		}
		if found {
			continue
		}
		r := []rune(text)[0]
		text = text[len(string(r)):]
		switch r {
		case '\x03':
			consoleCtrl.Call(0, 0)
		case '\r', '\n':
			add(0xd, '\r', 0)
		case '\t':
			add(9, 9, 0)
		case '\x1b':
			add(0x1b, 0x1b, 0)
		case '\b', 0x7f:
			add(8, 8, 0)
		default:
			if r < 32 {
				add(uint16('A'+r-1), uint16(r), 8)
			} else {
				u := windows.StringToUTF16(string(r))
				for _, ch := range u[:len(u)-1] {
					add(0, ch, 0)
				}
			}
		}
	}
	if len(keys) == 0 {
		return nil
	}
	var written uint32
	ok, _, e := consoleWrite.Call(uintptr(h), uintptr(unsafe.Pointer(&keys[0])), uintptr(len(keys)), uintptr(unsafe.Pointer(&written)))
	if ok == 0 {
		return e
	}
	return nil
}
func platformAttachConsole(pid int) (windows.Handle, windows.Handle, error) {
	consoleFree.Call()
	ok, _, e := consoleAttach.Call(uintptr(pid))
	if ok == 0 {
		return 0, 0, e
	}
	consoleHandler.Call(0, 1)
	out, e := windows.CreateFile(windows.StringToUTF16Ptr("CONOUT$"), windows.GENERIC_READ|windows.GENERIC_WRITE, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil, windows.OPEN_EXISTING, 0, 0)
	if e != nil {
		return 0, 0, e
	}
	in, e := windows.CreateFile(windows.StringToUTF16Ptr("CONIN$"), windows.GENERIC_READ|windows.GENERIC_WRITE, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil, windows.OPEN_EXISTING, 0, 0)
	return in, out, e
}
func platformBridgePID(s string) (int, error) {
	pid, e := strconv.Atoi(s)
	if e != nil || pid <= 0 || pid == os.Getpid() {
		return 0, fmt.Errorf("invalid console process")
	}
	return pid, nil
}
