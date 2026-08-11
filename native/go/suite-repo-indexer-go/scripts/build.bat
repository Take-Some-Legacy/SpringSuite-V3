@echo off
call "%~dp0..\..\build-module.bat" "suite-repo-indexer"
exit /b %ERRORLEVEL%
