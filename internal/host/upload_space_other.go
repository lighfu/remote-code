//go:build !windows && !linux && !darwin

package host

func uploadFreeBytes(string) (uint64, bool) { return 0, false }
func uploadPlatformDiskFull(error) bool     { return false }
