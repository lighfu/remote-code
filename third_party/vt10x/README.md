# vt10x

Local copy of [hinshun/vt10x](https://github.com/hinshun/vt10x/tree/5011da428d02), under the accompanying MIT license.

Remote Code uses a scrollback callback to capture each row before it leaves the
normal screen. The two upstream terminal implementations were identical and share
one source file here. Partial UTF-8 input is returned to the caller for buffering.
