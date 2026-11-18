//go:build windows

package host

import (
	"bytes"
	"errors"
	"image"
	"image/color"
	"os"
	"testing"
	"time"
	"unsafe"
)

func TestDesktopNativeDisplayCoordinates(t *testing.T) {
	displays := []desktopDisplay{
		{ID: "primary", Width: 1920, Height: 1080, Primary: true},
		{ID: "left", X: -1080, Y: -500, Width: 1080, Height: 1920},
	}
	target, virtual, err := desktopRectangles(displays, "left")
	if err != nil || target != image.Rect(-1080, -500, 0, 1420) || virtual != image.Rect(-1080, -500, 1920, 1420) {
		t.Fatalf("unexpected target %v, virtual %v, err %v", target, virtual, err)
	}
	for _, test := range []struct {
		id     string
		x, y   float64
		screen image.Point
	}{
		{"left", 0, 0, image.Pt(-1080, -500)},
		{"left", 1, 1, image.Pt(-1, 1419)},
		{"primary", 0, 0, image.Pt(0, 0)},
		{"primary", 1, 1, image.Pt(1919, 1079)},
		{"all", 0, 0, image.Pt(-1080, -500)},
	} {
		events, err := desktopInputEvents(desktopInput{Type: "click", Display: test.id, X: test.x, Y: test.y}, displays)
		if err != nil || len(events) != 3 {
			t.Fatalf("%+v: events=%v err=%v", test, events, err)
		}
		mouse := events[0].mouse
		got := image.Pt(virtual.Min.X+int(mouse.x)*virtual.Dx()/65536, virtual.Min.Y+int(mouse.y)*virtual.Dy()/65536)
		if mouse.flags&mouseVirtual == 0 || got != test.screen {
			t.Fatalf("%+v landed at %v: %+v", test, got, mouse)
		}
	}
	for _, input := range []desktopInput{
		{Type: "click", Display: "all", X: 1, Y: 0},
		{Type: "drag", Display: "all", EndX: 1, EndY: 1},
	} {
		events, err := desktopInputEvents(input, displays)
		if err == nil || len(events) != 0 {
			t.Fatalf("pointer in monitor gap accepted: %v, %v", events, err)
		}
	}
	if _, err := desktopInputEvents(desktopInput{Type: "key", Display: "unplugged", Key: "enter"}, displays); !errors.Is(err, errDesktopDisplayChanged) {
		t.Fatalf("unplugged display: %v", err)
	}
}

func TestDesktopNativeInputEncoding(t *testing.T) {
	if got := unsafe.Sizeof(desktopWinEvent{}); (unsafe.Sizeof(uintptr(0)) == 8 && got != 40) || (unsafe.Sizeof(uintptr(0)) == 4 && got != 28) {
		t.Fatalf("Windows INPUT ABI size is %d", got)
	}
	keys, err := desktopKeyboardEvents(desktopInput{Type: "text", Text: "語🌏\r\n"})
	if err != nil || len(keys) != 8 {
		t.Fatalf("UTF-16 events: %d, %v", len(keys), err)
	}
	for i, unit := range []uint16{0x8a9e, 0xd83c, 0xdf0f, 0x0d} {
		if keys[i*2] != desktopKey(0, unit, winKeyUnicode) || keys[i*2+1] != desktopKey(0, unit, winKeyUnicode|winKeyUp) {
			t.Fatalf("UTF-16 unit %d encoded incorrectly", i)
		}
	}
	chord, err := desktopKeyboardEvents(desktopInput{Type: "key", Key: "altTab"})
	if err != nil || len(chord) != 4 {
		t.Fatalf("Alt+Tab: %v, %v", chord, err)
	}
	releases := desktopPendingReleases(chord[:2])
	if len(releases) != 2 || releases[0] != chord[2] || releases[1] != chord[3] || len(desktopPendingReleases(chord)) != 0 {
		t.Fatalf("partial chord cleanup: %+v", releases)
	}
	events, err := desktopInputEvents(desktopInput{Type: "scroll", Display: "all", Delta: -2}, []desktopDisplay{{ID: "m", Width: 1000, Height: 500}})
	if err != nil || len(events) != 2 || int32(events[1].mouse.data) != -240 {
		t.Fatalf("wheel units: %+v, %v", events, err)
	}
}

func TestDesktopNativeFrameSize(t *testing.T) {
	for _, tc := range []struct {
		width, height int
		high          bool
		want          image.Point
	}{
		{1280, 720, false, image.Pt(1280, 720)},
		{1920, 1080, false, image.Pt(1600, 900)},
		{2160, 3840, true, image.Pt(1800, 3200)},
		{7680, 2160, true, image.Pt(3200, 900)},
	} {
		if got := desktopFrameSize(image.Rect(0, 0, tc.width, tc.height), tc.high); got != tc.want {
			t.Errorf("%+v: got %v", tc, got)
		}
	}
}

func TestDesktopNativeResolutionLimits(t *testing.T) {
	opts := desktopFrameOptions{native: true}
	for _, size := range []image.Point{image.Pt(5760, 2160), image.Pt(7680, 4320), image.Pt(1440, 5120)} {
		// Include monitors with a negative origin. A native frame must preserve both dimensions.
		rect := image.Rectangle{Max: size}.Add(image.Pt(-980, -160))
		got, err := desktopRequestedSize(rect, opts)
		if err != nil || got != size {
			t.Fatalf("%v: %v %v", rect, got, err)
		}
	}
	for _, size := range []image.Point{image.Pt(16500, 720), image.Pt(16384, 2160)} {
		_, err := desktopRequestedSize(image.Rectangle{Max: size}, opts)
		var he *HTTPError
		if !errors.As(err, &he) || he.Status != 413 {
			t.Fatalf("%v: %v", size, err)
		}
	}
}

func TestDesktopWindowsJPEG(t *testing.T) {
	if !desktopImagingReady() {
		t.Skip("Windows JPEG encoder unavailable")
	}
	const width, height = 64, 24
	raw := make([]byte, width*height*4)
	for i := 0; i < len(raw); i += 4 {
		if i/4%width < width/2 {
			raw[i+2] = 240
		} else {
			raw[i] = 235
		}
	}
	frame := desktopNativeJPEG(raw, image.Pt(width, height), 86)
	img, _, err := image.Decode(bytes.NewReader(frame))
	if err != nil || img.Bounds().Size() != image.Pt(width, height) {
		t.Fatalf("Windows JPEG decode: %v", err)
	}
	left := color.RGBAModel.Convert(img.At(3, 3)).(color.RGBA)
	right := color.RGBAModel.Convert(img.At(width-3, 3)).(color.RGBA)
	if left.R < 220 || left.B > 30 || right.B < 215 || right.R > 30 {
		t.Fatalf("incorrect BGRX channels: %v %v", left, right)
	}
}

// Opt in from an interactive Windows session. Frames stay in memory; this
// test never sends input or moves the local pointer.
func TestDesktopNativeCapture(t *testing.T) {
	if os.Getenv("REMOTE_CODE_TEST_DESKTOP_CAPTURE") != "1" {
		t.Skip("requires an interactive Windows desktop")
	}
	monitors, err := listDesktopDisplays()
	if err != nil {
		t.Fatal(err)
	}
	ids := []string{"all"}
	for _, d := range monitors {
		ids = append(ids, d.ID)
	}
	for _, method := range desktopCaptureMethods(monitors) {
		for _, id := range ids {
			for _, options := range []desktopFrameOptions{
				{capture: method, codec: "jpeg"}, {capture: method, codec: "png", highDetail: true},
				{capture: method, codec: "jpeg", native: true, separateCursor: true}, {capture: method, codec: "png", native: true, separateCursor: true},
			} {
				codec := options.codec
				frame, err := captureDesktopFrame(id, options)
				if err != nil || frame.layout != desktopLayoutID(monitors) || (method != "auto" && method != frame.capture) {
					t.Fatalf("%s %s/%s: layout=%q method=%s err=%v", id, method, codec, frame.layout, frame.capture, err)
				}
				conf, format, err := image.DecodeConfig(bytes.NewReader(frame.data))
				target, _, _ := desktopRectangles(monitors, id)
				want, _ := desktopRequestedSize(target, options)
				if frame.etag == "" || frame.cursor == "" {
					t.Fatalf("no frame or cursor state for %s", id)
				}
				if err != nil || conf.Width != want.X || conf.Height != want.Y || format != codec {
					t.Fatalf("%s %s/%s: %s config=%v err=%v", id, method, codec, format, conf, err)
				}
				t.Logf("%s %s(%s)/%s %dx%d %d bytes", id, method, frame.capture, format, conf.Width, conf.Height, len(frame.data))
			}
		}
	}
}

// Opt-in read-only throughput measurement on the local monitor layout.
func BenchmarkDesktopFrameCapture(b *testing.B) {
	if os.Getenv("REMOTE_CODE_TEST_DESKTOP_CAPTURE") != "1" {
		b.Skip("requires an interactive Windows desktop")
	}
	for _, opts := range []desktopFrameOptions{
		{capture: "dxgi", codec: "jpeg"}, {capture: "dxgi", codec: "jpeg", highDetail: true},
		{capture: "gdi", codec: "jpeg"}, {capture: "gdi", codec: "jpeg", highDetail: true},
		{capture: "dxgi", codec: "jpeg", native: true}, {capture: "gdi", codec: "jpeg", native: true},
	} {
		name := opts.capture + "/normal"
		if opts.highDetail {
			name = opts.capture + "/high"
		}
		if opts.native {
			name = opts.capture + "/native"
		}
		b.Run(name, func(b *testing.B) {
			if _, err := captureDesktopFrame("all", opts); err != nil {
				b.Fatal(err)
			}
			b.ReportAllocs()
			for b.Loop() {
				frame, err := captureDesktopFrame("all", opts)
				if err != nil || len(frame.data) == 0 {
					b.Fatal("empty frame", err)
				}
			}
		})
	}
}

func BenchmarkDesktopConditionalCapture(b *testing.B) {
	if os.Getenv("REMOTE_CODE_TEST_DESKTOP_CAPTURE") != "1" {
		b.Skip("requires an interactive Windows desktop")
	}
	monitors, err := listDesktopDisplays()
	if err != nil {
		b.Fatal(err)
	}
	// Exercise sampling a single display rapidly without saving frames or generating input.
	for _, d := range monitors {
		b.Run(d.Name, func(b *testing.B) {
			opts := desktopFrameOptions{native: true, separateCursor: true, capture: "dxgi", codec: "jpeg"}
			initial, err := captureDesktopFrame(d.ID, opts)
			if err != nil {
				b.Fatal(err)
			}
			opts.previousETag = initial.etag
			var unchanged int
			var unchangedDuration time.Duration
			for b.Loop() {
				start := time.Now()
				frame, err := captureDesktopFrame(d.ID, opts)
				if err != nil {
					b.Fatal(err)
				}
				if frame.etag == opts.previousETag {
					if len(frame.data) != 0 {
						b.Fatal("unchanged frame unexpectedly encoded")
					}
					unchanged++
					unchangedDuration += time.Since(start)
				} else if len(frame.data) == 0 {
					b.Fatal("missing changed frame")
				}
				opts.previousETag = frame.etag
			}
			b.ReportMetric(100*float64(unchanged)/float64(b.N), "unchanged-%")
			if unchanged > 0 {
				b.ReportMetric(float64(unchangedDuration.Microseconds())/float64(unchanged), "us/unchanged")
			}
		})
	}
}

func TestDesktopNativeCaptureFallback(t *testing.T) {
	if os.Getenv("REMOTE_CODE_TEST_DESKTOP_CAPTURE") != "1" {
		t.Skip("requires an interactive Windows desktop")
	}
	monitors, err := listDesktopDisplays()
	if err != nil {
		t.Fatal(err)
	}
	monitor := monitors[len(monitors)-1]
	desktopDuplication.Lock()
	resetDesktopDuplication()
	prepareDesktopDuplication(desktopLayoutID(monitors))
	desktopDuplication.retryAfter[monitor.ID] = time.Now().Add(time.Minute)
	desktopDuplication.Unlock()
	t.Cleanup(func() {
		desktopDuplication.Lock()
		defer desktopDuplication.Unlock()
		resetDesktopDuplication()
	})

	_, err = captureDesktopFrame(monitor.ID, desktopFrameOptions{capture: "dxgi", codec: "jpeg"})
	if err == nil {
		t.Fatal("explicit DXGI capture silently switched methods")
	}
	frame, err := captureDesktopFrame("all", desktopFrameOptions{capture: "auto", codec: "jpeg"})
	if err != nil || frame.capture != "gdi" || len(frame.data) == 0 {
		t.Fatalf("auto did not recover the full layout through GDI: method=%s err=%v", frame.capture, err)
	}
}

func TestDesktopDXGIRotation(t *testing.T) {
	// Raw 3x2 BGRA image with padded scan lines and a non-zero destination origin.
	src := make([]byte, 32)
	for y := 0; y < 2; y++ {
		for x := 0; x < 3; x++ {
			src[y*16+x*4] = byte(1 + y*3 + x)
		}
	}
	for _, test := range []struct {
		rotation uint32
		w, h     int
		want     []byte
	}{
		{1, 3, 2, []byte{1, 2, 3, 4, 5, 6}},
		{2, 2, 3, []byte{4, 1, 5, 2, 6, 3}},
		{3, 3, 2, []byte{6, 5, 4, 3, 2, 1}},
		{4, 2, 3, []byte{3, 6, 2, 5, 1, 4}},
	} {
		dst := make([]byte, 5*4*4)
		copyDuplicationPixels(dst, 20, image.Rect(1, 1, 1+test.w, 1+test.h), src, 16, image.Pt(3, 2), test.rotation)
		var got []byte
		for y := 1; y <= test.h; y++ {
			for x := 1; x <= test.w; x++ {
				got = append(got, dst[y*20+x*4])
			}
		}
		if !bytes.Equal(got, test.want) {
			t.Fatalf("rotation %d: got %v, want %v", test.rotation, got, test.want)
		}
	}
}
