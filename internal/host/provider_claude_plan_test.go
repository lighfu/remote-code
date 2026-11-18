package host

import (
	"reflect"
	"testing"
)

func claudePlanTestRow(kind, at string, blocks ...M) M {
	return M{"type": kind, "timestamp": at, "message": M{"content": blocks}}
}

func claudePlanTestCall(id, name string, input M) M {
	return M{"type": "tool_use", "id": id, "name": name, "input": input}
}

func TestClaudeDesktopPlanFromTranscript(t *testing.T) {
	const update = "mcp__ccd_turn__update_plan"
	start := claudePlanTestCall("start", update, M{"scope": "work", "items": []M{
		{"content": "Inspect", "status": "in_progress"},
		{"content": "Fix"},
		{"content": "Verify"},
	}})
	var state claudePlanState
	state.observe(claudePlanTestRow("api-request-blob", "", start))
	if state.touched || state.snapshot() != nil {
		t.Fatal("request payload replay made a plan")
	}
	state.observe(claudePlanTestRow("assistant", "2026-11-17T14:49:34.298Z", start))
	advance := claudePlanTestCall("advance", update, M{"advance": true})
	state.observe(claudePlanTestRow("assistant", "2026-11-17T14:55:57.029Z", advance))
	state.observe(claudePlanTestRow("assistant", "2026-11-17T14:56:00Z", advance))
	side := claudePlanTestRow("assistant", "2026-11-17T14:56:01Z", claudePlanTestCall("side", update, M{"items": []M{}}))
	side["isSidechain"] = true
	state.observe(side)
	got := state.snapshot()
	want := M{"items": []M{
		{"content": "Inspect", "status": "completed"},
		{"content": "Fix", "status": "in_progress"},
		{"content": "Verify", "status": "pending"},
	}, "updatedAt": int64(1794927357029)}
	if !state.touched || !reflect.DeepEqual(got, want) {
		t.Fatalf("after advance = %#v, want %#v", got, want)
	}
	state.observe(claudePlanTestRow("assistant", "2026-11-17T14:56:01Z", claudePlanTestCall("go-to", update, M{"done": []any{1.0, 2.5, 6.0}, "start": 3.0})))
	items := providerRows(state.snapshot()["items"])
	if S(items[1], "status") != "pending" || S(items[2], "status") != "in_progress" {
		t.Fatalf("start = %#v", items)
	}
	state.observe(claudePlanTestRow("assistant", "2026-11-17T14:58:23.672Z", claudePlanTestCall("finish", "mcp__ccd_turn__wrap_up", M{"recap": "complete"})))
	for _, item := range providerRows(state.snapshot()["items"]) {
		if S(item, "status") != "completed" {
			t.Fatalf("wrap-up did not finish item: %#v", item)
		}
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatal("later plan events mutated a previous snapshot")
	}
}

func TestClaudeTodoPlanCanBeCleared(t *testing.T) {
	var state claudePlanState
	state.observe(claudePlanTestRow("assistant", "", claudePlanTestCall("todo", "TodoWrite", M{"todos": []M{
		{"content": "Verify", "status": "in_progress", "activeForm": "Verifying"},
	}})))
	state.observe(claudePlanTestRow("assistant", "", claudePlanTestCall("invalid", "TodoWrite", M{})))
	want := []M{{"content": "Verify", "status": "in_progress", "activeForm": "Verifying"}}
	if got := providerRows(state.snapshot()["items"]); !reflect.DeepEqual(got, want) {
		t.Fatalf("todos = %#v, want %#v", got, want)
	}
	state.observe(claudePlanTestRow("assistant", "", claudePlanTestCall("clear", "TodoWrite", M{"todos": []any{}})))
	if !state.touched || state.snapshot() != nil {
		t.Fatalf("explicit empty todo list retained a plan: %#v", state.snapshot())
	}
}

func TestClaudeTaskPlanUsesToolResultIDs(t *testing.T) {
	var state claudePlanState
	state.observe(claudePlanTestRow("assistant", "", claudePlanTestCall("create", "TaskCreate", M{"subject": "Verify", "activeForm": "Verifying"})))
	state.observe(claudePlanTestRow("user", "", M{"type": "tool_result", "tool_use_id": "unrelated", "content": "Task #99 created"}))
	if state.touched || state.snapshot() != nil {
		t.Fatal("unrelated result created a task")
	}
	state.observe(claudePlanTestRow("user", "", M{"type": "tool_result", "tool_use_id": "create", "content": []M{{"type": "text", "text": "Task #7 created successfully"}}}))
	state.observe(claudePlanTestRow("assistant", "", claudePlanTestCall("update", "TaskUpdate", M{"taskId": 7.0, "status": "completed"})))
	want := []M{{"content": "Verify", "status": "completed", "activeForm": "Verifying"}}
	if got := providerRows(state.snapshot()["items"]); !reflect.DeepEqual(got, want) {
		t.Fatalf("tasks = %#v, want %#v", got, want)
	}
	state.observe(claudePlanTestRow("assistant", "", claudePlanTestCall("delete", "TaskUpdate", M{"taskId": "7", "status": "deleted"})))
	if !state.touched || state.snapshot() != nil {
		t.Fatalf("deletion retained task: %#v", state.snapshot())
	}
}
