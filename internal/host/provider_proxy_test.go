package host

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func init() {
	if os.Getenv("REMOTE_CODE_PROXY_FIXTURE") != "1" {
		return
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ws, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err != nil {
			os.Exit(3)
		}
		for {
			var m M
			if err = ws.ReadJSON(&m); err != nil {
				os.Exit(0)
			}
			if S(m, "method") == "exit" {
				os.Exit(7)
			}
			_ = ws.WriteJSON(M{"id": m["id"], "result": M{"echo": providerMap(m["params"])["payload"]}})
		}
	}))
	conn, err := net.Dial("tcp", server.Listener.Addr().String())
	if err != nil {
		os.Exit(4)
	}
	go func() { _, _ = io.Copy(conn, os.Stdin); conn.Close() }()
	_, _ = io.Copy(os.Stdout, conn)
	os.Exit(0)
}
func TestSharedDaemonWebSocketOverNativeStdio(t *testing.T) {
	t.Setenv("REMOTE_CODE_PROXY_FIXTURE", "1")
	exe, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	wire, err := startProviderProxy(ctx, exe, func(M) {})
	if err != nil {
		t.Fatal(err)
	}
	defer wire.close()
	for _, value := range []string{"initialize", "unicode 日本語", "line\nnext"} {
		reply, err := wire.request(ctx, "echo", M{"payload": value})
		if err != nil || S(reply, "echo") != value {
			t.Fatal(reply, err)
		}
	}
	if _, err = wire.request(ctx, "exit", M{}); err == nil {
		t.Fatal("proxy process exit was not propagated")
	}
}
