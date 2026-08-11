package com.takesome.springsuite.module;

import com.takesome.springsuite.config.SuiteWorkingDirectoryBootstrap;
import java.nio.file.Path;
import java.nio.file.Paths;

final class ModuleBootstrapPaths {
    private ModuleBootstrapPaths() {
    }

    static Path resolveRuntimeRoot() {
        return SuiteWorkingDirectoryBootstrap.install();
    }

    static Path resolveRuntimePath(Path runtimeRoot, String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return runtimeRoot.resolve(path).toAbsolutePath().normalize();
    }

    static String env(String key) {
        return System.getenv(key);
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

}
