package host

import (
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"
)

func TestNativeCodexSetsFastModeAfterSavingModel(t *testing.T) {
	a := testApp(t)
	p := NewProvider("codex", a.Store, a.DataDir).(*nativeProvider)
	a.Providers["codex"] = p
	rec := a.Store.Create(M{"provider": "codex", "externalId": "native", "model": "before", "fastMode": true})
	sid := S(rec.Info, "id")
	calls := 0
	wire := codexParityWire(func(method string, params M) (M, error) {
		calls++
		tier, hasTier := params["serviceTier"]
		info := a.Store.Get(sid).Info
		if method != "thread/settings/update" || S(params, "threadId") != "native" || !hasTier || tier != nil ||
			S(info, "model") != "after" || S(info, "effort") != "ultra" {
			return nil, fmt.Errorf("unexpected settings sent to native Codex: %s %v, session: %v", method, params, info)
		}
		return M{}, nil
	})
	runtime := &nativeSession{external: "native"}
	runtime.SetWire(wire)
	p.sessions[sid], p.rpc = runtime, wire
	res := testRequest(a, "PATCH", "/api/sessions/"+sid, `{"model":"after","effort":"ultra","fastMode":false}`)
	if res.Code != http.StatusOK || calls != 1 || B(a.Store.Get(sid).Info, "fastMode") {
		t.Fatalf("native settings not applied: %d %s (%d requests)", res.Code, res.Body, calls)
	}
}

func TestDesktopFastModeSettingsUseOneRequest(t *testing.T) {
	cases := []struct {
		name, body       string
		settings         M
		oldFast, applied bool
		model, effort    string
		fast             bool
	}{
		{
			name: "turn off with model and effort", body: `{"model":"fast-model","effort":"ultra","fastMode":false}`,
			settings: M{"model": "fast-model", "effort": "ultra", "serviceTier": nil},
			oldFast:  true, applied: true, model: "fast-model", effort: "ultra",
		},
		{
			name: "turn on with newly selected capable model", body: `{"model":"fast-model","effort":"ultra","fastMode":true}`,
			settings: M{"model": "fast-model", "effort": "ultra", "serviceTier": "priority"},
			applied:  true, model: "fast-model", effort: "ultra", fast: true,
		},
		{
			name: "desktop rejection preserves effective settings", body: `{"model":"fast-model","effort":"ultra","fastMode":false}`,
			settings: M{"model": "fast-model", "effort": "ultra", "serviceTier": nil},
			oldFast:  true, model: "standard-model", effort: "high", fast: true,
		},
		{
			name: "turn off without other settings", body: `{"fastMode":false}`,
			settings: M{"serviceTier": nil}, oldFast: true, applied: true, model: "standard-model", effort: "high",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			a := testApp(t)
			p := NewProvider("codex", a.Store, a.DataDir).(*nativeProvider)
			a.Providers["codex"] = p
			p.models = []M{{"id": "standard-model"}, {"id": "fast-model", "supportsFastMode": true}}
			p.modelsUntil = time.Now().Add(time.Hour)
			thread := NewID()
			rec := a.Store.Create(M{"provider": "codex", "externalId": thread, "desktop": true, "model": "standard-model", "effort": "high", "fastMode": tc.oldFast})
			sid := S(rec.Info, "id")
			d := p.desktopClient()
			client, desktop := net.Pipe()
			defer desktop.Close()
			if err := desktop.SetDeadline(time.Now().Add(3 * time.Second)); err != nil {
				t.Fatal(err)
			}
			d.conn, d.clientID = client, "remote-client"
			d.follows[thread] = &desktopBinding{sid: sid, owner: "desktop-owner", state: M{"latestModel": "standard-model"}}
			go d.read(client)
			done := make(chan *httptest.ResponseRecorder, 1)
			go func() { done <- testRequest(a, "PATCH", "/api/sessions/"+sid, tc.body) }()
			req, err := desktopTestRead(desktop)
			if err != nil {
				t.Fatal(err)
			}
			method := S(req, "method")
			params := providerMap(req["params"])
			if method != "thread-follower-update-thread-settings" || S(params, "conversationId") != thread ||
				!reflect.DeepEqual(providerMap(params["threadSettings"]), tc.settings) {
				t.Fatalf("unexpected setting request: %v", req)
			}
			if err := desktopTestWrite(desktop, M{
				"type": "response", "requestId": req["requestId"], "method": method,
				"resultType": "success", "result": M{"applied": tc.applied}, "handledByClientId": "desktop-owner",
			}); err != nil {
				t.Fatal(err)
			}
			select {
			case res := <-done:
				if (res.Code == http.StatusOK) != tc.applied {
					t.Fatalf("wrong apply outcome: %d %s", res.Code, res.Body)
				}
			case <-time.After(3 * time.Second):
				t.Fatal("setting request waited for another Desktop RPC")
			}
			info := a.Store.Get(sid).Info
			if S(info, "model") != tc.model || S(info, "effort") != tc.effort || B(info, "fastMode") != tc.fast {
				t.Fatalf("unexpected saved settings: %v", info)
			}
		})
	}
}
