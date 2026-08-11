@echo off
setlocal EnableExtensions

for %%S in (
    suite-cloudflared-wrapper
    suite-desktop-capture
    suite-fs-worker
    suite-repo-indexer
    suite-tail-watcher
) do (
    call "%~dp0build-module.bat" "%%S"
    if errorlevel 1 exit /b %ERRORLEVEL%
)

echo [OK] all embedded Go sidecars built successfully.
exit /b 0
