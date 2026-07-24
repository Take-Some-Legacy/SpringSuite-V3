@echo off
setlocal
cd /d "%~dp0\.."
if not exist build mkdir build
go test ./...
if errorlevel 1 exit /b 1
go build -o build\suite-cloudflared-wrapper.exe ./cmd/suite-cloudflared-wrapper
if errorlevel 1 exit /b 1
go run github.com/tc-hib/go-winres@v0.3.3 patch --in windows-resources\suite-cloudflared-wrapper\winres.json --delete --no-backup build\suite-cloudflared-wrapper.exe
if errorlevel 1 exit /b 1
endlocal
