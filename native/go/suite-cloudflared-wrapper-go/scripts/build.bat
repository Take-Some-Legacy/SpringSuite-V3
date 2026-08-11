@echo off
call "%~dp0..\..\build-module.bat" "suite-cloudflared-wrapper"
exit /b %ERRORLEVEL%
