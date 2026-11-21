package host

import (
	"context"
	"errors"
	"net/http"
	"testing"
)

func TestClaudeRemoteSendRecordsOnlyAcceptedMessages(t *testing.T) {
	const clientID = "2b878d27-aa08-49ee-8d01-6b674ef48837"
	for _, tc := range []struct {
		name         string
		duringSend   string
		transportErr error
		status       string
		accepted     bool
	}{
		{name: "unauthorized", transportErr: errors.New("Remote Control send returned HTTP 401"), status: "idle"},
		{name: "unknown delivery", transportErr: context.DeadlineExceeded, status: "idle"},
		{name: "accepted", accepted: true, status: "running"},
		{name: "early echo and result", duringSend: "finished", accepted: true, status: "idle"},
		{name: "early echo before timeout", duringSend: "echo", transportErr: context.DeadlineExceeded, accepted: true, status: "running"},
		{name: "approval before acknowledgment", duringSend: "approval", accepted: true, status: "waiting"},
		{name: "disconnect before acknowledgment", duringSend: "disconnect", accepted: true, status: "idle"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			a := testApp(t)
			p := NewProvider("claude", a.Store, a.DataDir).(*nativeProvider)
			a.Providers["claude"] = p
			sid := S(a.Store.Create(M{"provider": "claude", "live": true, "externalId": "pc-session"}).Info, "id")
			a.Store.Add(sid, M{"id": "previous", "kind": "user", "text": "Continue", "title": "From PC"})
			a.Store.Add(sid, M{"id": "previous-result", "kind": "result", "text": "Completed"})
			var sentID string
			wire := &providerWire{
				closeFunc: func() error { return nil },
				sendContextFunc: func(_ context.Context, msg M) error {
					sentID = S(msg, "uuid")
					if tc.duringSend == "finished" || tc.duringSend == "echo" {
						a.Store.Add(sid, M{"id": sentID, "kind": "user", "text": "Continue", "title": "From PC"})
					}
					switch tc.duringSend {
					case "finished":
						a.Store.Add(sid, M{"id": "real-reply", "kind": "assistant", "text": "Done"})
						a.Store.Add(sid, M{"id": "real-result", "kind": "result", "text": "Completed"})
					case "approval":
						a.Store.Update(sid, M{"status": "waiting", "pending": 1}, false)
					case "disconnect":
						a.Store.Update(sid, M{"live": false}, false)
					}
					return tc.transportErr
				},
			}
			p.sessions[sid] = &nativeSession{wire: wire, external: "pc-session", remote: true}
			url := "/api/sessions/" + sid + "/messages"
			body := `{"text":"Continue","clientMessageId":"` + clientID + `"}`
			result := testRequest(a, http.MethodPost, url, body)
			if (result.Code == http.StatusOK) != tc.accepted || sentID == "" {
				t.Fatalf("unexpected delivery result: %d %s", result.Code, result.Body)
			}
			rec := a.Store.Get(sid)
			if S(rec.Info, "status") != tc.status {
				t.Fatalf("session status = %q, want %q", S(rec.Info, "status"), tc.status)
			}
			var outgoing []M
			for _, entry := range rec.Entries {
				if S(entry, "kind") == "user" && S(entry, "id") != "previous" {
					outgoing = append(outgoing, entry)
				}
			}
			if tc.accepted {
				if len(outgoing) != 1 || S(outgoing[0], "id") != sentID || S(outgoing[0], "clientMessageId") != clientID || S(outgoing[0], "title") != "" {
					t.Fatalf("accepted message receipt = %v", outgoing)
				}
				if tc.duringSend == "finished" && S(rec.Entries[len(rec.Entries)-1], "id") != "real-result" {
					t.Fatal("early user echo moved after the result")
				}
			} else if len(outgoing) != 0 {
				t.Fatalf("unconfirmed message was added to the transcript: %v", outgoing)
			}
		})
	}
}
