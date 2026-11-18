package host

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"
)

func TestImmediateMessageInterruptsExistingTurnBeforeSend(t *testing.T) {
	a := testApp(t)
	p := &fakeProvider{}
	a.Providers["codex"] = p
	rec := a.Store.Create(M{"provider": "codex", "status": "running"})
	url := "/api/sessions/" + S(rec.Info, "id") + "/messages"
	if res := testRequest(a, http.MethodPost, url, `{"text":"First","now":true}`); res.Code != http.StatusOK {
		t.Fatal(res.Body)
	}
	if res := testRequest(a, http.MethodPost, url, `{"text":"Second"}`); res.Code != http.StatusOK {
		t.Fatal(res.Body)
	}
	if !slices.Equal(p.calls, []string{"interrupt", "send", "send"}) {
		t.Fatal(p.calls)
	}
}

func TestLANModeOnlyAllowsLocalNetworkPeers(t *testing.T) {
	a := testApp(t)
	a.Config.AllowLAN = true
	for _, tc := range []struct {
		peer string
		code int
	}{
		{"127.0.0.1:1234", http.StatusOK},
		{"10.2.3.4:1234", http.StatusOK},
		{"[fd00::6]:1234", http.StatusOK},
		{"[fe80::6%adapter]:1234", http.StatusOK},
		{"203.0.113.13:1234", http.StatusForbidden},
		{"[2001:db8::6]:1234", http.StatusForbidden},
	} {
		r := httptest.NewRequest(http.MethodGet, "/api/health", nil)
		r.RemoteAddr = tc.peer
		res := httptest.NewRecorder()
		a.Handler().ServeHTTP(res, r)
		if res.Code != tc.code {
			t.Errorf("%s status = %d, want %d", tc.peer, res.Code, tc.code)
		}
	}
}

func TestStoreLoadsOtherSessionsWhenOneFileIsUnreadable(t *testing.T) {
	dir := t.TempDir()
	good := Record{Info: M{"id": "good", "provider": "claude"}}
	if err := atomicJSON(filepath.Join(dir, "good.json"), good); err != nil {
		t.Fatal(err)
	}
	brokenPath := filepath.Join(dir, "broken.json")
	if err := os.WriteFile(brokenPath, []byte("{"), 0600); err != nil {
		t.Fatal(err)
	}
	store, err := OpenStore(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	if got := store.List(); len(got) != 1 || S(got[0], "id") != "good" {
		t.Fatal(got)
	}
	if raw, err := os.ReadFile(brokenPath); err != nil || string(raw) != "{" {
		t.Fatalf("unreadable session was changed: %v", err)
	}
}

func TestQuickTunnelWatchReportsProcessExitAndLeavesTunnelOnServerStop(t *testing.T) {
	done := make(chan struct{})
	close(done)
	if !watchQuickTunnel(t.Context(), done, nil, time.Hour) {
		t.Fatal("the exited tunnel was not scheduled for recovery")
	}
	if !watchQuickTunnel(t.Context(), nil, func() bool { return false }, time.Millisecond) {
		t.Fatal("an adopted tunnel's process exit was not detected")
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if watchQuickTunnel(ctx, done, nil, time.Hour) {
		t.Fatal("stopping the server must not start another tunnel")
	}
}
