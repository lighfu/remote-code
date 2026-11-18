@echo off
chcp 65001 >nul
title Remote Code
cd /d "%~dp0"
if exist "%~dp0go.mod" (
  go run ./cmd/remote-code %*
) else (
  "%~dp0remote-code.exe" %*
)
if errorlevel 1 pause
