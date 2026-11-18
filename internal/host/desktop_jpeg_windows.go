//go:build windows

package host

import (
	"image"
	"runtime"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	desktopImaging        = windows.NewLazySystemDLL("gdiplus.dll")
	desktopStartImaging   = desktopImaging.NewProc("GdiplusStartup")
	desktopScanBitmap     = desktopImaging.NewProc("GdipCreateBitmapFromScan0")
	desktopSaveBitmap     = desktopImaging.NewProc("GdipSaveImageToStream")
	desktopDisposeBitmap  = desktopImaging.NewProc("GdipDisposeImage")
	desktopShellStreams   = windows.NewLazySystemDLL("shlwapi.dll")
	desktopNewStream      = desktopShellStreams.NewProc("SHCreateMemStream")
	desktopStreamSize     = desktopShellStreams.NewProc("IStream_Size")
	desktopStreamRead     = desktopShellStreams.NewProc("IStream_Read")
	desktopStreamReset    = desktopShellStreams.NewProc("IStream_Reset")
	desktopStreamRelease  = desktopShellStreams.NewProc("IUnknown_AtomicRelease")
	desktopJPEGEncoder    = windows.GUID{Data1: 0x557cf401, Data2: 0x1a04, Data3: 0x11d3, Data4: [8]byte{0x9a, 0x73, 0x00, 0x00, 0xf8, 0x1e, 0xf3, 0x2e}}
	desktopEncoderQuality = windows.GUID{Data1: 0x1d5be4b5, Data2: 0xfa4a, Data3: 0x452d, Data4: [8]byte{0x9c, 0xdd, 0x5d, 0xb3, 0x51, 0x05, 0xe7, 0xeb}}
)

var desktopImagingReady = sync.OnceValue(func() bool {
	for _, proc := range []*windows.LazyProc{desktopStartImaging, desktopScanBitmap, desktopSaveBitmap, desktopDisposeBitmap, desktopNewStream, desktopStreamSize, desktopStreamRead, desktopStreamReset, desktopStreamRelease} {
		if proc.Find() != nil {
			return false
		}
	}
	var input struct {
		version                        uint32
		callback                       uintptr
		suppressThread, suppressCodecs int32
	}
	input.version = 1
	var token uintptr
	status, _, _ := desktopStartImaging.Call(uintptr(unsafe.Pointer(&token)), uintptr(unsafe.Pointer(&input)), 0)
	return status == 0
})

// GDI+ consumes the capture DIB's BGRX pixels directly. Only the encoded
// stream crosses into Go memory. If unavailable, the caller uses image/jpeg.
func desktopNativeJPEG(raw []byte, size image.Point, quality uint32) []byte {
	if !desktopImagingReady() {
		return nil
	}
	// GDI+ retains scan0 until DisposeImage, including between native calls.
	// Pin is a no-op for the operating system's DIB memory.
	var pinned runtime.Pinner
	pinned.Pin(&raw[0])
	defer pinned.Unpin()
	var bitmap uintptr
	const pixelFormat32bppRGB = 0x00022009
	status, _, _ := desktopScanBitmap.Call(uintptr(size.X), uintptr(size.Y), uintptr(size.X*4), pixelFormat32bppRGB, uintptr(unsafe.Pointer(&raw[0])), uintptr(unsafe.Pointer(&bitmap)))
	if status != 0 {
		return nil
	}
	defer desktopDisposeBitmap.Call(bitmap)
	stream, _, _ := desktopNewStream.Call(0, 0)
	if stream == 0 {
		return nil
	}
	defer desktopStreamRelease.Call(uintptr(unsafe.Pointer(&stream)))
	var params struct {
		count     uint32
		parameter struct {
			id           windows.GUID
			values, kind uint32
			value        *uint32
		}
	}
	params.count = 1
	params.parameter.id = desktopEncoderQuality
	params.parameter.values = 1
	params.parameter.kind = 4 // EncoderParameterValueTypeLong
	params.parameter.value = &quality
	status, _, _ = desktopSaveBitmap.Call(bitmap, stream, uintptr(unsafe.Pointer(&desktopJPEGEncoder)), uintptr(unsafe.Pointer(&params)))
	if status != 0 {
		return nil
	}
	var length uint64
	if hr, _, _ := desktopStreamSize.Call(stream, uintptr(unsafe.Pointer(&length))); hr != 0 || length == 0 || length > 4*desktopMaxNativePixels {
		return nil
	}
	if hr, _, _ := desktopStreamReset.Call(stream); hr != 0 {
		return nil
	}
	data := make([]byte, int(length))
	if hr, _, _ := desktopStreamRead.Call(stream, uintptr(unsafe.Pointer(&data[0])), uintptr(len(data))); hr != 0 {
		return nil
	}
	return data
}
