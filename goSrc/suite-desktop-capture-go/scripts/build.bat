@echo off
setlocal
cd /d "%~dp0.."
if not exist "..\..\suiteBinaries" mkdir "..\..\suiteBinaries"
go mod tidy
go build -trimpath -ldflags "-s -w" -o "..\..\suiteBinaries\suite-desktop-capture.exe" .\cmd\suite-desktop-capture
if errorlevel 1 exit /b 1
go run github.com/tc-hib/go-winres@v0.3.3 patch --in windows-resources\suite-desktop-capture\winres.json --delete --no-backup "..\..\suiteBinaries\suite-desktop-capture.exe"
if errorlevel 1 exit /b 1
endlocal
