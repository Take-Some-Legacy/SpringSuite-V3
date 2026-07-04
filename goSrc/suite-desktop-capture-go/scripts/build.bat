@echo off
setlocal
cd /d "%~dp0.."
if not exist "..\..\suiteBinaries" mkdir "..\..\suiteBinaries"
go mod tidy
go build -trimpath -ldflags "-s -w" -o "..\..\suiteBinaries\suite-desktop-capture.exe" .\cmd\suite-desktop-capture
endlocal
