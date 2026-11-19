package host

import "time"

// Rates are API equivalents per million tokens. The published tables distinguish
// the whole request's input length, the service tier and the write lifetime.
// https://developers.openai.com/api/docs/pricing
// https://developers.openai.com/api/docs/guides/prompt-caching
func codexCharge(p M, u analyticsUsage) (cost, saved float64, costKnown, savedKnown bool) {
	input, hasInput := reportedAmount(p, "input")
	read, hasRead := reportedAmount(p, "cachedInput")
	output, hasOutput := reportedAmount(p, "output")
	shortWrite, hasShort := reportedAmount(p, "cacheWrite30m")
	longWrite, hasLong := reportedAmount(p, "cacheWrite12h")
	shortCount := u.Write5 - u.CodexWrite12h
	noCache := u.Read+u.Write5 == 0

	multiplier := 1.0
	switch u.Tier {
	case "", "default", "standard", "auto":
	case "fast", "priority":
		multiplier = N(p, "priorityMultiplier")
	case "flex":
		multiplier = N(p, "flexMultiplier")
	case "ultrafast":
		multiplier = N(p, "ultrafastMultiplier")
		if ends, err := time.Parse(time.RFC3339, S(p, "ultrafastPromoUntil")); err == nil && u.At > 0 && u.At < ends.UnixMilli() {
			multiplier = N(p, "ultrafastPromoMultiplier")
		}
	default:
		multiplier = 0
	}
	if multiplier <= 0 {
		return 0, 0, false, noCache
	}
	if threshold := N(p, "longContextAbove"); threshold > 0 && u.Read+u.Fresh+u.Write5 > threshold {
		input, read, shortWrite, longWrite = input*2, read*2, shortWrite*2, longWrite*2
		output *= 1.5
	}
	writesKnown := (shortCount == 0 || hasShort) && (u.CodexWrite12h == 0 || hasLong)
	costKnown = writesKnown && (u.Fresh == 0 || hasInput) && (u.Read == 0 || hasRead) && (u.Output == 0 || hasOutput)
	savedKnown = noCache || writesKnown && hasInput && (u.Read == 0 || hasRead)
	writeCost := shortCount*shortWrite + u.CodexWrite12h*longWrite
	if costKnown {
		cost = (u.Fresh*input + u.Read*read + writeCost + u.Output*output) * multiplier / 1e6
	}
	if savedKnown {
		saved = (u.Read*(input-read) + u.Write5*input - writeCost) * multiplier / 1e6
	}
	return
}
