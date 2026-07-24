@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
set "BOOTSTRAP=%ROOT%\suiteBinaries\suite-runtime-bootstrap.exe"
set "CONTROLLER_CONFIG=%ROOT%\config\runtime-controller.json"
if not exist "%BOOTSTRAP%" (
  echo [SpringSuite] suite-runtime-bootstrap.exe is missing.
  endlocal & exit /b 1
)
if not exist "%CONTROLLER_CONFIG%" (
  echo [SpringSuite] runtime-controller.json is missing.
  endlocal & exit /b 1
)
"%BOOTSTRAP%" start --config "%CONTROLLER_CONFIG%" --timeout 6m --preloader=false
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
