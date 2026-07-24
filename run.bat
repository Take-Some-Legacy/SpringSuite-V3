@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
set "BOOTSTRAP=%ROOT%\suiteBinaries\suite-runtime-bootstrap.exe"
set "CONTROLLER=%ROOT%\suiteBinaries\suite-runtime-controller.exe"
set "CONTROLLER_CONFIG=%ROOT%\config\runtime-controller.json"
if not exist "%BOOTSTRAP%" (
  echo [SpringSuite] suite-runtime-bootstrap.exe is missing.
  endlocal & exit /b 1
)
if not exist "%CONTROLLER%" (
  echo [SpringSuite] suite-runtime-controller.exe is missing.
  endlocal & exit /b 1
)
if not exist "%CONTROLLER_CONFIG%" (
  echo [SpringSuite] runtime-controller.json is missing.
  endlocal & exit /b 1
)
start "SpringSuite Runtime Bootstrap" /min "%BOOTSTRAP%" start --config "%CONTROLLER_CONFIG%" --timeout 6m
endlocal
exit /b 0
