@echo off
call "%~dp0..\..\build-module.bat" "suite-tail-watcher"
exit /b %ERRORLEVEL%
