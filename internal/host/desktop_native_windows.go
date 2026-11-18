//go:build windows

package host

import (
	"bytes"
	"crypto/sha256"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"image/png"
	"net/http"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	desktopUser32       = windows.NewLazySystemDLL("user32.dll")
	desktopGDI          = windows.NewLazySystemDLL("gdi32.dll")
	desktopDPI          = desktopUser32.NewProc("SetThreadDpiAwarenessContext")
	desktopThread       = desktopUser32.NewProc("GetThreadDesktop")
	desktopUserInfo     = desktopUser32.NewProc("GetUserObjectInformationW")
	desktopEnumMonitors = desktopUser32.NewProc("EnumDisplayMonitors")
	desktopMonitorInfo  = desktopUser32.NewProc("GetMonitorInfoW")
	desktopGetDC        = desktopUser32.NewProc("GetDC")
	desktopReleaseDC    = desktopUser32.NewProc("ReleaseDC")
	desktopGetCursor    = desktopUser32.NewProc("GetCursorInfo")
	desktopLoadCursor   = desktopUser32.NewProc("LoadCursorW")
	desktopGetIcon      = desktopUser32.NewProc("GetIconInfo")
	desktopDrawIcon     = desktopUser32.NewProc("DrawIconEx")
	desktopCreateDC     = desktopGDI.NewProc("CreateCompatibleDC")
	desktopDeleteDC     = desktopGDI.NewProc("DeleteDC")
	desktopCreateDIB    = desktopGDI.NewProc("CreateDIBSection")
	desktopSelect       = desktopGDI.NewProc("SelectObject")
	desktopDelete       = desktopGDI.NewProc("DeleteObject")
	desktopGetObject    = desktopGDI.NewProc("GetObjectW")
	desktopStretchMode  = desktopGDI.NewProc("SetStretchBltMode")
	desktopBrushOrigin  = desktopGDI.NewProc("SetBrushOrgEx")
	desktopBitBlt       = desktopGDI.NewProc("BitBlt")
	desktopBlit         = desktopGDI.NewProc("StretchBlt")
	desktopFlush        = desktopGDI.NewProc("GdiFlush")
	desktopMonitorProc  = windows.NewCallback(collectDesktopMonitor)
)

func desktopSupported() bool { return true }

// Keep GDI and monitor coordinates in physical pixels on this thread only.
// Windows changes the input desktop for lock screens and secure prompts.
func enterInteractiveDesktop() (func(), error) {
	runtime.LockOSThread()
	if err := checkInteractiveDesktop(); err != nil {
		runtime.UnlockOSThread()
		return nil, err
	}
	if err := desktopDPI.Find(); err != nil {
		runtime.UnlockOSThread()
		return nil, errors.New("Windows per-monitor DPI awareness is unavailable")
	}
	const perMonitorAware = ^uintptr(2) // DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE (-3)
	old, _, _ := desktopDPI.Call(perMonitorAware)
	if old == 0 {
		runtime.UnlockOSThread()
		return nil, errors.New("could not determine physical desktop coordinates")
	}
	return func() {
		desktopDPI.Call(old)
		runtime.UnlockOSThread()
	}, nil
}

// Called on the OS thread that owns the desktop operation.
func checkInteractiveDesktop() error {
	threadDesk, _, _ := desktopThread.Call(uintptr(windows.GetCurrentThreadId()))
	var receivingInput, length uint32
	ok, _, _ := desktopUserInfo.Call(threadDesk, 6 /* UOI_IO */, uintptr(unsafe.Pointer(&receivingInput)), 4, uintptr(unsafe.Pointer(&length)))
	if ok == 0 || receivingInput == 0 {
		return errors.New("the Windows user desktop is unavailable or locked")
	}
	return nil
}

type desktopMonitorDetails struct {
	size          uint32
	monitor, work windows.Rect
	flags         uint32
	device        [32]uint16
}

func collectDesktopMonitor(handle, dc, rect uintptr, displays *[]desktopDisplay) uintptr {
	var info desktopMonitorDetails
	info.size = uint32(unsafe.Sizeof(info))
	ok, _, _ := desktopMonitorInfo.Call(handle, uintptr(unsafe.Pointer(&info)))
	if ok == 0 {
		return 0
	}
	r := info.monitor
	id := windows.UTF16ToString(info.device[:])
	if id != "" && r.Right > r.Left && r.Bottom > r.Top {
		*displays = append(*displays, desktopDisplay{
			ID: id, Name: strings.TrimPrefix(id, `\\.\`), Primary: info.flags&1 != 0,
			X: int(r.Left), Y: int(r.Top), Width: int(r.Right - r.Left), Height: int(r.Bottom - r.Top),
		})
	}
	return 1
}

func desktopMonitors() ([]desktopDisplay, error) {
	var monitors []desktopDisplay
	ok, _, _ := desktopEnumMonitors.Call(0, 0, desktopMonitorProc, uintptr(unsafe.Pointer(&monitors)))
	if ok == 0 || len(monitors) == 0 {
		return nil, errors.New("no active Windows display could be read")
	}
	sort.Slice(monitors, func(i, j int) bool {
		if monitors[i].Primary != monitors[j].Primary {
			return monitors[i].Primary
		}
		return monitors[i].ID < monitors[j].ID
	})
	return monitors, nil
}

func listDesktopDisplays() ([]desktopDisplay, error) {
	leave, err := enterInteractiveDesktop()
	if err != nil {
		return nil, err
	}
	defer leave()
	return desktopMonitors()
}

func monitorRectangle(d desktopDisplay) image.Rectangle {
	return image.Rect(d.X, d.Y, d.X+d.Width, d.Y+d.Height)
}

func desktopRectangles(monitors []desktopDisplay, id string) (target, virtual image.Rectangle, err error) {
	for _, d := range monitors {
		r := monitorRectangle(d)
		virtual = virtual.Union(r)
		if d.ID == id {
			target = r
		}
	}
	if id == "" || id == "all" {
		target = virtual
	}
	if target.Empty() {
		err = errDesktopDisplayChanged
	}
	return
}

// Keep the aspect ratio of portrait, ultrawide and tiled monitor layouts.
func desktopFrameSize(r image.Rectangle, highDetail bool) image.Point {
	longestEdge := 1600
	if highDetail {
		longestEdge = 3200
	}
	longest := max(r.Dx(), r.Dy())
	if longest <= longestEdge {
		return r.Size()
	}
	return image.Pt(max(1, r.Dx()*longestEdge/longest), max(1, r.Dy()*longestEdge/longest))
}

type desktopBitmapHeader struct {
	size                     uint32
	width, height            int32
	planes, bitCount         uint16
	compression, imageSize   uint32
	xResolution, yResolution int32
	colorsUsed, important    uint32
}

const desktopMaxNativePixels = 32 * 1024 * 1024

func desktopRequestedSize(r image.Rectangle, options desktopFrameOptions) (image.Point, error) {
	if !options.native {
		return desktopFrameSize(r, options.highDetail), nil
	}
	w, h := r.Dx(), r.Dy()
	if w > 16384 || h > 16384 || w <= 0 || h <= 0 || int64(w)*int64(h) > desktopMaxNativePixels {
		return image.Point{}, &HTTPError{http.StatusRequestEntityTooLarge, fmt.Sprintf("Native desktop size %dx%d exceeds the limit (16384 pixels per edge and 32 megapixels). Choose a single display or High detail.", w, h)}
	}
	return r.Size(), nil
}

var desktopFrameEpoch = NewID()

func captureDesktopFrame(display string, options desktopFrameOptions) (desktopFrame, error) {
	var frame desktopFrame
	leave, err := enterInteractiveDesktop()
	if err != nil {
		desktopDuplication.Lock()
		resetDesktopDuplication()
		desktopDuplication.Unlock()
		return frame, err
	}
	defer leave()
	monitors, err := desktopMonitors()
	if err != nil {
		return frame, err
	}
	target, _, err := desktopRectangles(monitors, display)
	if err != nil {
		return frame, err
	}
	frame.layout = desktopLayoutID(monitors)
	size, err := desktopRequestedSize(target, options)
	if err != nil {
		return frame, err
	}
	if options.capture == "dxgi" || options.capture == "auto" {
		frame.capture = "dxgi"
		err = captureDuplicatedFrame(&frame, monitors, display, target, size, options)
		if err == nil || options.capture == "dxgi" {
			return frame, err
		}
		if err = checkInteractiveDesktop(); err != nil {
			return desktopFrame{}, err
		}
	}
	frame.capture, frame.etag, frame.data = "gdi", "", nil
	frame.regions, frame.baseETag = nil, ""
	cursor := sampleDesktopCursor(&frame, options.separateCursor, target, nil)
	err = renderDesktopFrame(&frame, cursor, monitors, nil, target, size, options)
	return frame, err
}

func captureDuplicatedFrame(frame *desktopFrame, monitors []desktopDisplay, display string, target image.Rectangle, size image.Point, options desktopFrameOptions) error {
	desktopDuplication.Lock()
	defer desktopDuplication.Unlock()
	parts, generation, areas, err := acquireDesktopOutputs(monitors, display, frame.layout, target, size)
	if err != nil {
		return err
	}
	cursor := sampleDesktopCursor(frame, options.separateCursor, target, areas)
	// Generation advances only for a new desktop image. A separately drawn
	// pointer can change position without invalidating the image on the phone.
	sum := sha256.Sum256([]byte(fmt.Sprintf("%s/%s/%s/%s/%v/%d/%v/%v/%x/%d/%d", desktopFrameEpoch, frame.layout, display, options.encodingKey(), size, generation, options.highDetail, options.native, cursor.handle, cursor.x, cursor.y)))
	frame.etag = fmt.Sprintf(`"%x"`, sum[:16])
	s := options.regions
	if s != nil && s.tag == options.previousETag && s.sourceTag == frame.etag && s.tag != "" {
		frame.etag = s.tag
		return checkInteractiveDesktop()
	}
	if s == nil && frame.etag == options.previousETag {
		return checkInteractiveDesktop()
	}
	if s != nil && supportsDirectDesktopRegions(parts) {
		fresh := s.begin(size, frame, options.previousETag)
		s.restoreCursorBackground()
		for _, part := range parts {
			o := part.output
			if !fresh && s.generations[part.display.ID] == o.generation {
				continue
			}
			if err := o.readPixels(func(src []byte, pitch int) {
				o.updateRegions(s, part, fresh, src, pitch)
			}); err != nil {
				return desktopDuplicationError(part.display, o, err)
			}
			s.generations[part.display.ID] = o.generation
		}
		if !s.drawCursorPatch(target, cursor) {
			// A driver or unusual cursor can require the full Windows surface.
			return renderDesktopFrame(frame, cursor, monitors, parts, target, size, options)
		}
		if err := checkInteractiveDesktop(); err != nil {
			return err
		}
		return encodeDesktopUpdate(s, fresh, frame, options)
	}
	return renderDesktopFrame(frame, cursor, monitors, parts, target, size, options)
}

func supportsDirectDesktopRegions(parts []desktopOutputPart) bool {
	for i, part := range parts {
		if part.output.rotation > 1 || part.output.size != part.destination.Size() {
			return false
		}
		for _, other := range parts[:i] {
			if part.destination.Overlaps(other.destination) {
				return false
			}
		}
	}
	return true
}

// Keep the DIB selected and alive until all pixel reads and writes finish.
func withDesktopDIB(size image.Point, render func(mem, screen uintptr, raw []byte) error) error {
	screen, _, _ := desktopGetDC.Call(0)
	if screen == 0 {
		return errors.New("could not open the Windows screen")
	}
	defer desktopReleaseDC.Call(0, screen)
	mem, _, _ := desktopCreateDC.Call(screen)
	if mem == 0 {
		return errors.New("could not create the desktop capture context")
	}
	defer desktopDeleteDC.Call(mem)
	// BI_RGB, 32 bits per pixel, negative height for top-down scan lines.
	header := desktopBitmapHeader{size: uint32(unsafe.Sizeof(desktopBitmapHeader{})), width: int32(size.X), height: int32(-size.Y), planes: 1, bitCount: 32}
	var bits unsafe.Pointer
	bitmap, _, _ := desktopCreateDIB.Call(screen, uintptr(unsafe.Pointer(&header)), 0, uintptr(unsafe.Pointer(&bits)), 0, 0)
	if bitmap == 0 {
		return errors.New("could not allocate the desktop frame")
	}
	defer desktopDelete.Call(bitmap)
	previous, _, _ := desktopSelect.Call(mem, bitmap)
	if previous == 0 || previous == ^uintptr(0) || bits == nil {
		return errors.New("could not select the desktop capture bitmap")
	}
	defer desktopSelect.Call(mem, previous)
	raw := unsafe.Slice((*byte)(bits), size.X*size.Y*4)
	return render(mem, screen, raw)
}

func renderDesktopFrame(frame *desktopFrame, cursor desktopCursorInfo, monitors []desktopDisplay, parts []desktopOutputPart, target image.Rectangle, size image.Point, options desktopFrameOptions) error {
	return withDesktopDIB(size, func(mem, screen uintptr, raw []byte) error {
		// A single monitor covers every pixel. Only composite layouts can have
		// uncovered gaps requiring an explicit black background.
		covered := false
		for _, monitor := range monitors {
			if target.In(monitorRectangle(monitor)) {
				covered = true
				break
			}
		}
		if !covered {
			clear(raw)
		}
		if parts == nil {
			if err := drawGDIDesktop(mem, screen, target, size, monitors); err != nil {
				return err
			}
		} else {
			for _, part := range parts {
				if err := part.output.draw(raw, size.X*4, part.destination); err != nil {
					return desktopDuplicationError(part.display, part.output, err)
				}
			}
		}
		if cursor.handle != 0 {
			if rect, ok := desktopCursorBounds(target, size, cursor); ok {
				drawDesktopCursor(mem, cursor.handle, rect)
			}
		}
		if ok, _, _ := desktopFlush.Call(); ok == 0 {
			return errors.New("Windows could not finish capturing the desktop")
		}
		if err := checkInteractiveDesktop(); err != nil {
			return err
		}
		if s := options.regions; s != nil {
			fresh := s.updateComposite(raw, size, frame, options.previousETag)
			return encodeDesktopUpdate(s, fresh, frame, options)
		}
		if frame.capture == "gdi" {
			// GDI supplies pixels even when the scene is idle. Compare their digest
			// before encoding or transferring another complete desktop image.
			sum := sha256.Sum256(raw)
			frame.etag = fmt.Sprintf(`"g-%s-%dx%d-%s-%t-%x"`, frame.layout, size.X, size.Y, options.encodingKey(), options.highDetail || options.native, sum[:16])
			if frame.etag == options.previousETag {
				return nil
			}
		}
		var err error
		frame.data, err = encodeDesktopPixels(raw, size, options, false)
		return err
	})
}

func encodeDesktopUpdate(s *desktopRegionState, fresh bool, frame *desktopFrame, opts desktopFrameOptions) error {
	sourceTag := frame.etag
	pixels, size := s.pixels, s.size
	if !fresh {
		rects := s.changedRects()
		if len(rects) == 0 {
			s.tag, s.sourceTag = opts.previousETag, sourceTag
			frame.etag = s.tag
			return nil
		}
		regions, packedSize := packDesktopRegions(rects, size)
		if len(regions) > 0 {
			size = packedSize
			needed := size.X * size.Y * 4
			if needed > 32<<20 {
				pixels = make([]byte, needed)
			} else {
				if cap(s.atlas) < needed {
					s.atlas = make([]byte, needed)
				} else {
					s.atlas = s.atlas[:needed]
					if len(regions) > 1 {
						// Padding between rectangles must never expose old screen pixels.
						clear(s.atlas)
					}
				}
				pixels = s.atlas
			}
			for _, r := range regions {
				copyDesktopRect(pixels, size.X*4, r.source, s.pixels, s.size.X*4, r.dest)
			}
			frame.regions, frame.baseETag = regions, opts.previousETag
		}
	}
	var err error
	frame.data, err = encodeDesktopPixels(pixels, size, opts, len(frame.regions) == 0)
	if err != nil {
		return err
	}
	s.sequence++
	s.tag = fmt.Sprintf(`"r-%s-%x"`, s.id, s.sequence)
	s.sourceTag = sourceTag
	frame.etag = s.tag
	return nil
}

func encodeDesktopPixels(raw []byte, size image.Point, options desktopFrameOptions, preserve bool) ([]byte, error) {
	quality := 78
	if options.highDetail || options.native {
		quality = 86
	}
	qualityRate := options.rate
	if options.codec == "jpeg" {
		if options.jpegQuality != 0 {
			quality = options.jpegQuality
			// An explicit quality stays fixed. The stream still paces the
			// encoded bytes against its target using options.rate.nextPeriod.
			qualityRate = nil
		} else if qualityRate != nil {
			quality = qualityRate.jpegQuality(size.X*size.Y, quality)
		}
		if encoded := desktopNativeJPEG(raw, size, uint32(quality)); encoded != nil {
			if qualityRate != nil {
				qualityRate.observeJPEG(size.X*size.Y, quality, len(encoded))
			}
			return encoded, nil
		}
	}
	// Go's codecs need RGBA. Keep the native BGRX reference image intact
	// when it will be compared to the next frame.
	if preserve {
		raw = bytes.Clone(raw)
	}
	img := &image.RGBA{Pix: raw, Stride: size.X * 4, Rect: image.Rectangle{Max: size}}
	for i := 0; i < len(raw); i += 4 {
		raw[i], raw[i+2], raw[i+3] = raw[i+2], raw[i], 255
	}
	var out bytes.Buffer
	var err error
	if options.codec == "png" {
		encoder := png.Encoder{CompressionLevel: desktopPNGLevels[options.pngCompression]}
		err = encoder.Encode(&out, img)
	} else {
		err = jpeg.Encode(&out, img, &jpeg.Options{Quality: quality})
		if err == nil && qualityRate != nil {
			qualityRate.observeJPEG(size.X*size.Y, quality, out.Len())
		}
	}
	return out.Bytes(), err
}

// Round shared monitor edges identically to avoid visible seams.
func desktopDestination(src, target image.Rectangle, size image.Point) image.Rectangle {
	dst := src.Intersect(target).Sub(target.Min)
	return image.Rect(dst.Min.X*size.X/target.Dx(), dst.Min.Y*size.Y/target.Dy(),
		dst.Max.X*size.X/target.Dx(), dst.Max.Y*size.Y/target.Dy())
}

func drawGDIDesktop(mem, screen uintptr, target image.Rectangle, size image.Point, monitors []desktopDisplay) error {
	if size != target.Size() {
		desktopStretchMode.Call(mem, 4 /* HALFTONE */)
		desktopBrushOrigin.Call(mem, 0, 0, 0)
	}
	for _, m := range monitors {
		src := monitorRectangle(m).Intersect(target)
		if src.Empty() {
			continue
		}
		dst := desktopDestination(src, target, size)
		if dst.Empty() {
			continue
		}
		const captureCopy = 0x40cc0020 // SRCCOPY | CAPTUREBLT (layered windows)
		var ok uintptr
		if src.Size() == dst.Size() {
			ok, _, _ = desktopBitBlt.Call(mem, uintptr(dst.Min.X), uintptr(dst.Min.Y), uintptr(dst.Dx()), uintptr(dst.Dy()), screen, uintptr(src.Min.X), uintptr(src.Min.Y), captureCopy)
		} else {
			ok, _, _ = desktopBlit.Call(mem, uintptr(dst.Min.X), uintptr(dst.Min.Y), uintptr(dst.Dx()), uintptr(dst.Dy()), screen, uintptr(src.Min.X), uintptr(src.Min.Y), uintptr(src.Dx()), uintptr(src.Dy()), captureCopy)
		}
		if ok == 0 {
			return errors.New("could not capture the Windows desktop")
		}
	}
	return nil
}

type desktopCursorInfo struct {
	size, flags uint32
	handle      uintptr
	x, y        int32
}

// Return the native cursor to paint only when its pixels are absent from the
// capture and the client cannot draw its shape. Keep custom shapes intact.
func sampleDesktopCursor(frame *desktopFrame, separate bool, target image.Rectangle, cursorAreas []image.Rectangle) desktopCursorInfo {
	var c desktopCursorInfo
	c.size = uint32(unsafe.Sizeof(c))
	desktopCursorState.Lock()
	ok, _, _ := desktopGetCursor.Call(uintptr(unsafe.Pointer(&c)))
	frame.revision = desktopCursorState.revision
	desktopCursorState.Unlock()
	p := image.Pt(int(c.x), int(c.y))
	frame.cursor, frame.cursorPosition = "embedded", ""
	if ok == 0 || c.flags&1 == 0 || !p.In(target) {
		frame.cursor = "hidden"
		return desktopCursorInfo{}
	}
	if separate {
		x := float64(p.X-target.Min.X) / float64(max(1, target.Dx()-1))
		y := float64(p.Y-target.Min.Y) / float64(max(1, target.Dy()-1))
		frame.cursorPosition = strconv.FormatFloat(x, 'f', 8, 64) + "," + strconv.FormatFloat(y, 'f', 8, 64)
	}
	if cursorAreas != nil {
		draw := false
		for _, a := range cursorAreas {
			if p.In(a) {
				draw = true
				break
			}
		}
		if !draw {
			return desktopCursorInfo{}
		} // Driver embeds the cursor.
	}
	if separate {
		arrow, _, _ := desktopLoadCursor.Call(0, 32512 /* IDC_ARROW */)
		if c.handle != 0 && c.handle == arrow {
			frame.cursor = frame.cursorPosition
			return desktopCursorInfo{}
		}
	}
	return c
}

func desktopCursorBounds(target image.Rectangle, size image.Point, cursor desktopCursorInfo) (image.Rectangle, bool) {
	var icon struct {
		isIcon, hotX, hotY uint32
		mask, color        uintptr
	}
	if ok, _, _ := desktopGetIcon.Call(cursor.handle, uintptr(unsafe.Pointer(&icon))); ok == 0 {
		return image.Rectangle{}, false
	}
	bmp := icon.color
	if icon.mask != 0 {
		defer desktopDelete.Call(icon.mask)
	}
	if bmp != 0 {
		defer desktopDelete.Call(bmp)
	} else {
		bmp = icon.mask
	}
	var info struct {
		kind, width, height, stride int32
		planes, bpp                 uint16
		bits                        uintptr
	}
	if ok, _, _ := desktopGetObject.Call(bmp, unsafe.Sizeof(info), uintptr(unsafe.Pointer(&info))); ok == 0 {
		return image.Rectangle{}, false
	}
	if icon.color == 0 {
		info.height /= 2 // Monochrome AND/XOR masks share a bitmap.
	}
	x := (int(cursor.x) - int(icon.hotX) - target.Min.X) * size.X / target.Dx()
	y := (int(cursor.y) - int(icon.hotY) - target.Min.Y) * size.Y / target.Dy()
	w := max(1, int(info.width)*size.X/target.Dx())
	h := max(1, int(info.height)*size.Y/target.Dy())
	return image.Rect(x, y, x+w, y+h), true
}

func drawDesktopCursor(dc, handle uintptr, rect image.Rectangle) bool {
	ok, _, _ := desktopDrawIcon.Call(dc, uintptr(rect.Min.X), uintptr(rect.Min.Y), handle, uintptr(rect.Dx()), uintptr(rect.Dy()), 0, 0, 3 /* DI_NORMAL */)
	return ok != 0
}
