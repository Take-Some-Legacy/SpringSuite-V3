@echo off
call "%~dp0..\..\build-module.bat" "suite-desktop-capture"
exit /b %ERRORLEVEL%
