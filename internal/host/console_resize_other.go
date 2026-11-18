//go:build !windows

package host

func resizeConsoleWindow(columns, rows, neededColumns, neededRows int) bool { return false }
