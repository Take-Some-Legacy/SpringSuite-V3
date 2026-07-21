# SpringSuite crash reporting

SpringSuite installs a last-resort uncaught-exception handler before modules, external configuration and Spring Boot start.

When a Java startup failure or uncaught exception occurs, SpringSuite:

1. writes a UTF-8 report to `logs/crash/spring-suite-crash-<timestamp>.txt`;
2. includes the exception, complete stack trace, runtime/build information, thread dump and the last 64 KiB of `logs/spring-suite.log`;
3. shows an always-on-top Swing dialog when a graphical environment is available;
4. provides actions to copy the report, open the report directory, restart SpringSuite or close the process/window.

Windows launchers also set `-XX:ErrorFile=logs/crash/hs_err_pid%p.log`, so a native JVM crash can leave an `hs_err_pid*.log` next to Java crash reports.

Secrets are not intentionally collected. Launch arguments containing token, secret, password, API key or credential markers are redacted.
