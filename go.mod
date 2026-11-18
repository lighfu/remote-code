module github.com/lighfu/remote-code

go 1.26.0

replace github.com/hinshun/vt10x => ./third_party/vt10x

require (
	github.com/Microsoft/go-winio v0.6.4
	github.com/UserExistsError/conpty v0.1.4
	github.com/gdamore/tcell/v2 v2.8.1
	github.com/gorilla/websocket v1.5.3
	github.com/hinshun/vt10x v0.0.0-20220301184237-5011da428d02
	github.com/kirides/go-d3d v1.0.1
	github.com/mattn/go-runewidth v0.0.16
	github.com/pelletier/go-toml/v2 v2.2.4
	github.com/skip2/go-qrcode v0.0.0-20200617195104-da1b6568686e
	golang.org/x/sys v0.50.0
	golang.org/x/term v0.48.0
)

require (
	github.com/gdamore/encoding v1.0.1 // indirect
	github.com/lucasb-eyer/go-colorful v1.2.0 // indirect
	github.com/rivo/uniseg v0.4.3 // indirect
	golang.org/x/text v0.21.0 // indirect
)
