@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
for %%I in ("%ROOT%.") do set "ROOT=%%~fI"
rem SPRINGSUITE_SINGLE_INSTANCE_GUARD_BEGIN
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\spring-suite-single-instance-check.ps1" -Root "%ROOT%" -Port 8090
set "SPRINGSUITE_GUARD_RC=%ERRORLEVEL%"
if "%SPRINGSUITE_GUARD_RC%"=="10" exit /b 0
if not "%SPRINGSUITE_GUARD_RC%"=="0" exit /b %SPRINGSUITE_GUARD_RC%
rem SPRINGSUITE_SINGLE_INSTANCE_GUARD_END
if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
if not exist "%ROOT%\logs\archive" mkdir "%ROOT%\logs\archive"
if not exist "%ROOT%\logs\crash" mkdir "%ROOT%\logs\crash"
if not exist "%ROOT%\data" mkdir "%ROOT%\data"
if not exist "%ROOT%\.springsuite" mkdir "%ROOT%\.springsuite"
set "JAR=%ROOT%\spring-suite.jar"
if not exist "%JAR%" set "JAR=%ROOT%\suite-app\build\libs\spring-suite.jar"
if not exist "%JAR%" (
  echo [SpringSuite] spring-suite.jar was not found. Run: gradlew.bat :suite-app:bootJar
  exit /b 1
)
set "JAVA_EXE=java.exe"
if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
pushd "%ROOT%"
"%JAVA_EXE%" "-XX:ErrorFile=%ROOT%\logs\crash\hs_err_pid%%p.log" -XX:+ShowCodeDetailsInExceptionMessages -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%JAR%" "--suite-working-directory=%ROOT%" %*
popd
endlocal
