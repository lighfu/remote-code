package host

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

const fxEndpoint = "https://open.er-api.com/v6/latest/USD"
const fxLifetime = 12 * time.Hour

type fxQuote struct {
	Rate  float64            `json:"rate"`
	Rates map[string]float64 `json:"rates,omitempty"`
	At    time.Time          `json:"at"`
}

type fxRates struct {
	mu    sync.RWMutex
	quote fxQuote
}

func fxValidRate(rate float64) bool {
	return rate > 0 && !math.IsInf(rate, 0)
}

func (f *fxRates) restore(data string) {
	raw, err := os.ReadFile(filepath.Join(data, "fx-usdjpy.json"))
	if err != nil {
		return
	}
	var saved fxQuote
	if json.Unmarshal(raw, &saved) == nil && fxValidRate(saved.Rate) && !saved.At.IsZero() && !saved.At.After(time.Now()) {
		f.quote = saved
	}
}

func (f *fxRates) usdJpy(fallback float64) float64 {
	f.mu.RLock()
	defer f.mu.RUnlock()
	if f.quote.Rate > 0 {
		return f.quote.Rate
	}
	return fallback
}

// Published tables are immutable. Older caches contain a JPY quote only.
func (f *fxRates) usdRates() map[string]float64 {
	f.mu.RLock()
	defer f.mu.RUnlock()
	if len(f.quote.Rates) != 0 {
		return f.quote.Rates
	}
	rates := map[string]float64{"USD": 1}
	if fxValidRate(f.quote.Rate) {
		rates["JPY"] = f.quote.Rate
	}
	return rates
}

func (f *fxRates) refreshLoop(ctx context.Context, data string) {
	for ctx.Err() == nil {
		delay := time.Until(f.quote.At.Add(fxLifetime))
		if delay <= 0 || len(f.quote.Rates) == 0 {
			probe, cancel := context.WithTimeout(ctx, 10*time.Second)
			rates, err := fetchUSDRates(probe, fxEndpoint)
			cancel()
			delay = 5 * time.Minute
			if err == nil {
				quote := fxQuote{Rate: rates["JPY"], Rates: rates, At: time.Now()}
				f.mu.Lock()
				f.quote = quote
				f.mu.Unlock()
				_ = atomicJSON(filepath.Join(data, "fx-usdjpy.json"), quote)
				delay = fxLifetime
			}
		}
		timer := time.NewTimer(delay)
		select {
		case <-ctx.Done():
			timer.Stop()
			return
		case <-timer.C:
		}
	}
}

func fetchUSDRates(ctx context.Context, endpoint string) (map[string]float64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, err
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	var payload struct {
		Base  string `json:"base_code"`
		Rates map[string]float64
	}
	if res.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(res.Body, 64<<10)).Decode(&payload) != nil ||
		(payload.Base != "" && payload.Base != "USD") || !fxValidRate(payload.Rates["JPY"]) {
		return nil, errors.New("USD exchange rates unavailable")
	}
	for code, rate := range payload.Rates {
		if len(code) != 3 || !fxValidRate(rate) {
			delete(payload.Rates, code)
		}
	}
	payload.Rates["USD"] = 1
	return payload.Rates, nil
}
