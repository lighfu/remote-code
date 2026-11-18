package host

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"testing"
	"time"
)

func TestCodexQuickQuestionSamplesLatestConversationWithoutHydratingItsTurns(t *testing.T) {
	for _, active := range []bool{false, true} {
		t.Run(fmt.Sprintf("active=%v", active), func(t *testing.T) {
			p := NewProvider("codex", providerTestStore(t), t.TempDir()).(*nativeProvider)
			defer p.Close()
			rec := &Record{Info: M{"id": "s", "externalId": "parent"}}
			if active {
				p.sessions["s"] = &nativeSession{external: "parent", turn: "latest"}
			}
			var methods []string
			p.rpc = codexParityWire(func(method string, params M) (M, error) {
				methods = append(methods, method)
				switch method {
				case "thread/turns/list":
					if params["limit"] != 1 || params["itemsView"] != "notLoaded" || params["sortDirection"] != "desc" {
						t.Fatalf("history unexpectedly requested: %v", params)
					}
					return M{"data": []M{{"id": "latest"}}}, nil
				case "thread/sample":
					prompt := S(params, "prompt")
					if S(params, "threadId") != "parent" || S(params, "expectedTurnId") != "latest" ||
						!B(params, "allowInProgressTurn") || !B(params, "disableMcp") || S(params, "effort") != "low" ||
						!strings.Contains(prompt, "Earlier side question: prior Q\nAnswer: prior A") || !strings.Contains(prompt, "Side question: next Q") {
						t.Fatalf("incorrect sample scope or prompt: %v", params)
					}
					return M{"text": "reply", "sourceTurnId": "latest"}, nil
				default:
					return nil, fmt.Errorf("unexpected method %s", method)
				}
			})
			out, err := p.codexSideQuestion(context.Background(), rec, M{"question": "next Q", "history": []M{{"question": "prior Q", "response": "prior A"}}})
			want := []string{"thread/turns/list", "thread/sample"}
			if active {
				want = want[1:]
			}
			if err != nil || S(providerMap(out), "response") != "reply" || !reflect.DeepEqual(want, methods) {
				t.Fatalf("output %v, error %v, methods %v", out, err, methods)
			}
		})
	}
}

func TestCodexQuickQuestionFallsBackOnlyBeforeSampling(t *testing.T) {
	for _, tc := range []struct {
		message  string
		fallback bool
	}{
		{"Method not found", true},
		{"thread/sample requires a loaded source", true},
		{"sample source turn must be the latest turn", true},
		{"sample source must be a persistent root thread", true},
		{"sample timed out", false}, {"sample source changed", false},
		{"quota exceeded", false}, {"", false},
	} {
		w := codexParityWire(func(method string, _ M) (M, error) {
			if method != "thread/sample" {
				t.Fatalf("unexpected method: %s", method)
			}
			if tc.message == "" {
				return M{"text": nil}, nil
			}
			return nil, errors.New(tc.message)
		})
		out, err := codexSampleSideQuestion(context.Background(), w, "parent", "turn", "question")
		if out != nil || (err == nil) != tc.fallback {
			t.Fatalf("%q: output %v, err %v", tc.message, out, err)
		}
	}
}

func TestCancelCodexQuickQuestionOnlyCancelsItsSample(t *testing.T) {
	w := &providerWire{waiting: map[string]chan providerReply{}, done: make(chan struct{})}
	requested := make(chan string, 1)
	canceled := make(chan string, 1)
	w.sendFunc = func(msg M) error {
		switch S(msg, "method") {
		case "thread/sample":
			requested <- S(msg, "id")
		case "thread/sample/cancel":
			canceled <- S(providerMap(msg["params"]), "requestId")
			w.receive(M{"id": msg["id"], "result": M{}})
		default:
			return fmt.Errorf("unexpected method: %v", msg)
		}
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() { _, err := codexSampleSideQuestion(ctx, w, "parent", "turn", "question"); done <- err }()
	var id string
	select {
	case id = <-requested:
	case <-ctx.Done():
		t.Fatal("sample did not start")
	}
	cancel()
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel returned %v", err)
	}
	select {
	case got := <-canceled:
		if got == "" || got != id {
			t.Fatalf("canceled %q, want %q", got, id)
		}
	case <-time.After(time.Second):
		t.Fatal("sample was not canceled upstream")
	}
}
