package host

import (
	"bytes"
	"encoding/binary"
	"image"
	"sync"
)

// GDI+ encodes JPEGs on a single core. Encode complete rows of JPEG blocks
// concurrently, then join the scans using standard restart markers. Each
// band begins with fresh DC predictors, exactly as at a restart marker.
// The client receives one ordinary baseline JPEG with the original sampling,
// quality, and dimensions.
func encodeDesktopJPEGBands(raw []byte, size image.Point, quality uint32, workers int) []byte {
	const alignment = 16 // covers GDI+'s 4:4:4, 4:2:2, and 4:2:0 sampling
	rows := (size.Y + alignment*workers - 1) / (alignment * workers) * alignment
	count := (size.Y + rows - 1) / rows
	if count < 2 {
		return nil
	}
	bands := make([][]byte, count)
	var wg sync.WaitGroup
	encode := func(i int) {
		top := i * rows
		bottom := min(size.Y, top+rows)
		stride := 4 * size.X
		bands[i] = desktopJPEGScan(raw[top*stride:bottom*stride], image.Pt(size.X, bottom-top), quality)
	}
	for i := 1; i < count; i++ {
		wg.Go(func() { encode(i) })
	}
	encode(0)
	wg.Wait()
	return joinDesktopJPEGBands(bands, size, rows)
}

type desktopJPEGHeader struct {
	densityOffset, heightOffset, scanOffset, scanStart int
	width, height, mcuWidth, mcuHeight                 int
}

// Accept only standalone, single-scan baseline JPEGs from the Windows encoder.
// Any unknown format (including progressive JPEG or pre-existing restart
// intervals) falls back to encoding the entire frame in one call.
func parseDesktopJPEGHeader(b []byte) (h desktopJPEGHeader, ok bool) {
	if len(b) < 6 || b[0] != 0xff || b[1] != 0xd8 || b[len(b)-2] != 0xff || b[len(b)-1] != 0xd9 {
		return h, false
	}
	var quant, huffman bool
	for i := 2; i+4 <= len(b)-2; {
		if b[i] != 0xff {
			return h, false
		}
		length := int(binary.BigEndian.Uint16(b[i+2:]))
		end := i + 2 + length
		if length < 2 || end > len(b)-2 {
			return h, false
		}
		marker := b[i+1]
		switch {
		case marker == 0xc0: // SOF0, 8-bit baseline with three components
			if h.heightOffset != 0 || length != 17 || b[i+4] != 8 || b[i+9] != 3 {
				return h, false
			}
			h.heightOffset = i + 5
			h.height = int(binary.BigEndian.Uint16(b[i+5:]))
			h.width = int(binary.BigEndian.Uint16(b[i+7:]))
			for c := range 3 {
				sampling := b[i+11+3*c]
				horiz, vert := int(sampling>>4), int(sampling&15)
				if horiz < 1 || horiz > 2 || vert < 1 || vert > 2 {
					return h, false
				}
				h.mcuWidth = max(h.mcuWidth, 8*horiz)
				h.mcuHeight = max(h.mcuHeight, 8*vert)
			}
		case marker == 0xdb:
			quant = true
		case marker == 0xc4:
			huffman = true
		case marker == 0xda: // SOS, one interleaved scan of all components
			if !quant || !huffman || h.heightOffset == 0 || length != 12 || b[i+4] != 3 ||
				b[end-3] != 0 || b[end-2] != 63 || b[end-1] != 0 || end >= len(b)-2 {
				return h, false
			}
			h.scanOffset, h.scanStart = i, end
			return h, true
		case marker == 0xe0 && i == 2 && length >= 16 && bytes.Equal(b[i+4:i+9], []byte("JFIF\x00")):
			// The capture thread has per-monitor DPI awareness. Worker threads
			// can report a different JFIF print density for identical pixels.
			h.densityOffset = i + 12
		case marker >= 0xe0 && marker <= 0xef, marker == 0xfe:
			// Application metadata or comment.
		default:
			return h, false
		}
		i = end
	}
	return h, false
}

func joinDesktopJPEGBands(bands [][]byte, size image.Point, rows int) []byte {
	if len(bands) < 2 || rows < 1 || size.Y > 65535 || (len(bands)-1)*rows >= size.Y || len(bands)*rows < size.Y {
		return nil
	}
	first := bands[0]
	h, ok := parseDesktopJPEGHeader(first)
	if !ok || h.width != size.X || h.height != rows || rows%h.mcuHeight != 0 {
		return nil
	}
	interval := ((size.X + h.mcuWidth - 1) / h.mcuWidth) * (rows / h.mcuHeight)
	if interval < 1 || interval > 65535 {
		return nil
	}
	total := h.scanStart + 6
	for i, band := range bands {
		p, ok := parseDesktopJPEGHeader(band)
		if !ok || p.height != min(rows, size.Y-i*rows) || h.heightOffset != p.heightOffset || h.scanStart != p.scanStart || h.densityOffset != p.densityOffset {
			return nil
		}
		start := 0
		if h.densityOffset != 0 {
			if !bytes.Equal(first[:h.densityOffset], band[:h.densityOffset]) {
				return nil
			}
			start = h.densityOffset + 4
		}
		if !bytes.Equal(first[start:h.heightOffset], band[start:h.heightOffset]) ||
			!bytes.Equal(first[h.heightOffset+2:h.scanStart], band[p.heightOffset+2:p.scanStart]) {
			// The scan must use exactly the same sampling and coding tables.
			return nil
		}
		total += len(band) - h.scanStart
	}
	out := make([]byte, 0, total)
	out = append(out, first[:h.scanOffset]...)
	binary.BigEndian.PutUint16(out[h.heightOffset:], uint16(size.Y))
	out = append(out, 0xff, 0xdd, 0, 4, byte(interval>>8), byte(interval))
	out = append(out, first[h.scanOffset:h.scanStart]...)
	for i, band := range bands {
		if i > 0 {
			out = append(out, 0xff, byte(0xd0+(i-1)%8))
		}
		out = append(out, band[h.scanStart:len(band)-2]...)
	}
	return append(out, 0xff, 0xd9)
}
