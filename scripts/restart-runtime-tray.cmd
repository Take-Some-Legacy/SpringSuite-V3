@echo off
setlocal
set "RUNTIME_ROOT=%~dp0.."
set "TRAY=%RUNTIME_ROOT%\suiteBinaries\suite-runtime-tray.exe"
set "CONFIG=%RUNTIME_ROOT%\config\runtime-controller.json"

if not exist "%TRAY%" (
    echo SpringSuite tray executable is missing: "%TRAY%"
    exit /b 2
)
if not exist "%CONFIG%" (
    echo SpringSuite runtime controller config is missing: "%CONFIG%"
    exit /b 3
)

taskkill /IM suite-runtime-tray.exe /F >nul 2>&1
timeout /t 1 /nobreak >nul
start "SpringSuite Runtime Tray" "%TRAY%" run --config "%CONFIG%"
exit /b 0
