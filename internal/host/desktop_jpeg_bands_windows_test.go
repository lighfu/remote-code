package host

import (
	"bytes"
	"image"
	"image/jpeg"
	"slices"
	"testing"
)

func TestDesktopJPEGBandsMatchSerialPixels(t *testing.T) {
	if !desktopImagingReady() {
		t.Skip("Windows JPEG encoder unavailable")
	}
	// Nonaligned image edges, a short last band, low and high quality, and
	// enough bands to wrap the restart marker sequence.
	for _, size := range []image.Point{{X: 319, Y: 337}, {X: 512, Y: 336}} {
		raw := make([]byte, size.X*size.Y*4)
		paintDesktopTestRect(raw, size, image.Rectangle{Max: size}, 229)
		for _, quality := range []uint32{32, 86} {
			decode := func(data []byte) *image.YCbCr {
				t.Helper()
				img, err := jpeg.Decode(bytes.NewReader(data))
				if err != nil {
					t.Fatal(err)
				}
				return img.(*image.YCbCr)
			}
			serial := decode(desktopJPEGScan(raw, size, quality))
			parallel := decode(encodeDesktopJPEGBands(raw, size, quality, 11))
			if serial.Rect != parallel.Rect || serial.SubsampleRatio != parallel.SubsampleRatio ||
				!bytes.Equal(serial.Y, parallel.Y) || !bytes.Equal(serial.Cb, parallel.Cb) || !bytes.Equal(serial.Cr, parallel.Cr) {
				t.Fatalf("pixel mismatch for %v, quality %d", size, quality)
			}
		}
	}
}

func TestDesktopJPEGBandFallback(t *testing.T) {
	if !desktopImagingReady() {
		t.Skip("Windows JPEG encoder unavailable")
	}
	const rows = 32
	size := image.Pt(300, rows*2)
	raw := make([]byte, size.X*rows*4)
	first := desktopJPEGScan(raw, image.Pt(size.X, rows), 86)
	header, ok := parseDesktopJPEGHeader(first)
	if !ok {
		t.Fatal("unsupported native JPEG")
	}
	// GDI+ derives JFIF print density from each worker's DPI awareness.
	// Keep the capture thread's metadata and join identical scan formats.
	secondDPI := bytes.Clone(first)
	if header.densityOffset == 0 {
		t.Fatal("missing JFIF density")
	}
	copy(secondDPI[header.densityOffset:], []byte{0, 144, 0, 144})
	joined := joinDesktopJPEGBands([][]byte{first, secondDPI}, size, rows)
	if len(joined) == 0 || !bytes.Equal(joined[header.densityOffset:header.densityOffset+4], first[header.densityOffset:header.densityOffset+4]) {
		t.Fatal("worker DPI changed the stream metadata")
	}
	for _, tc := range []struct {
		name   string
		modify func([]byte) []byte
	}{
		{"short second band", func(b []byte) []byte { b[header.heightOffset+1]--; return b }},
		{"different width", func(b []byte) []byte { b[header.heightOffset+3]--; return b }},
		{"different sampling", func(b []byte) []byte { b[header.heightOffset+6] = 0x11; return b }},
		{"different quantization", func(b []byte) []byte { b[bytes.Index(b, []byte{0xff, 0xdb})+5]++; return b }},
		{"different huffman table", func(b []byte) []byte { b[bytes.Index(b, []byte{0xff, 0xc4})+22]++; return b }},
		{"progressive", func(b []byte) []byte { b[header.heightOffset-4] = 0xc2; return b }},
		{"restart interval", func(b []byte) []byte { return slices.Insert(b, header.scanOffset, 0xff, 0xdd, 0, 4, 0, 8) }},
		{"truncated", func(b []byte) []byte { return b[:header.scanStart-1] }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			second := tc.modify(bytes.Clone(first))
			if joinDesktopJPEGBands([][]byte{first, second}, size, rows) != nil {
				t.Fatal("accepted incompatible JPEG band")
			}
		})
	}
	// A restart interval must fit in 16 bits.
	tall := bytes.Clone(first)
	tall[header.heightOffset], tall[header.heightOffset+1] = 0x20, 0
	tall[header.heightOffset+2], tall[header.heightOffset+3] = 0x10, 0
	if joinDesktopJPEGBands([][]byte{tall, tall}, image.Pt(4096, 16384), 8192) != nil {
		t.Fatal("accepted too many blocks per band")
	}
}
