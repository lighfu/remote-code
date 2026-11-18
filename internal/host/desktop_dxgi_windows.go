//go:build windows

package host

import (
	"errors"
	"fmt"
	"image"
	"strings"
	"sync"
	"time"
	"unsafe"

	d3d "github.com/kirides/go-d3d"
	"github.com/kirides/go-d3d/d3d11"
	"github.com/kirides/go-d3d/dxgi"
	"golang.org/x/sys/windows"
)

var desktopD3DDevice = windows.NewLazySystemDLL("d3d11.dll").NewProc("D3D11CreateDevice")

// Values of DXGI_MODE_ROTATION from dxgitype.h.
const (
	desktopRotate90  = 2
	desktopRotate180 = 3
	desktopRotate270 = 4
)

// DuplicateOutput allows a process one duplication object per output. Reuse
// it between frames, and release the GPU surfaces when viewers stop polling.
var desktopDuplication = struct {
	sync.Mutex
	layout     string
	outputs    map[string]*duplicatedOutput
	retryAfter map[string]time.Time
	timer      *time.Timer
	lastUse    time.Time
	generation uint64
}{}

type duplicatedOutput struct {
	device             *d3d11.ID3D11Device
	context            *d3d11.ID3D11DeviceContext
	dup                *dxgi.IDXGIOutputDuplication
	texture            *d3d11.ID3D11Texture2D
	surface            *dxgi.IDXGISurface
	size               image.Point
	rotation           uint32
	pointerSeparate    bool
	generation         uint64
	previousGeneration uint64
	damage             []image.Rectangle
	dirtyBuffer        [64]dxgi.RECT
	moveBuffer         [32]dxgi.DXGI_OUTDUPL_MOVE_RECT
}

func (o *duplicatedOutput) close() {
	if o.surface != nil {
		o.surface.Release()
	}
	if o.texture != nil {
		o.texture.Release()
	}
	if o.dup != nil {
		o.dup.Release()
	}
	if o.context != nil {
		o.context.Release()
	}
	if o.device != nil {
		o.device.Release()
	}
}

// Cache preparation, lookup and reset are called with desktopDuplication locked.
func resetDesktopDuplication() {
	for _, o := range desktopDuplication.outputs {
		o.close()
	}
	desktopDuplication.outputs = nil
	desktopDuplication.retryAfter = nil
	desktopDuplication.layout = ""
}

func prepareDesktopDuplication(layout string) {
	if desktopDuplication.layout != layout {
		resetDesktopDuplication()
		desktopDuplication.layout = layout
		desktopDuplication.outputs = make(map[string]*duplicatedOutput)
		desktopDuplication.retryAfter = make(map[string]time.Time)
	}
	const retention = 5 * time.Second
	desktopDuplication.lastUse = time.Now()
	if desktopDuplication.timer != nil {
		desktopDuplication.timer.Reset(retention)
		return
	}
	desktopDuplication.timer = time.AfterFunc(retention, func() {
		desktopDuplication.Lock()
		defer desktopDuplication.Unlock()
		// A new request may have reset the timer before we got the lock.
		if time.Since(desktopDuplication.lastUse) >= retention {
			resetDesktopDuplication()
			desktopDuplication.timer = nil
		}
	})
}

func getDuplicatedOutput(id string) (*duplicatedOutput, error) {
	if o := desktopDuplication.outputs[id]; o != nil {
		return o, nil
	}
	if time.Now().Before(desktopDuplication.retryAfter[id]) {
		return nil, errors.New("DXGI is temporarily unavailable on this display")
	}
	o, err := openDuplicatedOutput(id)
	if err != nil {
		desktopDuplication.retryAfter[id] = time.Now().Add(3 * time.Second)
		return nil, err
	}
	desktopDuplication.outputs[id] = o
	return o, nil
}

func desktopCaptureMethods(monitors []desktopDisplay) []string {
	methods := []string{"auto", "gdi"}
	leave, err := enterInteractiveDesktop()
	if err != nil {
		return methods
	}
	defer leave()
	desktopDuplication.Lock()
	defer desktopDuplication.Unlock()
	prepareDesktopDuplication(desktopLayoutID(monitors))
	for _, m := range monitors {
		if _, err := getDuplicatedOutput(m.ID); err != nil {
			return methods
		}
	}
	return append(methods, "dxgi")
}

func openDuplicatedOutput(id string) (*duplicatedOutput, error) {
	if err := desktopD3DDevice.Find(); err != nil {
		return nil, err
	}
	var factory *dxgi.IDXGIFactory1
	if err := dxgi.CreateDXGIFactory1(&factory); err != nil {
		return nil, err
	}
	defer factory.Release()
	for i := uint32(0); ; i++ {
		var adapter *dxgi.IDXGIAdapter1
		hr := d3d.HRESULT(factory.EnumAdapters1(i, &adapter))
		if hr == d3d.DXGI_ERROR_NOT_FOUND {
			break
		}
		if hr.Failed() {
			return nil, hr
		}
		found, err := duplicateAdapterOutput(adapter, id)
		adapter.Release()
		if found != nil || err != nil {
			return found, err
		}
	}
	return nil, fmt.Errorf("DXGI cannot find display %s", id)
}

func duplicateAdapterOutput(adapter *dxgi.IDXGIAdapter1, id string) (*duplicatedOutput, error) {
	for i := uint32(0); ; i++ {
		var output *dxgi.IDXGIOutput
		hr := d3d.HRESULT(adapter.EnumOutputs(i, &output))
		if hr == d3d.DXGI_ERROR_NOT_FOUND {
			return nil, nil
		}
		if hr.Failed() {
			return nil, hr
		}
		found, err := duplicateMatchingOutput(adapter, output, id)
		output.Release()
		if found != nil || err != nil {
			return found, err
		}
	}
}

func duplicateMatchingOutput(adapter *dxgi.IDXGIAdapter1, output *dxgi.IDXGIOutput, id string) (*duplicatedOutput, error) {
	var out5 *dxgi.IDXGIOutput5
	if d3d.HRESULT(output.QueryInterface(dxgi.IID_IDXGIOutput5, &out5)).Failed() {
		return nil, nil
	}
	defer out5.Release()
	var desc dxgi.DXGI_OUTPUT_DESC
	if hr := d3d.HRESULT(out5.GetDesc(&desc)); hr.Failed() {
		return nil, hr
	}
	if desc.AttachedToDesktop == 0 || !strings.EqualFold(windows.UTF16ToString(desc.DeviceName[:]), id) {
		return nil, nil
	}

	var out1 *dxgi.IDXGIOutput1
	if hr := d3d.HRESULT(output.QueryInterface(dxgi.IID_IDXGIOutput1, &out1)); hr.Failed() {
		return nil, hr
	}
	defer out1.Release()
	o := &duplicatedOutput{rotation: uint32(desc.Rotation)}
	// Supply the adapter owning this monitor. The default D3D device may
	// belong to a different GPU on desktops and hybrid laptops.
	result, _, _ := desktopD3DDevice.Call(uintptr(unsafe.Pointer(adapter)), 0, 0, 0, 0, 0, 7,
		uintptr(unsafe.Pointer(&o.device)), 0, uintptr(unsafe.Pointer(&o.context)))
	if hr := d3d.HRESULT(result); hr.Failed() {
		o.close()
		return nil, hr
	}
	var device1 *dxgi.IDXGIDevice1
	if hr := d3d.HRESULT(o.device.QueryInterface(dxgi.IID_IDXGIDevice1, &device1)); hr.Failed() {
		o.close()
		return nil, hr
	}
	defer device1.Release()
	if hr := d3d.HRESULT(out1.DuplicateOutput(device1, &o.dup)); hr.Failed() {
		o.close()
		return nil, hr
	}
	return o, nil
}

func (o *duplicatedOutput) update() (err error) {
	var timeout uint32
	if o.texture == nil {
		timeout = 300
	}
	var info dxgi.DXGI_OUTDUPL_FRAME_INFO
	var resource *dxgi.IDXGIResource
	hr := d3d.HRESULT(o.dup.AcquireNextFrame(timeout, &info, &resource))
	if hr == d3d.DXGI_ERROR_WAIT_TIMEOUT && o.texture != nil {
		return nil
	}
	if hr.Failed() {
		return hr
	}
	defer func() {
		if release := d3d.HRESULT(o.dup.ReleaseFrame()); release.Failed() && err == nil {
			err = release
		}
	}()
	defer resource.Release()
	if info.LastMouseUpdateTime != 0 {
		o.pointerSeparate = info.PointerPosition.Visible != 0
	}
	if info.LastPresentTime == 0 && o.texture != nil {
		return nil
	}
	var frame *d3d11.ID3D11Texture2D
	if hr = d3d.HRESULT(resource.QueryInterface(d3d11.IID_ID3D11Texture2D, &frame)); hr.Failed() {
		return hr
	}
	defer frame.Release()
	first := o.texture == nil
	if first {
		var desc d3d11.D3D11_TEXTURE2D_DESC
		frame.GetDesc(&desc) // ID3D11Texture2D::GetDesc returns void.
		o.size = image.Pt(int(desc.Width), int(desc.Height))
		desc.Usage, desc.CPUAccessFlags = d3d11.D3D11_USAGE_STAGING, d3d11.D3D11_CPU_ACCESS_READ
		desc.BindFlags, desc.MiscFlags = 0, 0
		if hr = d3d.HRESULT(o.device.CreateTexture2D(&desc, &o.texture)); hr.Failed() {
			return hr
		}
		if hr = d3d.HRESULT(o.texture.QueryInterface(dxgi.IID_IDXGISurface, &o.surface)); hr.Failed() {
			return hr
		}
	}
	o.damage = o.damage[:0]
	if !first && o.rotation <= 1 && info.TotalMetadataBufferSize > 0 {
		o.damage = o.frameDamage()
	}
	if len(o.damage) == 0 {
		o.context.CopyResource2D(o.texture, frame)
	} else {
		for _, r := range o.damage {
			box := d3d11.D3D11_BOX{Left: uint32(r.Min.X), Top: uint32(r.Min.Y), Right: uint32(r.Max.X), Bottom: uint32(r.Max.Y), Back: 1}
			o.context.CopySubresourceRegion2D(o.texture, 0, box.Left, box.Top, 0, frame, 0, &box)
		}
	}
	desktopDuplication.generation++
	o.previousGeneration = o.generation
	o.generation = desktopDuplication.generation
	return nil
}

// Windows gives us the final desktop texture. For a move, copying the
// destination directly from that texture also handles overlapping moves.
// Unavailable, excessive, rotated or invalid metadata uses the full surface.
func (o *duplicatedOutput) frameDamage() []image.Rectangle {
	var nm, nd uint32
	if d3d.HRESULT(o.dup.GetFrameMoveRects(o.moveBuffer[:], &nm)).Failed() ||
		d3d.HRESULT(o.dup.GetFrameDirtyRects(o.dirtyBuffer[:], &nd)).Failed() ||
		nm > uint32(len(o.moveBuffer)) || nd > uint32(len(o.dirtyBuffer)) {
		return nil
	}
	rects := o.damage[:0]
	for _, move := range o.moveBuffer[:nm] {
		r := move.Dest
		rects = append(rects, image.Rectangle{Min: image.Pt(int(r.Left), int(r.Top)), Max: image.Pt(int(r.Right), int(r.Bottom))})
	}
	for _, r := range o.dirtyBuffer[:nd] {
		rects = append(rects, image.Rectangle{Min: image.Pt(int(r.Left), int(r.Top)), Max: image.Pt(int(r.Right), int(r.Bottom))})
	}
	bounds := image.Rectangle{Max: o.size}
	area := 0
	for _, r := range rects {
		if r.Empty() || !r.In(bounds) {
			return nil
		}
		area += r.Dx() * r.Dy()
	}
	if area > o.size.X*o.size.Y*2/3 {
		return nil
	}
	return rects
}

// Other viewers can advance the shared GPU texture between two samples.
// Partial comparisons are safe only if this stream saw the preceding texture.
func (o *duplicatedOutput) updateRegions(s *desktopRegionState, part desktopOutputPart, fresh bool, src []byte, pitch int) {
	if fresh || len(o.damage) == 0 || s.generations[part.display.ID] != o.previousGeneration {
		s.update(src, pitch, part.destination, fresh)
		return
	}
	for _, r := range o.damage {
		offset := r.Min.Y*pitch + r.Min.X*4
		s.update(src[offset:], pitch, r.Add(part.destination.Min), false)
	}
}

// Windows supplies an unrotated BGRA surface even for portrait monitors.
// Read it in display order and scale directly into the final, bounded DIB.
func copyDuplicationPixels(dst []byte, stride int, area image.Rectangle, src []byte, pitch int, size image.Point, rotation uint32) {
	width, height := size.X, size.Y
	if rotation == desktopRotate90 || rotation == desktopRotate270 {
		width, height = height, width
	}
	if rotation <= 1 && area.Dx() == width {
		for y := 0; y < area.Dy(); y++ {
			from := (y * height / area.Dy()) * pitch
			to := (area.Min.Y+y)*stride + area.Min.X*4
			copy(dst[to:to+width*4], src[from:from+width*4])
		}
		return
	}

	// Each rotation changes the origin and the two axes of the source.
	// Calculate horizontal offsets once, outside the per-pixel copy.
	origin, xStep, yStep := 0, 4, pitch
	switch rotation {
	case desktopRotate90:
		origin, xStep, yStep = (size.Y-1)*pitch, -pitch, 4
	case desktopRotate180:
		origin, xStep, yStep = (size.Y-1)*pitch+(size.X-1)*4, -4, -pitch
	case desktopRotate270:
		origin, xStep, yStep = (size.X-1)*4, pitch, -4
	}
	offsets := make([]int, area.Dx())
	for x := range offsets {
		offsets[x] = (x * width / area.Dx()) * xStep
	}
	for y := 0; y < area.Dy(); y++ {
		row := dst[(area.Min.Y+y)*stride+area.Min.X*4:]
		base := origin + (y*height/area.Dy())*yStep
		for x, offset := range offsets {
			read := base + offset
			copy(row[x*4:x*4+4], src[read:read+4])
		}
	}
}

func (o *duplicatedOutput) draw(dst []byte, stride int, area image.Rectangle) error {
	return o.readPixels(func(src []byte, pitch int) {
		copyDuplicationPixels(dst, stride, area, src, pitch, o.size, o.rotation)
	})
}

func (o *duplicatedOutput) readPixels(read func(src []byte, pitch int)) error {
	var mapped dxgi.DXGI_MAPPED_RECT
	if hr := d3d.HRESULT(o.surface.Map(&mapped, dxgi.DXGI_MAP_READ)); hr.Failed() {
		return hr
	}
	defer o.surface.Unmap()
	if mapped.PBits == nil || int(mapped.Pitch) < o.size.X*4 {
		return errors.New("invalid DXGI surface stride")
	}
	src := unsafe.Slice((*byte)(mapped.PBits), int(mapped.Pitch)*o.size.Y)
	read(src, int(mapped.Pitch))
	return nil
}

type desktopOutputPart struct {
	output      *duplicatedOutput
	display     desktopDisplay
	destination image.Rectangle
}

// Call with desktopDuplication locked, including until the returned surfaces
// have been read. Idle outputs keep their previous GPU texture and generation.
func acquireDesktopOutputs(monitors []desktopDisplay, display, layout string, target image.Rectangle, size image.Point) (parts []desktopOutputPart, generation uint64, cursorAreas []image.Rectangle, err error) {
	prepareDesktopDuplication(layout)
	cursorAreas = []image.Rectangle{} // nil represents GDI (cursor always separate)
	for _, d := range monitors {
		if display != "" && display != "all" && d.ID != display {
			continue
		}
		src := monitorRectangle(d)
		dst := desktopDestination(src, target, size)
		if dst.Empty() {
			continue
		}
		o, err := getDuplicatedOutput(d.ID)
		if err != nil {
			return nil, 0, nil, fmt.Errorf("%s desktop duplication: %w", d.Name, err)
		}
		if err := o.update(); err != nil {
			return nil, 0, nil, desktopDuplicationError(d, o, err)
		}
		physical := o.size
		if o.rotation == desktopRotate90 || o.rotation == desktopRotate270 {
			physical = image.Pt(physical.Y, physical.X)
		}
		if physical != image.Pt(d.Width, d.Height) {
			return nil, 0, nil, desktopDuplicationError(d, o, errDesktopDisplayChanged)
		}
		generation = max(generation, o.generation)
		if o.pointerSeparate {
			cursorAreas = append(cursorAreas, src)
		}
		parts = append(parts, desktopOutputPart{o, d, dst})
	}
	return parts, generation, cursorAreas, nil
}

func desktopDuplicationError(d desktopDisplay, o *duplicatedOutput, err error) error {
	if errors.Is(err, d3d.DXGI_ERROR_WAIT_TIMEOUT) {
		return errDesktopFramePending
	}
	o.close()
	delete(desktopDuplication.outputs, d.ID)
	desktopDuplication.retryAfter[d.ID] = time.Now().Add(3 * time.Second)
	return fmt.Errorf("%s desktop duplication: %w", d.Name, err)
}
