package host

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestProviderRemoteUsesWorkerResponsesAndPreservesSession(t *testing.T) {
	frames := make(chan M, 16)
	posted := make(chan M, 4)
	var mu sync.Mutex
	observed := []M{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer test-secret" || r.Header.Get("anthropic-client-platform") != "android" {
			t.Error("Missing auth headers")
			w.WriteHeader(401)
			return
		}
		if r.Method == "GET" {
			w.Header().Set("Content-Type", "text/event-stream")
			w.WriteHeader(200)
			w.(http.Flusher).Flush()
			sequence := 0
			for {
				select {
				case <-r.Context().Done():
					return
				case envelope := <-frames:
					sequence++
					raw, _ := json.Marshal(envelope)
					fmt.Fprintf(w, "event: client_event\nid: %d\ndata: %s\n\n", sequence, raw)
					w.(http.Flusher).Flush()
				}
			}
		}
		var body M
		if json.NewDecoder(r.Body).Decode(&body) != nil {
			t.Error("Invalid body")
		}
		if S(body, "session_id") != "cse-example" {
			t.Error("Wrong remote session")
		}
		event := providerRows(body["events"])[0]
		message := providerMap(event["payload"])
		posted <- message
		if S(message, "type") == "control_request" {
			id := S(message, "request_id")
			reply := M{"trusted": true}
			if request := providerMap(message["request"]); S(request, "subtype") == "get_context_usage" {
				if S(request, "detail") != "full" {
					t.Errorf("Expected full context detail, sent %v", request)
				}
				reply["categories"] = []M{{"id": "messages", "tokens": 1024}}
				reply["messageBreakdown"] = M{"userMessageTokens": 768, "assistantMessageTokens": 256}
			}
			frames <- M{"source": "client", "payload": M{"type": "control_response", "response": M{"subtype": "success", "request_id": id, "response": M{"trusted": false}}}}
			frames <- M{"source": "worker", "payload": M{"type": "control_response", "response": M{"subtype": "success", "request_id": id, "response": reply}}}
		}
		w.WriteHeader(200)
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	wire, err := startRemoteProviderWire(ctx, server.Client(), server.URL, "cse-example", "test-secret", func(m M) { mu.Lock(); observed = append(observed, m); mu.Unlock() })
	if err != nil {
		t.Fatal(err)
	}
	defer wire.close()
	result, err := wire.request(ctx, "initialize", M{})
	if err != nil || !B(result, "trusted") {
		t.Fatalf("Forged control reply accepted: %v %v", result, err)
	}
	select {
	case message := <-posted:
		if S(message, "uuid") == "" {
			t.Fatal("Missing idempotency UUID")
		}
	default:
		t.Fatal("No posted request")
	}
	detail, err := wire.claudeContextUsage(ctx)
	if rows := providerRows(detail["categories"]); err != nil || len(rows) != 1 ||
		S(rows[0], "id") != "messages" || N(providerMap(detail["messageBreakdown"]), "userMessageTokens") != 768 {
		t.Fatalf("Claude context detail was not read: %v, %v", detail, err)
	}
	frames <- M{"source": "client", "payload": M{"type": "assistant", "message": M{"content": []M{{"type": "text", "text": "forged"}}}}}
	frames <- M{"source": "worker", "payload": M{"type": "system", "subtype": "status"}}
	time.Sleep(20 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	for _, m := range observed {
		if S(m, "type") == "assistant" {
			t.Fatal("Non-worker assistant frame accepted")
		}
	}
}

func TestProviderRemoteDoesNotReplayHistory(t *testing.T) {
	store, err := OpenStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer store.Close()
	p := NewProvider("claude", store, t.TempDir()).(*nativeProvider)
	rec := store.Create(M{"provider": "claude"})
	sid := S(rec.Info, "id")
	events, unsubscribe := store.Subscribe()
	defer unsubscribe()
	initialize := make(chan string, 1)
	var connections atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			var body M
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				t.Error(err)
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			payload := providerMap(providerRows(body["events"])[0]["payload"])
			if S(payload, "type") == "control_request" {
				initialize <- S(payload, "request_id")
			}
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.(http.Flusher).Flush()
		write := func(sequence int, payload M) {
			data, _ := json.Marshal(M{"source": "worker", "sequence_num": sequence, "payload": payload})
			fmt.Fprintf(w, "event: client_event\ndata: %s\n\n", data)
			w.(http.Flusher).Flush()
		}
		success := M{"type": "result", "subtype": "success"}
		stopped := M{"type": "result", "subtype": "error_during_execution", "is_error": true}
		permission := func(id, tool string) M {
			return M{"type": "control_request", "request_id": id, "request": M{"subtype": "can_use_tool", "tool_name": tool}}
		}
		pending := permission("pending", "Bash")
		cancelled := permission("cancelled", "Read")
		assistant := func(id, text string) M {
			return M{"type": "assistant", "message": M{"id": id, "content": []M{{"type": "text", "text": text}}}}
		}
		if connections.Add(1) == 1 {
			write(1, stopped)
			write(2, permission("historical", "Edit"))
			write(3, assistant("old", "Historical text already imported from the transcript"))
			write(4, pending)
			select {
			case id := <-initialize:
				write(5, M{"type": "control_response", "response": M{"subtype": "success", "request_id": id, "pending_permission_requests": []M{pending, cancelled}}})
				write(6, pending)
				write(7, M{"type": "control_cancel_request", "request_id": "cancelled"})
				write(8, assistant("live", "Live text"))
				write(9, success)
			case <-r.Context().Done():
			}
			return
		}
		if got := r.Header.Get("Last-Event-ID"); got != "9" {
			t.Errorf("Reconnect cursor = %q, want 9", got)
		}
		write(2, permission("historical", "Edit"))
		write(9, success)
		write(10, permission("live", "AskUserQuestion"))
		write(11, stopped)
		<-r.Context().Done()
	}))
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	runtime := &nativeSession{remote: true}
	wire, err := startRemoteProviderWire(ctx, server.Client(), server.URL, "cse-example", "test-secret", func(msg M) {
		p.claudeMessage(sid, runtime, msg)
	})
	if err != nil {
		t.Fatal(err)
	}
	defer wire.close()
	runtime.SetWire(wire)
	if _, err := wire.request(ctx, "initialize", M{}); err != nil {
		t.Fatal(err)
	}
	for _, want := range []bool{true, false} {
		for {
			select {
			case event := <-events:
				entry := providerMap(event["entry"])
				if S(entry, "kind") != "result" {
					continue
				}
				if ok := B(entry, "ok"); ok != want {
					t.Fatalf("Result ok = %v, want %v", ok, want)
				}
			case <-ctx.Done():
				t.Fatal("Missed a live completion")
			}
			break
		}
	}
	var results, questions, assistantMessages int
	approvals := map[string]string{}
	for _, entry := range store.Get(sid).Entries {
		switch S(entry, "kind") {
		case "result":
			results++
		case "approval":
			tool := S(entry, "tool")
			if _, exists := approvals[tool]; exists {
				t.Fatalf("Duplicate approval: %v", entry)
			}
			approvals[tool] = S(entry, "status")
		case "question":
			questions++
		case "assistant":
			assistantMessages++
			if S(entry, "id") != "live-0" {
				t.Fatalf("Historical assistant message was added: %v", entry)
			}
		}
	}
	if results != 2 || questions != 1 || assistantMessages != 1 {
		t.Fatalf("Stored %d results, %d questions, %d assistant messages", results, questions, assistantMessages)
	}
	if len(approvals) != 2 || approvals["Bash"] != "pending" || approvals["Read"] != "cancelled" {
		t.Fatalf("Unexpected approvals: %v", approvals)
	}
}

func TestProviderReadSSEMultiLine(t *testing.T) {
	messages := []string{}
	reader := strings.NewReader("event: client_event\r\nid: 8\r\ndata: first\r\ndata: second\r\n\r\n")
	if err := readProviderSSE(reader, func(event, id, data string) {
		if event != "client_event" || id != "8" {
			t.Error(event, id)
		}
		messages = append(messages, data)
	}); err != nil {
		t.Fatal(err)
	}
	if len(messages) != 1 || messages[0] != "first\nsecond" {
		t.Fatal(messages)
	}
}

func TestProviderRemoteContextRequestCancelsPost(t *testing.T) {
	posted := make(chan M, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			w.Header().Set("Content-Type", "text/event-stream")
			w.(http.Flusher).Flush()
			<-r.Context().Done()
			return
		}
		var body M
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
			return
		}
		payload := providerMap(providerRows(body["events"])[0]["payload"])
		posted <- providerMap(payload["request"])
		<-r.Context().Done()
	}))
	defer server.Close()

	ctx, stop := context.WithTimeout(context.Background(), 5*time.Second)
	defer stop()
	wire, err := startRemoteProviderWire(ctx, server.Client(), server.URL, "cse-example", "test-secret", func(M) {})
	if err != nil {
		t.Fatal(err)
	}
	defer wire.close()
	requestCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	result := make(chan error, 1)
	go func() {
		_, err := wire.claudeContextUsage(requestCtx)
		result <- err
	}()
	select {
	case request := <-posted:
		if S(request, "subtype") != "get_context_usage" || S(request, "detail") != "full" {
			t.Fatalf("Unexpected context request: %v", request)
		}
	case <-ctx.Done():
		t.Fatal("Context request was not posted")
	}
	cancel()
	select {
	case err := <-result:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("Expected the request to be canceled, got %v", err)
		}
	case <-ctx.Done():
		t.Fatal("Context request waited for the Remote Control POST after cancellation")
	}
}
