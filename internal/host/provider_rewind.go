package host

import (
	"bufio"
	"encoding/json"
	"errors"
	"os"
	"regexp"
	"strings"
)

type claudeRewindPrompt struct{ UUID, Parent string }

var claudeMessageID = regexp.MustCompile(`(?i)^([a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12})(?::\d+)?$`)

func rewindText(text string) string { return strings.Join(strings.Fields(text), " ") }
func (p *nativeProvider) resolveClaudeRewind(rec *Record, index int) (claudeRewindPrompt, error) {
	path := p.transcriptPath(S(rec.Info, "externalId"))
	if path == "" {
		return claudeRewindPrompt{}, errors.New("This message was not found in the PC conversation history")
	}
	file, err := os.Open(path)
	if err != nil {
		return claudeRewindPrompt{}, err
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), 32*1024*1024)
	byID := map[string]M{}
	last := ""
	for scanner.Scan() {
		var row M
		if json.Unmarshal(scanner.Bytes(), &row) != nil {
			continue
		}
		id := S(row, "uuid")
		if id == "" {
			continue
		}
		kind := S(row, "type")
		valid := kind == "user" && !B(row, "isMeta") && !B(row, "isSidechain")
		content := providerMap(row["message"])["content"]
		for _, block := range providerRows(content) {
			if S(block, "type") == "tool_result" {
				valid = false
			}
		}
		origin := providerMap(row["origin"])
		if len(origin) > 0 && S(origin, "kind") != "human" {
			valid = false
		}
		byID[id] = M{"parent": row["parentUuid"], "logicalParent": row["logicalParentUuid"], "valid": valid, "text": rewindText(providerText(content))}
		if (kind == "user" || kind == "assistant" || kind == "system") && !B(row, "isSidechain") {
			last = id
		}
	}
	if err = scanner.Err(); err != nil {
		return claudeRewindPrompt{}, err
	}
	chain := []string{}
	seen := map[string]bool{}
	for id := last; id != "" && !seen[id]; {
		seen[id] = true
		row := byID[id]
		if row == nil {
			break
		}
		chain = append(chain, id)
		id = S(row, "parent")
		if id == "" {
			id = S(row, "logicalParent")
		}
	}
	target := rec.Entries[index]
	direct := ""
	if match := claudeMessageID.FindStringSubmatch(S(target, "id")); match != nil {
		direct = match[1]
	}
	if direct != "" && seen[direct] && B(byID[direct], "valid") {
		return claudeRewindPrompt{direct, S(byID[direct], "parent")}, nil
	}
	want := rewindText(S(target, "text"))
	occurrence := 0
	for _, entry := range rec.Entries[:index] {
		if S(entry, "kind") == "user" && rewindText(S(entry, "text")) == want {
			occurrence++
		}
	}
	matched := 0
	for i := len(chain) - 1; i >= 0; i-- {
		id := chain[i]
		row := byID[id]
		if B(row, "valid") && S(row, "text") == want {
			if matched == occurrence {
				return claudeRewindPrompt{id, S(row, "parent")}, nil
			}
			matched++
		}
	}
	return claudeRewindPrompt{}, errors.New("This message was not found in the current PC conversation history")
}
