@echo off
setlocal
cd /d "%~dp0\.."
if not exist build mkdir build
"C:\Program Files\Go\bin\go.exe" test ./...
if errorlevel 1 exit /b 1
"C:\Program Files\Go\bin\go.exe" build -o build\suite-repo-indexer.exe ./cmd/suite-repo-indexer
if errorlevel 1 exit /b 1
go run github.com/tc-hib/go-winres@v0.3.3 patch --in windows-resources\suite-repo-indexer\winres.json --delete --no-backup build\suite-repo-indexer.exe
if errorlevel 1 exit /b 1
endlocal
