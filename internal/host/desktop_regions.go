package host

import (
	"bytes"
	"image"
	"sort"
)

const desktopTile = 128

type desktopRegion struct {
	dest   image.Rectangle
	source image.Point
}

// Each negotiated stream owns its exact last pixel image. A stalled or second
// viewer cannot change another viewer's base. No chain survives a reconnect.
type desktopRegionState struct {
	id, layout, method, tag, sourceTag string
	size                               image.Point
	pixels, atlas                      []byte
	dirty                              []bool
	generations                        map[string]uint64
	sequence                           uint64
}

func (s *desktopRegionState) begin(size image.Point, frame *desktopFrame, previous string) bool {
	fresh := previous == "" || previous != s.tag || s.size != size || s.layout != frame.layout || s.method != frame.capture
	if fresh {
		s.size, s.layout, s.method = size, frame.layout, frame.capture
		// Gaps between offset monitors are black, even after a layout change.
		s.pixels = make([]byte, size.X*size.Y*4)
		s.dirty = make([]bool, ((size.X+desktopTile-1)/desktopTile)*((size.Y+desktopTile-1)/desktopTile))
		s.generations = make(map[string]uint64)
	} else {
		clear(s.dirty)
	}
	// An error partway through a capture invalidates the partially updated base.
	s.tag = ""
	return fresh
}

func (s *desktopRegionState) updateComposite(pixels []byte, size image.Point, frame *desktopFrame, previous string) bool {
	fresh := s.begin(size, frame, previous)
	// A composed bitmap may contain a custom cursor in an otherwise idle
	// monitor. Resample every monitor when switching back to direct GPU input.
	clear(s.generations)
	s.update(pixels, size.X*4, image.Rectangle{Max: size}, fresh)
	return fresh
}

// Compare visible pixels of a monitor against its location in the virtual
// desktop. Tiling also handles drivers that invalidate a whole monitor when
// only its clock or video window changed.
func (s *desktopRegionState) update(src []byte, pitch int, dest image.Rectangle, fresh bool) {
	if dest.Empty() {
		return
	}
	stride := s.size.X * 4
	if fresh {
		copyDesktopRect(s.pixels, stride, dest.Min, src, pitch, image.Rectangle{Max: dest.Size()})
		return
	}
	cols := (s.size.X + desktopTile - 1) / desktopTile
	// Read in scanline order. Mapped PCIe staging surfaces can be uncached on
	// discrete GPUs; traversing columns causes a separate bus read per tile.
	for y := dest.Min.Y; y < dest.Max.Y; y++ {
		offset := (y - dest.Min.Y) * pitch
		start := y*stride + dest.Min.X*4
		row := src[offset : offset+dest.Dx()*4]
		old := s.pixels[start : start+len(row)]
		if bytes.Equal(old, row) {
			continue
		}
		allDirty := true
		for x := dest.Min.X; x < dest.Max.X; {
			tx := x / desktopTile
			right := min((tx+1)*desktopTile, dest.Max.X)
			a, b := (x-dest.Min.X)*4, (right-dest.Min.X)*4
			idx := (y/desktopTile)*cols + tx
			if s.dirty[idx] || !bytes.Equal(old[a:b], row[a:b]) {
				s.dirty[idx] = true
				copy(old[a:b], row[a:b])
			} else {
				allDirty = false
			}
			x = right
		}
		// A dense video frame often dirties the whole band in its first
		// scanline. Copy the remaining lines at memory bandwidth.
		if allDirty {
			end := min((y/desktopTile+1)*desktopTile, dest.Max.Y)
			if y+1 < end {
				area := image.Rect(dest.Min.X, y+1, dest.Max.X, end)
				copyDesktopRect(s.pixels, stride, area.Min, src, pitch, area.Sub(dest.Min))
			}
			y = end - 1
		}
	}
}

func copyDesktopRect(dst []byte, stride int, at image.Point, src []byte, pitch int, area image.Rectangle) {
	n := area.Dx() * 4
	from, to := area.Min.Y*pitch+area.Min.X*4, at.Y*stride+at.X*4
	if n == pitch && n == stride {
		copy(dst[to:to+n*area.Dy()], src[from:from+n*area.Dy()])
		return
	}
	for y := 0; y < area.Dy(); y++ {
		copy(dst[to:to+n], src[from:from+n])
		from, to = from+pitch, to+stride
	}
}

// Combine adjacent changed tile runs. Fragmented scenes use at most eight
// nonoverlapping sector bounds, keeping both the wire header and decoder work
// bounded without joining distant changes across monitors.
func (s *desktopRegionState) changedRects() []image.Rectangle {
	cols := (s.size.X + desktopTile - 1) / desktopTile
	rows := len(s.dirty) / cols
	var sectors [8]image.Rectangle
	var rects []image.Rectangle
	bounds := image.Rectangle{Max: s.size}
	for y := 0; y < rows; y++ {
		for x := 0; x < cols; {
			if !s.dirty[y*cols+x] {
				x++
				continue
			}
			start := x
			for x < cols && s.dirty[y*cols+x] {
				tile := image.Rect(x*desktopTile, y*desktopTile, (x+1)*desktopTile, (y+1)*desktopTile).Intersect(bounds)
				index := (y*2/rows)*4 + x*4/cols
				sectors[index] = sectors[index].Union(tile)
				x++
			}
			if len(rects) > 8 {
				continue
			}
			run := image.Rect(start*desktopTile, y*desktopTile, x*desktopTile, (y+1)*desktopTile).Intersect(bounds)
			merged := false
			for i, r := range rects {
				if r.Max.Y == run.Min.Y && r.Min.X == run.Min.X && r.Max.X == run.Max.X {
					rects[i].Max.Y = run.Max.Y
					merged = true
					break
				}
			}
			if !merged {
				rects = append(rects, run)
			}
		}
	}
	if len(rects) > 8 {
		rects = rects[:0]
		for _, r := range sectors {
			if !r.Empty() {
				rects = append(rects, r)
			}
		}
	}
	return rects
}

// Pack all changes into a single encoded image. Keeping the source and
// destination offsets on JPEG MCU boundaries avoids chroma bleed between
// regions and keeps untouched pixels consistent with the original keyframe.
func packDesktopRegions(rects []image.Rectangle, size image.Point) ([]desktopRegion, image.Point) {
	sort.Slice(rects, func(i, j int) bool { return rects[i].Dy() > rects[j].Dy() })
	maxWidth := 0
	for _, r := range rects {
		maxWidth = max(maxWidth, r.Dx())
	}
	bestArea := size.X * size.Y * 4 / 5
	var best []desktopRegion
	var bestSize image.Point
	for _, width := range []int{maxWidth, max(maxWidth, size.X/2), size.X} {
		var packed []desktopRegion
		var used image.Point
		x, y, rowHeight := 0, 0, 0
		for _, r := range rects {
			if x+r.Dx() > width {
				x = 0
				y += (rowHeight + 15) &^ 15
				rowHeight = 0
			}
			packed = append(packed, desktopRegion{dest: r, source: image.Pt(x, y)})
			used.X = max(used.X, x+r.Dx())
			used.Y = max(used.Y, y+r.Dy())
			rowHeight = max(rowHeight, r.Dy())
			x += (r.Dx() + 15) &^ 15
		}
		if area := used.X * used.Y; used.Y <= size.Y && area < bestArea {
			best, bestSize, bestArea = packed, used, area
		}
	}
	return best, bestSize
}
