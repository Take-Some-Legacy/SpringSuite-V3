package com.takesome.springsuite.module;

import java.nio.file.Path;
import java.nio.file.Paths;

final class ModuleBootstrapPaths {
    private ModuleBootstrapPaths() {
    }

    static Path resolveRuntimeRoot() {
        String explicit = firstNonBlank(System.getProperty("suite.home"), env("SPRING_SUITE_HOME"));
        if (!explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
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
