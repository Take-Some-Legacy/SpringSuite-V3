@echo off
setlocal
cd /d "%~dp0.."
if not exist "..\..\suiteBinaries" mkdir "..\..\suiteBinaries"
go test ./...
if errorlevel 1 exit /b 1
go build -trimpath -ldflags "-s -w" -o "..\..\suiteBinaries\suite-fs-worker.exe" .\cmd\suite-fs-worker
if errorlevel 1 exit /b 1
go run github.com/tc-hib/go-winres@v0.3.3 patch --in windows-resources\suite-fs-worker\winres.json --delete --no-backup "..\..\suiteBinaries\suite-fs-worker.exe"
if errorlevel 1 exit /b 1
endlocal
