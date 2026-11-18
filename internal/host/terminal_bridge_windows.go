//go:build windows

package host

import (
	"bufio"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"golang.org/x/sys/windows"
	"os"
	"sync/atomic"
	"time"
)

func runConsoleBridge(args []string) error {
	if len(args) < 2 {
		return errors.New("invalid console bridge arguments")
	}
	pid, e := platformBridgePID(args[1])
	if e != nil {
		return e
	}
	in, out, e := platformAttachConsole(pid)
	if e != nil {
		return e
	}
	defer windows.CloseHandle(in)
	defer windows.CloseHandle(out)
	defer consoleFree.Call()
	if args[0] == "input" {
		if len(args) != 3 {
			return errors.New("missing console input")
		}
		text, e := base64.StdEncoding.DecodeString(args[2])
		if e != nil {
			return e
		}
		return platformConsoleInput(in, string(text))
	}
	if args[0] != "attach" && args[0] != "dump" {
		return errors.New("invalid console action")
	}
	var done atomic.Bool
	go func() {
		s := bufio.NewScanner(os.Stdin)
		s.Buffer(make([]byte, 4096), 2<<20)
		for s.Scan() {
			line := s.Text()
			if line == "Q" {
				break
			}
			if len(line) > 1 && line[0] == 'I' {
				if b, e := base64.StdEncoding.DecodeString(line[1:]); e == nil {
					platformConsoleInput(in, string(b))
				}
			}
		}
		done.Store(true)
	}()
	last := ""
	for !done.Load() {
		m, e := platformConsoleScreen(out)
		if e != nil {
			json.NewEncoder(os.Stdout).Encode(M{"type": "exit", "code": nil})
			return nil
		}
		raw, _ := json.Marshal(m)
		if string(raw) != last {
			fmt.Println(string(raw))
			last = string(raw)
		}
		if args[0] == "dump" {
			return nil
		}
		time.Sleep(60 * time.Millisecond)
	}
	return nil
}
