@echo off
setlocal
cd /d "%~dp0\.."
if not exist build mkdir build
go test ./...
if errorlevel 1 exit /b 1
go build -o build\suite-cloudflared-wrapper.exe ./cmd/suite-cloudflared-wrapper
endlocal
