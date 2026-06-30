package com.takesome.springsuite.toolbelt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class DescriptorToolRuntime {
    private static final String DESCRIPTOR_SENTINEL = "__takesome_tool_descriptor__";

    public String resolveExecutable(Path descriptorDir, Map<String, Object> raw) {
        String direct = firstNonBlank(
                stringAt(raw, "executable"),
                stringAt(raw, "binary"),
                stringAt(raw, "path"),
                stringAt(raw, "runtime.executable"),
                stringAt(raw, "windows.executable"),
                stringAt(raw, "exec.executable"),
                stringAt(raw, "launcher.executable")
        );
        if (direct.isBlank()) {
            List<String> command = firstListAt(raw, "command", "runtime.command", "windows.command", "exec.command", "launcher.command");
            if (!command.isEmpty() && !isDescriptorSentinel(command.get(0))) {
                direct = command.get(0);
            }
        }
        String resolved = resolvePathOrPathExecutable(descriptorDir, direct);
        if (!resolved.isBlank()) {
            return resolved;
        }
        return resolvePackagedExecutable(descriptorDir, raw).orElse("");
    }

    public List<String> commandTemplate(Map<String, Object> raw, String executable) {
        List<String> command = firstListAt(raw, "command", "runtime.command", "windows.command", "exec.command", "launcher.command");
        if (!command.isEmpty() && !isDescriptorSentinel(command.get(0))) {
            ArrayList<String> template = new ArrayList<>(command);
            if (!executable.isBlank()) {
                template.set(0, executable);
            }
            return List.copyOf(template);
        }
        return executable == null || executable.isBlank()
                ? List.of(DESCRIPTOR_SENTINEL, descriptorId(raw))
                : List.of(executable);
    }

    public List<String> buildRuntimeCommand(ToolDescriptor descriptor, List<String> args) {
        ArrayList<String> runtime = new ArrayList<>();
        List<String> template = descriptor.commandTemplate();
        String executable = descriptor.executable();
        if (!template.isEmpty() && !isDescriptorSentinel(template.get(0))) {
            runtime.addAll(template);
            if (!executable.isBlank()) {
                runtime.set(0, executable);
            }
        } else if (!executable.isBlank()) {
            runtime.add(executable);
        } else {
            runtime.addAll(template);
        }
        if (runtime.isEmpty()) {
            return List.of();
        }
        runtime = new ArrayList<>(wrapPlatformCommand(runtime));
        runtime.addAll(args == null ? List.of() : args);
        return List.copyOf(runtime);
    }

    public boolean isDescriptorSentinel(String value) {
        return value != null && value.trim().equalsIgnoreCase(DESCRIPTOR_SENTINEL);
    }

    private List<String> wrapPlatformCommand(List<String> command) {
        String first = command.get(0);
        String lower = first.toLowerCase(Locale.ROOT);
        ArrayList<String> wrapped = new ArrayList<>();
        if (isWindows() && (lower.endsWith(".bat") || lower.endsWith(".cmd"))) {
            wrapped.add("cmd");
            wrapped.add("/c");
            wrapped.addAll(command);
            return wrapped;
        }
        if (lower.endsWith(".jar")) {
            wrapped.add("java");
            wrapped.add("-jar");
            wrapped.addAll(command);
            return wrapped;
        }
        wrapped.addAll(command);
        return wrapped;
    }

    private String resolvePathOrPathExecutable(Path descriptorDir, String rawPath) {
        if (rawPath == null || rawPath.isBlank() || isDescriptorSentinel(rawPath)) {
            return "";
        }
        Path candidate = Paths.get(rawPath);
        if (candidate.isAbsolute() && Files.isRegularFile(candidate)) {
            return candidate.toAbsolutePath().normalize().toString();
        }
        Path relative = descriptorDir.resolve(candidate).toAbsolutePath().normalize();
        if (Files.isRegularFile(relative)) {
            return relative.toString();
        }
        return findOnPath(rawPath).map(path -> path.toAbsolutePath().normalize().toString()).orElse("");
    }

    private Optional<String> resolvePackagedExecutable(Path descriptorDir, Map<String, Object> raw) {
        if (!Files.isDirectory(descriptorDir)) {
            return Optional.empty();
        }
        List<String> needles = descriptorNeedles(descriptorDir, raw);
        try (Stream<Path> stream = Files.walk(descriptorDir, 8)) {
            return stream.filter(Files::isRegularFile)
                    .filter(this::isExecutableLike)
                    .sorted(Comparator.comparingInt(path -> -score(path, needles)))
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize().toString());
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private List<String> descriptorNeedles(Path descriptorDir, Map<String, Object> raw) {
        ArrayList<String> needles = new ArrayList<>();
        addNeedle(needles, descriptorId(raw));
        addNeedle(needles, stringAt(raw, "name"));
        addNeedle(needles, stringAt(raw, "title"));
        if (descriptorDir.getFileName() != null) {
            addNeedle(needles, descriptorDir.getFileName().toString());
        }
        return needles.stream().distinct().toList();
    }

    private void addNeedle(ArrayList<String> needles, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String value = normalizeToken(raw);
        if (!value.isBlank()) {
            needles.add(value);
        }
        int dot = raw.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < raw.length()) {
            String tail = normalizeToken(raw.substring(dot + 1));
            if (!tail.isBlank()) {
                needles.add(tail);
            }
        }
    }

    private int score(Path path, List<String> needles) {
        String file = normalizeToken(path.getFileName().toString());
        int score = 1;
        for (String needle : needles) {
            if (file.equals(needle)) {
                score += 1000;
            } else if (file.contains(needle) || needle.contains(file)) {
                score += 100;
            }
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            score += 10;
        }
        return score;
    }

    private boolean isExecutableLike(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd") || lower.endsWith(".jar");
    }

    private Optional<Path> findOnPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
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

    private List<String> executableNames(String name) {
        if (!isWindows()) {
            return List.of(name);
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            return List.of(name);
        }
        return List.of(name + ".exe", name + ".bat", name + ".cmd", name);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String descriptorId(Map<String, Object> raw) {
        return firstNonBlank(stringAt(raw, "id"), stringAt(raw, "descriptor_id"), stringAt(raw, "descriptorId"), stringAt(raw, "tool.id"), stringAt(raw, "name"));
    }

    private String stringAt(Map<String, Object> raw, String path) {
        Object value = raw;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) {
                return "";
            }
            value = map.get(part);
        }
        return value instanceof String string ? string.trim() : "";
    }

    private List<String> firstListAt(Map<String, Object> raw, String... paths) {
        for (String path : paths) {
            Object value = objectAt(raw, path);
            if (value instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            if (value instanceof String string && !string.isBlank()) {
                return List.of(string.trim());
            }
        }
        return List.of();
    }

    private Object objectAt(Map<String, Object> raw, String path) {
        Object value = raw;
        for (String part : path.split("\\.")) {
            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }
            value = map.get(part);
        }
        return value;
    }

    private String normalizeToken(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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
