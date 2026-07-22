@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
set "BOOTSTRAP=%ROOT%\suiteBinaries\suite-runtime-bootstrap.exe"
set "CONTROLLER_CONFIG=%ROOT%\config\runtime-controller.json"
if exist "%BOOTSTRAP%" if exist "%CONTROLLER_CONFIG%" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$arguments=@('start','--config','%CONTROLLER_CONFIG%'); Start-Process -FilePath '%BOOTSTRAP%' -ArgumentList $arguments -WorkingDirectory '%ROOT%' -Verb RunAs -WindowStyle Hidden"
  set "RC=%ERRORLEVEL%"
  endlocal & exit /b %RC%
)
set "SUPERVISOR=%ROOT%\scripts\spring-suite-supervisor.ps1"
if not exist "%SUPERVISOR%" (
  echo [SpringSuite] runtime controller and legacy supervisor are missing.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File','%SUPERVISOR%','-Root','%ROOT%','-Port','8090','-Elevated'); Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WorkingDirectory '%ROOT%' -Verb RunAs -WindowStyle Hidden"
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
