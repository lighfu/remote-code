package host

import (
	"context"
	"errors"
	"fmt"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	"github.com/moutend/go-wca/pkg/wca"
	"golang.org/x/sys/windows"
)

// Capture the default playback endpoint in shared loopback mode. WASAPI
// converts the endpoint's mix format to the PCM format used by Android.
func captureDesktopAudio(ctx context.Context, shutdown <-chan struct{}, emit func([]byte) error) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	if err := windows.CoInitializeEx(0, windows.COINIT_MULTITHREADED); err != nil && !errors.Is(err, syscall.Errno(1)) { // S_FALSE is success
		return err
	}
	defer windows.CoUninitialize()
	var devices *wca.IMMDeviceEnumerator
	if err := wca.CoCreateInstance(wca.CLSID_MMDeviceEnumerator, 0, wca.CLSCTX_INPROC_SERVER, wca.IID_IMMDeviceEnumerator, &devices); err != nil {
		return err
	}
	defer devices.Release()
	var device *wca.IMMDevice
	if err := devices.GetDefaultAudioEndpoint(wca.ERender, wca.EMultimedia, &device); err != nil {
		return fmt.Errorf("opening the Windows playback device: %w", err)
	}
	defer device.Release()
	var deviceID string
	if err := device.GetId(&deviceID); err != nil {
		return err
	}
	var client *wca.IAudioClient
	// IMMDevice::Activate takes CLSCTX by value. The v0.3.0 binding passes its address.
	hr, _, _ := syscall.SyscallN(device.VTable().Activate, uintptr(unsafe.Pointer(device)), uintptr(unsafe.Pointer(wca.IID_IAudioClient)),
		wca.CLSCTX_INPROC_SERVER, 0, uintptr(unsafe.Pointer(&client)))
	if int32(hr) < 0 {
		return fmt.Errorf("activating the Windows playback device: HRESULT %#x", uint32(hr))
	}
	defer client.Release()
	const frameBytes = 4 // 16-bit stereo
	format := wca.WAVEFORMATEX{WFormatTag: wca.WAVE_FORMAT_PCM, NChannels: 2, NSamplesPerSec: 48000,
		NAvgBytesPerSec: 48000 * frameBytes, NBlockAlign: frameBytes, WBitsPerSample: 16}
	flags := uint32(wca.AUDCLNT_STREAMFLAGS_LOOPBACK | wca.AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | wca.AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY)
	if err := client.Initialize(wca.AUDCLNT_SHAREMODE_SHARED, flags, 1_000_000, 0, &format, nil); err != nil {
		return fmt.Errorf("starting Windows loopback capture: %w", err)
	}
	var capture *wca.IAudioCaptureClient
	if err := client.GetService(wca.IID_IAudioCaptureClient, &capture); err != nil {
		return err
	}
	defer capture.Release()
	if err := client.Start(); err != nil {
		return err
	}
	defer client.Stop()
	if err := emit(nil); err != nil {
		return err
	}
	ticker := time.NewTicker(20 * time.Millisecond)
	defer ticker.Stop()
	checkDevice := time.NewTicker(3 * time.Second)
	defer checkDevice.Stop()
	lastWrite := time.Now()
	pcm := make([]byte, 0, 4800*frameBytes)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-shutdown:
			return context.Canceled
		case <-checkDevice.C:
			var current *wca.IMMDevice
			if err := devices.GetDefaultAudioEndpoint(wca.ERender, wca.EMultimedia, &current); err != nil {
				return err
			}
			var currentID string
			err := current.GetId(&currentID)
			current.Release()
			if err != nil || currentID != deviceID {
				return err // Reconnect the viewer to the new default output.
			}
		case <-ticker.C:
		}
		pcm = pcm[:0]
		for len(pcm) < 4800*frameBytes {
			var pending, frames, flags uint32
			if err := capture.GetNextPacketSize(&pending); err != nil {
				return err
			}
			if pending == 0 {
				break
			}
			var data *byte
			if err := capture.GetBuffer(&data, &frames, &flags, nil, nil); err != nil {
				return err
			}
			if frames == 0 || frames > 48000 || (data == nil && flags&wca.AUDCLNT_BUFFERFLAGS_SILENT == 0) {
				capture.ReleaseBuffer(frames)
				return errors.New("invalid Windows audio packet")
			}
			size := int(frames) * frameBytes
			if flags&wca.AUDCLNT_BUFFERFLAGS_SILENT != 0 {
				pcm = append(pcm, make([]byte, size)...)
			} else {
				pcm = append(pcm, unsafe.Slice(data, size)...)
			}
			if err := capture.ReleaseBuffer(frames); err != nil {
				return err
			}
		}
		if len(pcm) == 0 {
			if time.Since(lastWrite) < time.Second {
				continue
			}
			// An idle endpoint can stop issuing packets. Keep the connection alive with a short silent block.
			pcm = append(pcm, make([]byte, 960*frameBytes)...)
		}
		if err := emit(pcm); err != nil {
			return err
		}
		lastWrite = time.Now()
	}
}
