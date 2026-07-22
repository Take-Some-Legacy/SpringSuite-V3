@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
set "BOOTSTRAP=%ROOT%\suiteBinaries\suite-runtime-bootstrap.exe"
set "CONTROLLER=%ROOT%\suiteBinaries\suite-runtime-controller.exe"
set "CONTROLLER_CONFIG=%ROOT%\config\runtime-controller.json"
if exist "%BOOTSTRAP%" if exist "%CONTROLLER%" if exist "%CONTROLLER_CONFIG%" (
  start "SpringSuite Runtime Controller" /min "%BOOTSTRAP%" start --config "%CONTROLLER_CONFIG%"
  endlocal
  exit /b 0
)
set "SUPERVISOR=%ROOT%\scripts\spring-suite-supervisor.ps1"
if not exist "%SUPERVISOR%" (
  echo [SpringSuite] runtime controller and legacy supervisor are missing.
  exit /b 1
)
if not exist "%ROOT%\spring-suite.jar" (
  echo [SpringSuite] spring-suite.jar was not found.
  exit /b 1
)
start "SpringSuite Supervisor" /min powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%SUPERVISOR%" -Root "%ROOT%" -Port 8090 %*
endlocal
exit /b 0
