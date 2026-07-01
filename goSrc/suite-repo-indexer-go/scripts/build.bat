@echo off
setlocal
cd /d "%~dp0\.."
if not exist build mkdir build
"C:\Program Files\Go\bin\go.exe" test ./...
if errorlevel 1 exit /b 1
"C:\Program Files\Go\bin\go.exe" build -o build\suite-repo-indexer.exe ./cmd/suite-repo-indexer
endlocal
