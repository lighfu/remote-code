package host

import (
	"context"
	"errors"
	"net"
	"testing"
	"time"
)

func TestDesktopSteeringReceiptSurvivesMissingReply(t *testing.T) {
	store := providerTestStore(t)
	p := NewProvider("codex", store, t.TempDir()).(*nativeProvider)
	defer p.Close()
	d := p.desktopClient()
	thread, clientID := NewID(), NewID()
	rec := store.Create(M{"provider": "codex", "externalId": thread, "cwd": t.TempDir()})
	sid := S(rec.Info, "id")
	client, server := net.Pipe()
	defer server.Close()
	if err := server.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
		t.Fatal(err)
	}
	active := func(items ...M) M {
		return M{"threadRuntimeStatus": M{"type": "active"}, "turns": []M{{"turnId": "current-turn", "status": "inProgress", "items": items}}}
	}
	d.conn, d.clientID = client, "remote-code"
	d.follows[thread] = &desktopBinding{sid: sid, owner: "desktop-owner", state: active(), revision: 1}
	go d.read(client)

	events, stop := store.Subscribe()
	defer stop()
	ctx, cancel := context.WithTimeout(t.Context(), 3*time.Second)
	defer cancel()
	sent := make(chan error, 1)
	go func() {
		_, err := p.desktopDo(ctx, "send", rec, M{
			"text": "With image", "clientMessageId": clientID,
			"images": []M{{"mime": "image/png", "data": "iVBORw0KGgo="}},
		})
		sent <- err
	}()
	request, err := desktopTestRead(server)
	if err != nil {
		t.Fatal(err)
	}
	params := providerMap(request["params"])
	input := providerRows(params["input"])
	if S(request, "method") != "thread-follower-steer-turn" || S(params, "clientUserMessageId") != clientID ||
		S(providerMap(params["restoreMessage"]), "id") != clientID || len(input) != 2 || S(input[1], "type") != "localImage" {
		t.Fatalf("wrong steering request: %v", request)
	}
	project := func(revision int, items ...M) {
		t.Helper()
		err := desktopTestWrite(server, M{
			"type": "broadcast", "method": "thread-stream-state-changed", "sourceClientId": "desktop-owner", "version": 13,
			"params": M{"hostId": "local", "conversationId": thread, "change": M{"type": "snapshot", "revision": revision, "conversationState": active(items...)}},
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	steering := M{"type": "steeringUserMessage", "id": "steering", "input": input, "clientUserMessageId": clientID, "status": "pending"}
	project(2, steering)
	pendingProjected := false
	for !pendingProjected {
		select {
		case event := <-events:
			if S(event, "type") == "entry" {
				t.Fatalf("pending steering produced an entry: %v", event)
			}
			pendingProjected = S(event, "type") == "session" && S(providerMap(event["session"]), "status") == "running"
		case <-ctx.Done():
			t.Fatal("pending state was not projected")
		}
	}
	// Desktop can assign its own ID and include a canonical row before the steering wrapper.
	accepted := clone(steering)
	accepted["status"] = "accepted"
	accepted["serverUserMessageId"] = "accepted"
	assigned := NewID()
	accepted["serverClientUserMessageId"] = assigned
	project(3, M{"id": "accepted", "type": "userMessage", "clientId": assigned, "content": input}, accepted)
	for {
		select {
		case event := <-events:
			entry := providerMap(event["entry"])
			if S(entry, "id") != "accepted" {
				continue
			}
			if S(entry, "clientMessageId") != clientID || S(entry, "text") != "With image" || len(providerAnswerList(entry["media"])) != 1 {
				t.Fatalf("projected message lost the receipt or attachment: %v", entry)
			}
			cancel()
			if err := <-sent; !errors.Is(err, context.Canceled) {
				t.Fatalf("expected the unanswered IPC request to fail once cancelled, got %v", err)
			}
			return
		case <-ctx.Done():
			t.Fatal("accepted message was not projected")
		}
	}
}
