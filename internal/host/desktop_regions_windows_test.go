//go:build windows

package host

import (
	"bytes"
	"fmt"
	"image"
	"image/draw"
	"os"
	"testing"
	"time"
)

func TestDesktopRegionFramesReconstruct(t *testing.T) {
	size := image.Pt(997, 515)
	full := image.Rectangle{Max: size}
	opts := desktopFrameOptions{codec: "png", native: true}
	s := desktopRegionState{id: "test"}
	raw := make([]byte, size.X*size.Y*4)
	reconstructed := image.NewRGBA(full)
	for i := 0; i < 5; i++ {
		frame := desktopFrame{layout: "first", capture: "dxgi", etag: fmt.Sprint(i)}
		switch i {
		case 1:
			paintDesktopTestRect(raw, size, image.Rect(100, 100, 129, 125), 25)
		case 2:
			paintDesktopTestRect(raw, size, image.Rect(128, 180, 205, 283), 101)
			paintDesktopTestRect(raw, size, image.Rect(927, 415, 997, 515), 192)
		case 4:
			paintDesktopTestRect(raw, size, full, 107)
		}
		fresh := s.begin(size, &frame, opts.previousETag)
		s.update(raw, size.X*4, full, fresh)
		if err := encodeDesktopUpdate(&s, fresh, &frame, opts); err != nil {
			t.Fatal(err)
		}
		if i == 3 {
			if len(frame.data) != 0 || frame.etag != opts.previousETag {
				t.Fatal("static frame must reuse its validator")
			}
			continue
		}
		if frame.etag == "" || frame.etag == opts.previousETag {
			t.Fatal("changed image needs new validator")
		}
		decoded, _, err := image.Decode(bytes.NewReader(frame.data))
		if err != nil {
			t.Fatal(err)
		}
		if len(frame.regions) == 0 {
			if i != 0 && i != 4 {
				t.Fatalf("sparse frame %d was encoded in full", i)
			}
			if decoded.Bounds() != full {
				t.Fatal("bad keyframe size")
			}
			draw.Draw(reconstructed, full, decoded, image.Point{}, draw.Src)
		} else {
			if frame.baseETag != opts.previousETag || (i == 1 && len(frame.regions) != 1) || (i == 2 && len(frame.regions) < 2) {
				t.Fatalf("patch frame %d: %q %v", i, frame.baseETag, frame.regions)
			}
			for _, r := range frame.regions {
				if !r.dest.In(full) || !(image.Rectangle{Min: r.source, Max: r.source.Add(r.dest.Size())}).In(decoded.Bounds()) {
					t.Fatal("invalid atlas bounds")
				}
				draw.Draw(reconstructed, r.dest, decoded, r.source, draw.Src)
			}
		}
		for p := 0; p < len(raw); p += 4 {
			if reconstructed.Pix[p] != raw[p+2] || reconstructed.Pix[p+1] != raw[p+1] || reconstructed.Pix[p+2] != raw[p] || reconstructed.Pix[p+3] != 255 {
				t.Fatalf("mismatched pixel %d on frame %d", p/4, i)
			}
		}
		opts.previousETag = frame.etag
	}
}

func paintDesktopTestRect(raw []byte, size image.Point, r image.Rectangle, v byte) {
	for y := r.Min.Y; y < r.Max.Y; y++ {
		for x := r.Min.X; x < r.Max.X; x++ {
			p := (y*size.X + x) * 4
			raw[p], raw[p+1], raw[p+2], raw[p+3] = byte(x/5+y/7)^v, byte(x/23-y/13)^v, byte(x/3+y/41)^v, 255
		}
	}
}

func TestDesktopCompositedCursorToDirectOnIdleMonitor(t *testing.T) {
	size := image.Pt(528, 165)
	pixels := make([]byte, size.X*size.Y*4)
	cursorX, cursorY := 133, 19
	p := (cursorY*size.X + cursorX) * 4
	opts := desktopFrameOptions{codec: "png"}
	s := desktopRegionState{id: "test"}
	frame := desktopFrame{capture: "dxgi", layout: "same"}
	s.begin(size, &frame, "")
	s.generations["left"] = 4
	s.tag = "previous"
	pixels[p] = 251
	fresh := s.updateComposite(pixels, size, &frame, "previous")
	if err := encodeDesktopUpdate(&s, fresh, &frame, opts); err != nil {
		t.Fatal(err)
	}
	if _, ok := s.generations["left"]; ok {
		t.Fatal("native cursor could remain on an idle hardware output")
	}
	opts.previousETag = frame.etag
	s.begin(size, &frame, opts.previousETag)
	// The left output is unaligned to the global tile grid and has padded rows.
	dest := image.Rect(8, 3, 263, 164)
	pitch := dest.Dx()*4 + 32
	s.update(make([]byte, pitch*dest.Dy()), pitch, dest, false)
	if s.pixels[p] != 0 {
		t.Fatal("old cursor was not removed")
	}
	found := false
	for _, r := range s.changedRects() {
		if image.Pt(cursorX, cursorY).In(r) {
			found = true
		}
	}
	if !found {
		t.Fatal("cursor removal not transmitted")
	}
}

func TestDesktopDXGIDamageUsesMatchingGeneration(t *testing.T) {
	size := image.Pt(641, 199)
	bounds := image.Rectangle{Max: size}
	offset := image.Pt(67, 9)
	raw := make([]byte, size.X*size.Y*4)
	s := desktopRegionState{id: "damage"}
	frame := desktopFrame{capture: "dxgi", layout: "stable"}
	s.begin(size.Add(offset), &frame, "")
	out := &duplicatedOutput{size: size, previousGeneration: 3, generation: 4, damage: []image.Rectangle{image.Rect(9, 7, 258, 79), image.Rect(518, 111, 548, 133)}}
	part := desktopOutputPart{display: desktopDisplay{ID: "a"}, destination: bounds.Add(offset)}
	out.updateRegions(&s, part, true, raw, size.X*4)
	s.generations["a"] = 3
	for i, r := range out.damage {
		paintDesktopTestRect(raw, size, r, byte(i+17))
	}
	out.updateRegions(&s, part, false, raw, size.X*4)
	assertSame := func() {
		for y := 0; y < size.Y; y++ {
			base := ((y+offset.Y)*s.size.X + offset.X) * 4
			if !bytes.Equal(raw[y*size.X*4:(y+1)*size.X*4], s.pixels[base:base+size.X*4]) {
				t.Fatalf("missing changed pixels in row %d", y)
			}
		}
	}
	assertSame()
	// A separate stream consumed generation 5 including a change outside the
	// most recent damage metadata. A viewer on generation 4 needs the full output.
	s.generations["a"] = 4
	out.previousGeneration, out.generation = 5, 6
	paintDesktopTestRect(raw, size, image.Rect(0, 150, 600, 153), 91)
	out.updateRegions(&s, part, false, raw, size.X*4)
	assertSame()
}

// Same video scene and clock updates in each mode; the region path includes
// comparison and atlas construction, and the legacy path encodes the entire
// desktop. Both produce the selected native resolution and quality.
func BenchmarkDesktopMovingRegions(b *testing.B) {
	size := image.Pt(5760, 2160)
	full := image.Rectangle{Max: size}
	for _, scene := range []struct {
		name  string
		video image.Rectangle
	}{
		{"window", image.Rect(128, 192, 896, 624)}, {"monitor", image.Rect(0, 0, 1920, 1080)}, {"dense", full},
	} {
		for _, negotiated := range []bool{false, true} {
			mode := "full"
			if negotiated {
				mode = "regions"
			}
			b.Run(scene.name+"/"+mode, func(b *testing.B) {
				raw := make([]byte, size.X*size.Y*4)
				paintDesktopTestRect(raw, size, full, 31)
				opts := desktopFrameOptions{codec: "jpeg", native: true}
				s := desktopRegionState{id: "bench"}
				base := desktopFrame{layout: "bench", capture: "dxgi"}
				s.begin(size, &base, "")
				s.update(raw, size.X*4, full, true)
				s.tag = "first"
				opts.previousETag = s.tag
				var sent int64
				b.ResetTimer()
				for i := 0; i < b.N; i++ {
					b.StopTimer()
					paintDesktopTestRect(raw, size, scene.video, byte(i)<<2)
					paintDesktopTestRect(raw, size, image.Rect(5652, 1500, 5716, 1524), byte(i+7))
					b.StartTimer()
					frame := desktopFrame{layout: "bench", capture: "dxgi"}
					var err error
					if negotiated {
						fresh := s.begin(size, &frame, opts.previousETag)
						s.update(raw, size.X*4, full, fresh)
						err = encodeDesktopUpdate(&s, fresh, &frame, opts)
						opts.previousETag = frame.etag
					} else {
						frame.data, err = encodeDesktopPixels(raw, size, opts, false)
					}
					if err != nil {
						b.Fatal(err)
					}
					sent += int64(len(frame.data))
				}
				b.ReportMetric(float64(sent)/float64(b.N), "bytes/frame")
			})
		}
	}
}

func TestDesktopReadOnlyRegionCapture(t *testing.T) {
	if os.Getenv("REMOTE_CODE_TEST_DESKTOP_CAPTURE") != "1" {
		t.Skip("read-only live desktop test")
	}
	opts := desktopFrameOptions{capture: "dxgi", codec: "jpeg", native: true, separateCursor: true, regions: &desktopRegionState{id: "live"}}
	frames, changes, bytesSent := 0, 0, 0
	var duration time.Duration
	for i := 0; i < 36; i++ {
		start := time.Now()
		frame, err := captureDesktopFrame("all", opts)
		if err != nil {
			t.Fatal(err)
		}
		duration += time.Since(start)
		if len(frame.data) > 0 {
			frames++
			if _, _, err = image.DecodeConfig(bytes.NewReader(frame.data)); err != nil {
				t.Fatal(err)
			}
			bytesSent += len(frame.data)
		}
		if len(frame.regions) > 0 {
			if frame.baseETag != opts.previousETag {
				t.Fatal("invalid live frame base")
			}
			changes++
		}
		opts.previousETag = frame.etag
		time.Sleep(16 * time.Millisecond)
	}
	t.Logf("36 native samples %v, %d image frames, %d region updates, %d total bytes, average PC %v", opts.regions.size, frames, changes, bytesSent, duration/36)
}
