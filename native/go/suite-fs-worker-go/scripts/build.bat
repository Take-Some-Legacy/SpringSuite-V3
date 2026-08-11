@echo off
call "%~dp0..\..\build-module.bat" "suite-fs-worker"
exit /b %ERRORLEVEL%
