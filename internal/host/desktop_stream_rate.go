package host

import (
	"math"
	"time"
)

// This state belongs to one negotiated stream. Durations spent capturing and
// writing count towards the next deadline; a busy sender never catches up by
// capturing a burst of old frames.
type desktopStreamRate struct {
	bytesPerSecond float64
	targetPeriod   time.Duration
	capturePeriod  time.Duration
	meanCost       time.Duration
	debt           time.Duration
	lastSample     time.Time
	jpegDensity    float64
}

func (r *desktopStreamRate) nextPeriod(start time.Time, capture time.Duration, bodyBytes int) time.Duration {
	elapsed := start.Sub(r.lastSample)
	first := r.lastSample.IsZero()
	r.lastSample = start
	r.debt = max(0, r.debt-elapsed)
	if bodyBytes == 0 {
		// Resume checking at the requested rate for cursor changes and new motion.
		r.meanCost = 0
		return max(r.targetPeriod, r.debt)
	}
	if first {
		r.capturePeriod = capture
	} else {
		r.capturePeriod += (capture - r.capturePeriod) / 8
	}
	cost := time.Duration(float64(time.Second) * float64(bodyBytes) / r.bytesPerSecond)
	if first {
		// Allow a single full-size keyframe on connection. Its size says
		// little about the following patch stream.
		cost = min(cost, 2*r.targetPeriod)
	}
	r.debt = min(2*time.Second, r.debt+cost)
	if r.meanCost == 0 {
		r.meanCost = cost
	} else {
		r.meanCost += (cost - r.meanCost) / 4
	}
	// Average over short fluctuations in patch size. Limit bursts to 150 ms
	// of payload. If one lossless frame alone exceeds a multi-second budget,
	// still refresh the view within the mobile client's input freshness window.
	period := max(r.targetPeriod, r.meanCost, r.debt-150*time.Millisecond)
	return min(2*time.Second, period)
}

// A JPEG's expected size depends on both scene detail and quantization.
// Learn the density from the most recent encoded images and choose quality
// for THIS atlas size. Small text/cursor updates can use the original quality
// immediately after a large video update, at the same physical resolution.
func jpegSizeFactor(quality int) float64 {
	return math.Sqrt(float64(quality) / float64(100-quality))
}

func (r *desktopStreamRate) jpegQuality(pixels, maximum int) int {
	if r.jpegDensity == 0 {
		return maximum
	}
	period := max(r.targetPeriod, r.capturePeriod)
	bytes := period.Seconds() * r.bytesPerSecond
	factor := bytes / (r.jpegDensity * float64(pixels))
	squared := factor * factor
	return min(maximum, max(32, int(100*squared/(1+squared)+0.5)))
}

func (r *desktopStreamRate) observeJPEG(pixels, quality, length int) {
	// An almost empty JPEG consists mainly of its fixed headers and gives a
	// poor estimate of how compressible the next region of content will be.
	if pixels < 4096 {
		return
	}
	density := float64(length) / (float64(pixels) * jpegSizeFactor(quality))
	if r.jpegDensity == 0 {
		r.jpegDensity = density
	} else {
		r.jpegDensity += (density - r.jpegDensity) * .35
	}
}
