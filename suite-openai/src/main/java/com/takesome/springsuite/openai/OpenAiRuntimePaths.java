package com.takesome.springsuite.openai;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Component;

@Component
public class OpenAiRuntimePaths {
    public Path runtimeRoot() {
        String explicit = firstNonBlank(
                System.getProperty("suite.working.directory", ""),
                System.getProperty("suite.project.root", ""),
                System.getenv("NOESIS_SUITE_RUNTIME_ROOT"),
                System.getenv("NOESIS_SUITE_ROOT"),
                System.getenv("NORTHSTAR_SUITE_RUNTIME_ROOT"),
                System.getenv("NORTHSTAR_SUITE_ROOT"),
                System.getenv("TAKESOME_SUITE_ROOT")
        );
        if (!explicit.isBlank()) {
            return Paths.get(explicit).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public Path resolveRuntimePath(String path) {
        Path p = Paths.get(path == null ? "" : path.trim());
        return p.isAbsolute() ? p.toAbsolutePath().normalize() : runtimeRoot().resolve(p).toAbsolutePath().normalize();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
