@echo off
rem Resolve the interactive user's cloudflared context before bootstrap/controller
rem detach. The controller and JVM inherit these values even when the child process
rem is hidden or elevated.
if not defined SPRING_SUITE_CLOUDFLARED_USER_PROFILE if defined USERPROFILE set "SPRING_SUITE_CLOUDFLARED_USER_PROFILE=%USERPROFILE%"
if defined SPRING_SUITE_CLOUDFLARED_EXECUTABLE exit /b 0

set "SPRING_SUITE_RESOLVE_ROOT=%~1"
if defined SPRING_SUITE_RESOLVE_ROOT if exist "%SPRING_SUITE_RESOLVE_ROOT%\suiteBinaries\cloudflared.exe" (
  set "SPRING_SUITE_CLOUDFLARED_EXECUTABLE=%SPRING_SUITE_RESOLVE_ROOT%\suiteBinaries\cloudflared.exe"
  exit /b 0
)

for /f "delims=" %%I in ('where.exe cloudflared.exe 2^>nul') do if not defined SPRING_SUITE_CLOUDFLARED_EXECUTABLE set "SPRING_SUITE_CLOUDFLARED_EXECUTABLE=%%~fI"
if defined SPRING_SUITE_CLOUDFLARED_EXECUTABLE exit /b 0

if defined LOCALAPPDATA if exist "%LOCALAPPDATA%\Microsoft\WinGet\Links\cloudflared.exe" set "SPRING_SUITE_CLOUDFLARED_EXECUTABLE=%LOCALAPPDATA%\Microsoft\WinGet\Links\cloudflared.exe"
if defined SPRING_SUITE_CLOUDFLARED_EXECUTABLE exit /b 0
if defined ChocolateyInstall if exist "%ChocolateyInstall%\bin\cloudflared.exe" set "SPRING_SUITE_CLOUDFLARED_EXECUTABLE=%ChocolateyInstall%\bin\cloudflared.exe"
if defined SPRING_SUITE_CLOUDFLARED_EXECUTABLE exit /b 0
if defined SCOOP if exist "%SCOOP%\shims\cloudflared.exe" set "SPRING_SUITE_CLOUDFLARED_EXECUTABLE=%SCOOP%\shims\cloudflared.exe"
if defined SPRING_SUITE_CLOUDFLARED_EXECUTABLE exit /b 0
if defined USERPROFILE if exist "%USERPROFILE%\.cloudflared\cloudflared.exe" set "SPRING_SUITE_CLOUDFLARED_EXECUTABLE=%USERPROFILE%\.cloudflared\cloudflared.exe"
exit /b 0
