@echo off
setlocal EnableExtensions

if "%~1"=="" (
    echo Usage: build-module.bat ^<sidecar-name^>
    exit /b 2
)

set "SIDECAR_NAME=%~1"
set "NATIVE_GO_ROOT=%~dp0"
set "MODULE_DIR=%NATIVE_GO_ROOT%%SIDECAR_NAME%-go"
for %%I in ("%NATIVE_GO_ROOT%..\..") do set "REPOSITORY_ROOT=%%~fI"
set "OUTPUT_DIR=%REPOSITORY_ROOT%\suiteBinaries"
set "BUILD_DIR=%MODULE_DIR%\build"
set "BUILD_EXE=%BUILD_DIR%\%SIDECAR_NAME%.exe"
set "OUTPUT_EXE=%OUTPUT_DIR%\%SIDECAR_NAME%.exe"
set "WINRES=%MODULE_DIR%\windows-resources\%SIDECAR_NAME%\winres.json"

if not exist "%MODULE_DIR%\go.mod" (
    echo [ERROR] Go module not found: %MODULE_DIR%
    exit /b 3
)
if not exist "%WINRES%" (
    echo [ERROR] Windows resource descriptor not found: %WINRES%
    exit /b 4
)

where go >nul 2>nul
if errorlevel 1 (
    echo [ERROR] go.exe is not available on PATH.
    exit /b 5
)

if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"
if errorlevel 1 exit /b %ERRORLEVEL%
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"
if errorlevel 1 exit /b %ERRORLEVEL%

pushd "%MODULE_DIR%"
go test ./...
if errorlevel 1 goto :failed

go build -trimpath -ldflags "-s -w" -o "%BUILD_EXE%" ".\cmd\%SIDECAR_NAME%"
if errorlevel 1 goto :failed

go run github.com/tc-hib/go-winres@v0.3.3 patch --in "%WINRES%" --delete --no-backup "%BUILD_EXE%"
if errorlevel 1 goto :failed

copy /Y "%BUILD_EXE%" "%OUTPUT_EXE%" >nul
if errorlevel 1 goto :failed

popd
echo [OK] built %SIDECAR_NAME% -^> %OUTPUT_EXE%
exit /b 0

:failed
set "BUILD_EXIT=%ERRORLEVEL%"
popd
echo [ERROR] failed to build %SIDECAR_NAME% ^(exit %BUILD_EXIT%^)
exit /b %BUILD_EXIT%
