package com.takesome.springsuite.core.platform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class PlatformExecutables {
    private PlatformExecutables() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static String sidecarName(String baseName) {
        String normalized = baseName == null ? "" : baseName.trim();
        if (normalized.isBlank()) {
            return "";
        }
        if (isWindows() && !hasWindowsExecutableExtension(normalized)) {
            return normalized + ".exe";
        }
        if (!isWindows() && hasWindowsExecutableExtension(normalized)) {
            return stripWindowsExecutableExtension(normalized);
        }
        return normalized;
    }

    public static String suiteBinaryPath(String baseName) {
        return "suiteBinaries/" + sidecarName(baseName);
    }

    public static Optional<Path> resolveExecutable(Path runtimeRoot, String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            return Optional.empty();
        }
        Path raw = Paths.get(configuredPath.trim());
        List<Path> candidates = executablePathVariants(raw);
        if (raw.isAbsolute()) {
            return firstRegularFile(candidates);
        }
        Path base = runtimeRoot == null ? Paths.get("").toAbsolutePath().normalize() : runtimeRoot.toAbsolutePath().normalize();
        return firstRegularFile(candidates.stream().map(base::resolve).map(Path::normalize).toList());
    }

    public static Optional<Path> findOnPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank() || executableName == null || executableName.isBlank()) {
            return Optional.empty();
        }
        for (String part : pathEnv.split(java.io.File.pathSeparator)) {
            if (part.isBlank()) {
                continue;
            }
            Path dir = Paths.get(part);
            for (String name : executableNames(executableName)) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    public static List<String> executableNames(String configuredName) {
        String name = configuredName == null ? "" : configuredName.trim();
        if (name.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String lower = name.toLowerCase(Locale.ROOT);
        if (isWindows()) {
            if (hasWindowsExecutableExtension(lower)) {
                names.add(name);
            } else {
                names.add(name + ".exe");
                names.add(name + ".bat");
                names.add(name + ".cmd");
                names.add(name);
            }
        } else {
            if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                String stripped = stripWindowsExecutableExtension(name);
                names.add(stripped);
                if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                    names.add(stripped + ".sh");
                }
                names.add(name);
            } else {
                names.add(name);
                if (!lower.endsWith(".sh")) {
                    names.add(name + ".sh");
                }
            }
        }
        return List.copyOf(names);
    }

    public static List<Path> executablePathVariants(Path raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        String fileName = raw.getFileName() == null ? "" : raw.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (isWindows()) {
            if (hasWindowsExecutableExtension(lower)) {
                paths.add(raw);
            } else {
                paths.add(withSiblingFileName(raw, fileName + ".exe"));
                paths.add(withSiblingFileName(raw, fileName + ".bat"));
                paths.add(withSiblingFileName(raw, fileName + ".cmd"));
                paths.add(raw);
            }
        } else {
            if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                String stripped = stripWindowsExecutableExtension(fileName);
                paths.add(withSiblingFileName(raw, stripped));
                if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
                    paths.add(withSiblingFileName(raw, stripped + ".sh"));
                }
                paths.add(raw);
            } else {
                paths.add(raw);
                if (!lower.endsWith(".sh") && !fileName.isBlank()) {
                    paths.add(withSiblingFileName(raw, fileName + ".sh"));
                }
            }
        }
        return List.copyOf(paths);
    }

    public static boolean isExecutableLike(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return false;
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar")) {
            return true;
        }
        if (isWindows()) {
            return lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd");
        }
        return lower.endsWith(".sh") || !hasWindowsExecutableExtension(lower);
    }

    public static List<String> wrapCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return List.of();
        }
        String first = command.get(0);
        String lower = first.toLowerCase(Locale.ROOT);
        ArrayList<String> wrapped = new ArrayList<>();
        if (isWindows() && (lower.endsWith(".bat") || lower.endsWith(".cmd"))) {
            wrapped.add("cmd");
            wrapped.add("/c");
            wrapped.addAll(command);
            return List.copyOf(wrapped);
        }
        if (!isWindows() && lower.endsWith(".sh")) {
            wrapped.add("sh");
            wrapped.addAll(command);
            return List.copyOf(wrapped);
        }
        if (lower.endsWith(".jar")) {
            wrapped.add("java");
            wrapped.add("-jar");
            wrapped.addAll(command);
            return List.copyOf(wrapped);
        }
        return List.copyOf(command);
    }

    private static Optional<Path> firstRegularFile(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return Optional.of(candidate.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static boolean hasWindowsExecutableExtension(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd");
    }

    private static String stripWindowsExecutableExtension(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            return value.substring(0, value.length() - 4);
        }
        return value;
    }

    private static Path withSiblingFileName(Path path, String fileName) {
        Path parent = path.getParent();
        return parent == null ? Paths.get(fileName) : parent.resolve(fileName);
    }
}
