package host

import (
	"sync"
	"time"
)

// One in-flight snapshot per worktree, with a small shared limit on disk scans.
// Completed snapshots are immutable. Invalidation also detaches pending reads,
// so an older request cannot put pre-mutation status back into the cache.
type gitStatusCache struct {
	mu      sync.Mutex
	entries map[string]*gitStatusRead
	slots   chan struct{}
}

type gitStatusRead struct {
	done   chan struct{}
	until  time.Time // zero while in flight, accessed under the cache mutex
	status M
	err    error
}

func newGitStatusCache(concurrent int) *gitStatusCache {
	return &gitStatusCache{entries: make(map[string]*gitStatusRead), slots: make(chan struct{}, concurrent)}
}

func (r *gitStatusRead) wait() (M, error) {
	<-r.done
	return r.status, r.err
}

func (c *gitStatusCache) lookupLocked(key string, force bool) *gitStatusRead {
	r := c.entries[key]
	if r != nil && (r.until.IsZero() || (!force && time.Now().Before(r.until))) {
		return r
	}
	return nil
}

func (c *gitStatusCache) lookup(key string, force bool) *gitStatusRead {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.lookupLocked(key, force)
}

func (c *gitStatusCache) read(key string, force bool, load func() (M, error)) (M, error) {
	c.mu.Lock()
	if r := c.lookupLocked(key, force); r != nil {
		c.mu.Unlock()
		return r.wait()
	}
	now := time.Now()
	for path, r := range c.entries {
		if !r.until.IsZero() && !now.Before(r.until) {
			delete(c.entries, path)
		}
	}
	r := &gitStatusRead{done: make(chan struct{})}
	c.entries[key] = r
	c.mu.Unlock()

	c.slots <- struct{}{}
	r.status, r.err = load()
	<-c.slots

	c.mu.Lock()
	r.until = time.Now().Add(3 * time.Second)
	if r.err != nil && c.entries[key] == r {
		delete(c.entries, key)
	}
	close(r.done)
	c.mu.Unlock()
	return r.status, r.err
}

func (c *gitStatusCache) invalidate(key string) {
	c.mu.Lock()
	delete(c.entries, key)
	c.mu.Unlock()
}
