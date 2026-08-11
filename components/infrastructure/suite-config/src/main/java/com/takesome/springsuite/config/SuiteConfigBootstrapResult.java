package com.takesome.springsuite.config;

import java.nio.file.Path;

public record SuiteConfigBootstrapResult(
        Path projectRoot,
        Path configPath,
        Path logFile,
        boolean created,
        boolean supplemented,
        boolean consoleAnsiEnabled,
        boolean consoleAnsiProbe,
        String springAnsiMode
) {
}
