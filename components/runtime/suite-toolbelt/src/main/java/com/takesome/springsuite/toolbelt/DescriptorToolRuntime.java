package com.takesome.springsuite.toolbelt;

import com.takesome.springsuite.core.platform.PlatformExecutables;
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
        return resolveExecutable(descriptorDir, descriptorDir, raw);
    }

    public String resolveExecutable(Path descriptorDir, Path repoRoot, Map<String, Object> raw) {
        Path normalizedDescriptorDir = descriptorDir.toAbsolutePath().normalize();
        Path normalizedRepoRoot = repoRoot == null ? normalizedDescriptorDir : repoRoot.toAbsolutePath().normalize();
        Path packageRoot = resolveConfiguredPath(normalizedRepoRoot, normalizedDescriptorDir, firstNonBlank(
                stringAt(raw, "package_root"),
                stringAt(raw, "packageRoot")
        ), normalizedDescriptorDir);

        ArrayList<String> candidates = new ArrayList<>();
        for (String path : platformExecutablePaths()) {
            addCandidate(candidates, stringAt(raw, path));
        }
        addCandidate(candidates, stringAt(raw, "executable"));
        addCandidate(candidates, stringAt(raw, "binary"));
        addCandidate(candidates, stringAt(raw, "path"));
        addCandidate(candidates, stringAt(raw, "runtime.executable"));
        addCandidate(candidates, stringAt(raw, "exec.executable"));
        addCandidate(candidates, stringAt(raw, "launcher.executable"));
        addCandidate(candidates, stringAt(raw, "install_path"));
        addCandidate(candidates, stringAt(raw, "installPath"));

        List<String> command = firstListAt(raw, commandPathOrder());
        if (!command.isEmpty() && !isDescriptorSentinel(command.get(0))) {
            addCandidate(candidates, command.get(0));
        }

        for (String candidate : candidates) {
            String resolved = resolveCandidate(normalizedDescriptorDir, packageRoot, normalizedRepoRoot, candidate);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        return resolvePackagedExecutable(normalizedDescriptorDir, raw).orElse("");
    }

    public List<String> commandTemplate(Map<String, Object> raw, String executable) {
        List<String> command = firstListAt(raw, commandPathOrder());
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
        runtime = new ArrayList<>(PlatformExecutables.wrapCommand(runtime));
        runtime.addAll(args == null ? List.of() : args);
        return List.copyOf(runtime);
    }

    public boolean isDescriptorSentinel(String value) {
        return value != null && value.trim().equalsIgnoreCase(DESCRIPTOR_SENTINEL);
    }

    private void addCandidate(List<String> candidates, String raw) {
        if (raw != null && !raw.isBlank() && !isDescriptorSentinel(raw)) {
            candidates.add(raw.trim());
        }
    }

    private String resolveCandidate(Path descriptorDir, Path packageRoot, Path repoRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank() || isDescriptorSentinel(rawPath)) {
            return "";
        }
        Path candidate = Paths.get(rawPath);
        if (candidate.isAbsolute()) {
            for (Path variant : PlatformExecutables.executablePathVariants(candidate)) {
                if (Files.isRegularFile(variant)) {
                    return variant.toAbsolutePath().normalize().toString();
                }
            }
            return "";
        }
        for (Path base : List.of(packageRoot, descriptorDir, repoRoot)) {
            if (base == null) {
                continue;
            }
            for (Path variant : PlatformExecutables.executablePathVariants(candidate)) {
                Path resolved = base.resolve(variant).toAbsolutePath().normalize();
                if (Files.isRegularFile(resolved)) {
                    return resolved.toString();
                }
            }
        }
        return PlatformExecutables.findOnPath(rawPath).map(path -> path.toAbsolutePath().normalize().toString()).orElse("");
    }

    private Path resolveConfiguredPath(Path repoRoot, Path descriptorDir, String rawPath, Path fallback) {
        if (rawPath == null || rawPath.isBlank()) {
            return fallback;
        }
        Path path = Paths.get(rawPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        Path repoRelative = repoRoot.resolve(path).toAbsolutePath().normalize();
        if (Files.exists(repoRelative)) {
            return repoRelative;
        }
        return descriptorDir.resolve(path).toAbsolutePath().normalize();
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
        if (PlatformExecutables.isExecutableLike(path)) {
            score += 10;
        }
        return score;
    }

    private boolean isExecutableLike(Path path) {
        return PlatformExecutables.isExecutableLike(path);
    }

    private String[] commandPathOrder() {
        ArrayList<String> paths = new ArrayList<>();
        for (String prefix : platformPrefixes()) {
            paths.add(prefix + ".command");
        }
        paths.addAll(List.of("command", "runtime.command", "exec.command", "launcher.command"));
        return paths.toArray(String[]::new);
    }

    private List<String> platformExecutablePaths() {
        ArrayList<String> paths = new ArrayList<>();
        for (String prefix : platformPrefixes()) {
            paths.add(prefix + ".executable");
            paths.add(prefix + ".binary");
            paths.add(prefix + ".path");
        }
        return List.copyOf(paths);
    }

    private List<String> platformPrefixes() {
        if (PlatformExecutables.isWindows()) {
            return List.of("windows", "win32", "win");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return List.of("macos", "darwin", "unix", "posix", "linux");
        }
        return List.of("linux", "unix", "posix");
    }

    private String descriptorId(Map<String, Object> raw) {
        return firstNonBlank(
                stringAt(raw, "id"),
                stringAt(raw, "tool_id"),
                stringAt(raw, "descriptor_id"),
                stringAt(raw, "descriptorId"),
                stringAt(raw, "tool.id"),
                stringAt(raw, "name")
        );
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
