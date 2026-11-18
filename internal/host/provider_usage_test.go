package host

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

func TestProviderClaudeUsageFallsBackToNativeControlWithoutPrompt(t *testing.T) {
	t.Setenv("CLAUDE_CONFIG_DIR", t.TempDir())
	store := providerTestStore(t)
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	rec := store.Create(M{"provider": "claude"})
	wire := &providerWire{claude: true, waiting: map[string]chan providerReply{}, done: make(chan struct{}), onMessage: func(M) {}}
	var mu sync.Mutex
	methods := []string{}
	wire.sendFunc = func(message M) error {
		if S(message, "type") != "control_request" {
			t.Error("Usage sent a model/user prompt")
			return errors.New("Only control requests allowed")
		}
		request := providerMap(message["request"])
		method := S(request, "subtype")
		mu.Lock()
		methods = append(methods, method)
		mu.Unlock()
		if method != "get_usage" || !B(request, "skip_behaviors") {
			t.Error("Unexpected usage control", request)
		}
		wire.receive(M{"type": "control_response", "response": M{"subtype": "success", "request_id": S(message, "request_id"), "response": M{"subscription_type": "max", "rate_limits": M{"seven_day": M{"utilization": 42.0, "resets_at": "2026-11-22T09:00:00Z"}}}}})
		return nil
	}
	runtime := &nativeSession{}
	runtime.SetWire(wire)
	p.sessions[S(rec.Info, "id")] = runtime
	p.usage = M{"windows": []M{{"label": "Weekly", "usedPercent": 30.0}}, "tickets": M{"available": 4, "items": []M{{"title": "Weekly reset"}}}, "at": providerNow() - 600000}
	p.usageUntil = time.Time{}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	value, err := p.readUsage(ctx, true)
	if err != nil {
		t.Fatal(err)
	}
	usage := providerMap(value)
	windows := providerRows(usage["windows"])
	if len(windows) != 1 || N(windows[0], "usedPercent") != 42 || N(providerMap(usage["tickets"]), "available") != 4 {
		t.Fatalf("Fallback/tickets lost: %v", usage)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(methods) != 1 || methods[0] != "get_usage" {
		t.Fatalf("Wrong control calls: %v", methods)
	}
}
