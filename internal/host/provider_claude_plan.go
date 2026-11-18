package host

import (
	"fmt"
	"regexp"
	"time"
)

type claudePlanItem struct {
	content, status, activeForm string
}

type claudePlanState struct {
	items     []claudePlanItem
	tasks     map[string]claudePlanItem
	taskOrder []string
	creating  map[string]claudePlanItem
	seen      map[string]bool
	useTasks  bool
	touched   bool
	updatedAt int64
}

var claudePlanTaskID = regexp.MustCompile(`#(\d+)`)

func claudePlanStatus(s string) string {
	if s == "in_progress" || s == "completed" {
		return s
	}
	return "pending"
}

func (p *claudePlanState) observe(row M) {
	kind := S(row, "type")
	if (kind != "assistant" && kind != "user") || B(row, "isSidechain") {
		return
	}
	for _, block := range providerRows(providerMap(row["message"])["content"]) {
		changed := false
		switch S(block, "type") {
		case "tool_use":
			changed = p.tool(block)
		case "tool_result":
			changed = p.taskResult(block)
		}
		if changed {
			p.touched = true
			if at, err := time.Parse(time.RFC3339Nano, S(row, "timestamp")); err == nil {
				p.updatedAt = at.UnixMilli()
			}
		}
	}
}

func (p *claudePlanState) tool(block M) bool {
	name, id := S(block, "name"), S(block, "id")
	switch name {
	case "mcp__ccd_turn__update_plan", "mcp__ccd_turn__wrap_up", "TodoWrite", "TaskCreate", "TaskUpdate":
	default:
		return false
	}
	if id != "" {
		if p.seen[id] {
			return false
		}
		if p.seen == nil {
			p.seen = map[string]bool{}
		}
		p.seen[id] = true
	}
	input := providerMap(block["input"])
	switch name {
	case "mcp__ccd_turn__update_plan":
		if p.replaceItems(input["items"]) {
			return true
		}
		return p.updateItems(input)
	case "mcp__ccd_turn__wrap_up":
		if p.useTasks || len(p.items) == 0 {
			return false
		}
		for i := range p.items {
			p.items[i].status = "completed"
		}
		return true
	case "TodoWrite":
		return p.replaceItems(input["todos"])
	case "TaskCreate":
		if id != "" {
			content := S(input, "subject")
			if content == "" {
				content = S(input, "description")
			}
			if p.creating == nil {
				p.creating = map[string]claudePlanItem{}
			}
			p.creating[id] = claudePlanItem{content, "pending", S(input, "activeForm")}
		}
	case "TaskUpdate":
		return p.updateTask(input)
	}
	return false
}

func (p *claudePlanState) replaceItems(value any) bool {
	switch value.(type) {
	case []any, []M:
	default:
		return false
	}
	items := providerRows(value)
	p.items = make([]claudePlanItem, 0, len(items))
	for _, item := range items {
		p.items = append(p.items, claudePlanItem{S(item, "content"), claudePlanStatus(S(item, "status")), S(item, "activeForm")})
	}
	p.useTasks = false
	return true
}

func claudePlanIndex(n float64, size int) int {
	i := int(n)
	if n < 1 || n > float64(size) || float64(i) != n {
		return -1
	}
	return i - 1
}

func (p *claudePlanState) updateItems(input M) bool {
	changed := false
	for _, value := range array(input["done"]) {
		if i := claudePlanIndex(N(M{"index": value}, "index"), len(p.items)); i >= 0 {
			p.items[i].status = "completed"
			changed = true
		}
	}
	if start := claudePlanIndex(N(input, "start"), len(p.items)); start >= 0 {
		for i := range p.items {
			if i == start {
				p.items[i].status = "in_progress"
			} else if p.items[i].status == "in_progress" {
				p.items[i].status = "pending"
			}
		}
		changed = true
	}
	if B(input, "advance") && len(p.items) > 0 {
		start := 0
		for i := range p.items {
			if p.items[i].status == "in_progress" {
				p.items[i].status = "completed"
				start = i + 1
				break
			}
		}
		for i := start; i < len(p.items); i++ {
			if p.items[i].status == "pending" {
				p.items[i].status = "in_progress"
				break
			}
		}
		changed = true
	}
	if changed {
		p.useTasks = false
	}
	return changed
}

func (p *claudePlanState) taskResult(block M) bool {
	id := S(block, "tool_use_id")
	item, ok := p.creating[id]
	if !ok {
		return false
	}
	delete(p.creating, id)
	if B(block, "is_error") {
		return false
	}
	text, ok := block["content"].(string)
	if !ok {
		text = providerPretty(block["content"])
	}
	match := claudePlanTaskID.FindStringSubmatch(text)
	if len(match) < 2 {
		return false
	}
	taskID := match[1]
	if p.tasks == nil {
		p.tasks = map[string]claudePlanItem{}
	}
	if _, exists := p.tasks[taskID]; !exists {
		p.taskOrder = append(p.taskOrder, taskID)
	}
	p.tasks[taskID] = item
	p.useTasks = true
	return true
}

func (p *claudePlanState) updateTask(input M) bool {
	id := fmt.Sprint(input["taskId"])
	item, ok := p.tasks[id]
	if !ok {
		return false
	}
	if status := S(input, "status"); status == "deleted" {
		delete(p.tasks, id)
		for i, ordered := range p.taskOrder {
			if ordered == id {
				p.taskOrder = append(p.taskOrder[:i], p.taskOrder[i+1:]...)
				break
			}
		}
	} else {
		if status != "" {
			item.status = claudePlanStatus(status)
		}
		if content := S(input, "subject"); content != "" {
			item.content = content
		}
		if activeForm := S(input, "activeForm"); activeForm != "" {
			item.activeForm = activeForm
		}
		p.tasks[id] = item
	}
	p.useTasks = true
	return true
}

func (p *claudePlanState) snapshot() M {
	items := p.items
	if p.useTasks {
		items = make([]claudePlanItem, 0, len(p.taskOrder))
		for _, id := range p.taskOrder {
			items = append(items, p.tasks[id])
		}
	}
	if len(items) == 0 {
		return nil
	}
	rows := make([]M, 0, len(items))
	for _, item := range items {
		row := M{"content": item.content, "status": item.status}
		if item.activeForm != "" {
			row["activeForm"] = item.activeForm
		}
		rows = append(rows, row)
	}
	return M{"items": rows, "updatedAt": p.updatedAt}
}
