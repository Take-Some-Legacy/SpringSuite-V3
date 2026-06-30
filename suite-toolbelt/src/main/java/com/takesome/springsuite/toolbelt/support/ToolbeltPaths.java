package com.takesome.springsuite.toolbelt.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ToolbeltPaths {
    private ToolbeltPaths() {
    }

    public static Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir")))
                .toAbsolutePath()
                .normalize();
    }

    public static Path resolveRuntimePath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return runtimeRoot().resolve(path).toAbsolutePath().normalize();
    }

    public static Path descriptorRepoRoot(Path scanRoot) {
        Path current = scanRoot.toAbsolutePath().normalize();
        while (current != null) {
            if (current.getFileName() != null && current.getFileName().toString().equalsIgnoreCase("tools")) {
                Path parent = current.getParent();
                if (parent != null) {
                    return parent.toAbsolutePath().normalize();
                }
            }
            current = current.getParent();
        }
        return runtimeRoot();
    }

    public static Optional<Path> findOnPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return Optional.empty();
        }
        List<String> names = executableNames(executableName);
        for (String part : pathEnv.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) {
                continue;
            }
            Path dir = Paths.get(part);
            for (String name : names) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static List<String> executableNames(String name) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of(name);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            return List.of(name);
        }
        return List.of(name + ".exe", name + ".bat", name + ".cmd", name);
    }
}
