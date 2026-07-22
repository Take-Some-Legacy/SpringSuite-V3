@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
set "SUPERVISOR=%ROOT%\scripts\spring-suite-supervisor.ps1"
if not exist "%SUPERVISOR%" (
  echo [SpringSuite] supervisor is missing: %SUPERVISOR%
  exit /b 1
)
if not exist "%ROOT%\spring-suite.jar" (
  echo [SpringSuite] spring-suite.jar was not found.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-WindowStyle','Hidden','-File','%SUPERVISOR%','-Root','%ROOT%','-Port','8090','-Elevated'); Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WorkingDirectory '%ROOT%' -Verb RunAs -WindowStyle Hidden"
set "RC=%ERRORLEVEL%"
endlocal & exit /b %RC%
