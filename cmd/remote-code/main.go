package main

import (
	"fmt"
	"os"

	"github.com/lighfu/remote-code/internal/host"
)

func main() {
	if err := host.Run(os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, "[Remote Code]", err)
		os.Exit(1)
	}
}
