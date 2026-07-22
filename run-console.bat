@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
set "CONTROLLER=%ROOT%\suiteBinaries\suite-runtime-controller.exe"
set "CONTROLLER_CONFIG=%ROOT%\config\runtime-controller.json"
if exist "%CONTROLLER%" if exist "%CONTROLLER_CONFIG%" (
  "%CONTROLLER%" run --config "%CONTROLLER_CONFIG%"
  set "RC=%ERRORLEVEL%"
  endlocal & exit /b %RC%
)
set "SUPERVISOR=%ROOT%\scripts\spring-suite-supervisor.ps1"
if not exist "%SUPERVISOR%" (
  echo [SpringSuite] runtime controller and legacy supervisor are missing.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SUPERVISOR%" -Root "%ROOT%" -Port 8090 -Console %*
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
