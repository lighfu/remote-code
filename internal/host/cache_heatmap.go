package host

const cacheHeatmapMaxCells = 128
const cacheHeatmapLowHit = 0.9

type cacheHeatmapCell struct {
	Read  float64 `json:"read"`
	Input float64 `json:"input"`
	Low   int     `json:"low"`
}

func (c *cacheHeatmapCell) apply(usage analyticsUsage, direction float64) {
	read := max(0, usage.Read)
	input := read + max(0, usage.Fresh) + max(0, usage.Write5) + max(0, usage.Write1)
	c.Read += direction * read
	c.Input += direction * input
	if input > 0 && read < cacheHeatmapLowHit*input {
		c.Low += int(direction)
	}
}

// Keep equally sized groups in recorded request order and coalesce neighbors
// as the transcript grows. The last group can be partially filled.
type cacheHeatmap struct {
	RequestsPerCell int                `json:"requestsPerCell"`
	Cells           []cacheHeatmapCell `json:"cells"`
}

func (h *cacheHeatmap) append(index int, usage analyticsUsage) {
	if h.RequestsPerCell == 0 {
		h.RequestsPerCell = 1
	}
	if index/h.RequestsPerCell >= cacheHeatmapMaxCells {
		for i := range len(h.Cells) / 2 {
			left, right := h.Cells[i*2], h.Cells[i*2+1]
			h.Cells[i] = cacheHeatmapCell{Read: left.Read + right.Read, Input: left.Input + right.Input, Low: left.Low + right.Low}
		}
		h.Cells = h.Cells[:len(h.Cells)/2]
		h.RequestsPerCell *= 2
	}
	cell := index / h.RequestsPerCell
	if cell == len(h.Cells) {
		h.Cells = append(h.Cells, cacheHeatmapCell{})
	}
	h.Cells[cell].apply(usage, 1)
}

func (h *cacheHeatmap) replace(index int, old, updated analyticsUsage) {
	cell := &h.Cells[index/h.RequestsPerCell]
	cell.apply(old, -1)
	cell.apply(updated, 1)
}
