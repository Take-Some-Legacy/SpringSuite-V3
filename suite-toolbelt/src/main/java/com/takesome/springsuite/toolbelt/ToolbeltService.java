package com.takesome.springsuite.toolbelt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class ToolbeltService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolbeltProperties properties;
    private final OperatorLogService logService;
    private final ObjectMapper objectMapper;
    private final DescriptorToolRuntime descriptorRuntime;
    private final Object lock = new Object();
    private final LinkedHashMap<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private Instant scannedAt = Instant.EPOCH;

    public ToolbeltService(
            ToolbeltProperties properties,
            OperatorLogService logService,
            ObjectMapper objectMapper,
            DescriptorToolRuntime descriptorRuntime
    ) {
        this.properties = properties;
        this.logService = logService;
        this.objectMapper = objectMapper;
        this.descriptorRuntime = descriptorRuntime;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public ToolbeltSummary summary() {
        List<ToolDescriptor> snapshot = listTools();
        Map<String, Long> bySource = snapshot.stream()
                .collect(Collectors.groupingBy(ToolDescriptor::source, LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byKind = snapshot.stream()
                .collect(Collectors.groupingBy(ToolDescriptor::kind, LinkedHashMap::new, Collectors.counting()));
        long available = snapshot.stream().filter(ToolDescriptor::available).count();
        return new ToolbeltSummary(
                properties.isEnabled(),
                snapshot.size(),
                (int) available,
                snapshot.size() - (int) available,
                scannedAt,
                bySource,
                byKind
        );
    }

    public List<ToolDescriptor> listTools() {
        synchronized (lock) {
            return tools.values().stream()
                    .sorted(Comparator.comparing(ToolDescriptor::id))
                    .toList();
        }
    }

    public Optional<ToolDescriptor> find(String idOrName) {
        String normalized = normalize(idOrName);
        synchronized (lock) {
            ToolDescriptor direct = tools.get(normalized);
            if (direct != null) {
                return Optional.of(direct);
            }
            return tools.values().stream()
                    .filter(tool -> normalize(tool.name()).equals(normalized))
                    .findFirst();
        }
    }

    public ToolbeltSummary refresh() {
        LinkedHashMap<String, ToolDescriptor> discovered = new LinkedHashMap<>();
        if (!properties.isEnabled()) {
            synchronized (lock) {
                tools.clear();
                scannedAt = Instant.now();
            }
            return summary();
        }

        for (String configuredRoot : properties.getRoots()) {
            Path root = resolveRuntimePath(configuredRoot);
            scanDescriptorRoot(root, discovered);
        }
        if (properties.isIncludePathTools()) {
            scanPathTools(discovered);
        }

        synchronized (lock) {
            tools.clear();
            tools.putAll(discovered);
            scannedAt = Instant.now();
        }
        ToolbeltSummary summary = summary();
        logService.append(OperatorLogLevel.INFO, "toolbelt", "toolbelt scan complete", Map.of(
                "count", summary.count(),
                "available", summary.availableCount(),
                "unavailable", summary.unavailableCount()
        ));
        return summary;
    }

    public ToolRunResult run(ToolRunRequest request) {
        ToolDescriptor descriptor = find(request.toolId())
                .orElse(null);
        if (descriptor == null) {
            return failed(request.toolId(), List.of(), "", "unknown tool: " + request.toolId(), request.dryRun());
        }
        if (!properties.isAllowExecution() && !request.dryRun()) {
            return failed(descriptor.id(), descriptor.commandTemplate(), "", "tool execution disabled by suite.toolbelt.allow-execution=false", false);
        }
        if (!descriptor.available()) {
            return failed(descriptor.id(), descriptor.commandTemplate(), "", descriptor.availabilityMessage(), request.dryRun());
        }

        List<String> command = descriptorRuntime.buildRuntimeCommand(descriptor, request.args());
        Path cwd = request.cwd().isBlank() ? runtimeRoot() : resolveRuntimePath(request.cwd());
        if (command.isEmpty() || descriptorRuntime.isDescriptorSentinel(command.get(0))) {
            return failed(descriptor.id(), command, cwd.toString(), "descriptor tool executable is not resolved", request.dryRun());
        }

        if (request.dryRun()) {
            return new ToolRunResult(true, descriptor.id(), command, cwd.toString(), null, 0, "", "", "dry run", true, Instant.now());
        }

        Instant start = Instant.now();
        long started = System.nanoTime();
        Process process = null;
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "suite-toolbelt-output-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            process = builder.start();
            Process runningProcess = process;
            if (!request.stdin().isBlank()) {
                try (OutputStream input = process.getOutputStream()) {
                    input.write(request.stdin().getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }

            int stdoutLimit = Math.min(Math.max(1024, request.maxStdoutBytes()), properties.getMaxStdoutBytes());
            int stderrLimit = Math.min(Math.max(1024, request.maxStderrBytes()), properties.getMaxStderrBytes());
            Future<String> stdout = executor.submit(() -> readBounded(runningProcess.getInputStream(), stdoutLimit));
            Future<String> stderr = executor.submit(() -> readBounded(runningProcess.getErrorStream(), stderrLimit));

            long timeoutSec = request.timeoutSec() > 0 ? request.timeoutSec() : properties.getDefaultTimeout().toSeconds();
            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ToolRunResult(false, descriptor.id(), command, cwd.toString(), null, elapsedMs(started),
                        stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS), "tool timed out", false, start);
            }
            int exit = process.exitValue();
            return new ToolRunResult(exit == 0, descriptor.id(), command, cwd.toString(), exit, elapsedMs(started),
                    stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS), exit == 0 ? "ok" : "non-zero exit", false, start);
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return failed(descriptor.id(), command, cwd.toString(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), false);
        } finally {
            executor.shutdownNow();
        }
    }

    private void scanDescriptorRoot(Path root, LinkedHashMap<String, ToolDescriptor> discovered) {
        if (!Files.isDirectory(root)) {
            logService.append(OperatorLogLevel.WARN, "toolbelt", "toolbelt root not found", Map.of("root", root.toString()));
            return;
        }
        try (Stream<Path> stream = Files.walk(root, 12)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("tool.json"))
                    .forEach(path -> scanDescriptor(path, root, discovered));
        } catch (IOException ex) {
            logService.append(OperatorLogLevel.WARN, "toolbelt", "toolbelt scan failed", Map.of(
                    "root", root.toString(),
                    "error", ex.getMessage()
            ));
        }
    }

    private void scanDescriptor(Path descriptorPath, Path root, LinkedHashMap<String, ToolDescriptor> discovered) {
        try {
            Map<String, Object> raw = objectMapper.readValue(descriptorPath.toFile(), MAP_TYPE);
            String id = firstNonBlank(
                    stringAt(raw, "id"),
                    stringAt(raw, "descriptor_id"),
                    stringAt(raw, "descriptorId"),
                    stringAt(raw, "tool.id"),
                    stringAt(raw, "name"),
                    root.relativize(descriptorPath.getParent()).toString().replace('\\', '.').replace('/', '.')
            );
            String name = firstNonBlank(stringAt(raw, "name"), id);
            String title = firstNonBlank(stringAt(raw, "title"), stringAt(raw, "displayName"), name);
            String kind = firstNonBlank(stringAt(raw, "kind"), stringAt(raw, "type"), "descriptor-cli");
            String description = firstNonBlank(stringAt(raw, "description"), stringAt(raw, "summary"));
            String executable = descriptorRuntime.resolveExecutable(descriptorPath.getParent(), raw);
            List<String> template = descriptorRuntime.commandTemplate(raw, executable);
            List<String> safeCommands = listAt(raw, "safeCommandIds", "safe_command_ids", "safeCommands");
            List<String> tags = listAt(raw, "tags", "categories");
            boolean alwaysWrite = boolAt(raw, "alwaysWrite", "always_write", "annotations.alwaysWrite");
            boolean available = !executable.isBlank() && Files.isRegularFile(Paths.get(executable));
            String availability = available ? "available" : "executable not resolved from descriptor";
            ToolDescriptor descriptor = new ToolDescriptor(
                    normalize(id),
                    name,
                    title,
                    "descriptor",
                    kind,
                    description,
                    descriptorPath.toAbsolutePath().normalize().toString(),
                    executable,
                    template,
                    safeCommands,
                    tags,
                    available,
                    availability,
                    alwaysWrite,
                    raw
            );
            discovered.put(descriptor.id(), descriptor);
        } catch (Exception ex) {
            logService.append(OperatorLogLevel.WARN, "toolbelt", "tool descriptor parse failed", Map.of(
                    "path", descriptorPath.toString(),
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
        }
    }

    private void scanPathTools(LinkedHashMap<String, ToolDescriptor> discovered) {
        for (String tool : properties.getPathTools()) {
            Optional<Path> executable = findOnPath(tool);
            String id = normalize("path." + tool);
            discovered.put(id, new ToolDescriptor(
                    id,
                    tool,
                    tool,
                    "path",
                    "external-cli",
                    "Executable discovered on PATH: " + tool,
                    "",
                    executable.map(path -> path.toAbsolutePath().normalize().toString()).orElse(""),
                    executable.map(path -> List.of(path.toAbsolutePath().normalize().toString())).orElse(List.of(tool)),
                    List.of("version", "help"),
                    List.of("path"),
                    executable.isPresent(),
                    executable.map(path -> "available").orElse("not found on PATH"),
                    false,
                    Map.of()
            ));
        }
    }

    private String resolveExecutable(Path descriptorDir, Map<String, Object> raw) {
        String direct = firstNonBlank(
                stringAt(raw, "executable"),
                stringAt(raw, "binary"),
                stringAt(raw, "path")
        );
        if (direct.isBlank()) {
            List<String> command = listAt(raw, "command");
            if (!command.isEmpty()) {
                direct = command.get(0);
            }
        }
        if (direct.isBlank() || direct.startsWith("__takesome_tool_descriptor__")) {
            return "";
        }
        Path path = Paths.get(direct);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize().toString();
        }
        Path relative = descriptorDir.resolve(path).toAbsolutePath().normalize();
        if (Files.exists(relative)) {
            return relative.toString();
        }
        return findOnPath(direct).map(found -> found.toAbsolutePath().normalize().toString()).orElse("");
    }

    private List<String> commandTemplate(Map<String, Object> raw, String executable) {
        List<String> command = listAt(raw, "command");
        if (!command.isEmpty()) {
            return command;
        }
        return executable == null || executable.isBlank() ? List.of() : List.of(executable);
    }

    private Optional<Path> findOnPath(String executableName) {
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

    private List<String> executableNames(String name) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of(name);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe") || name.toLowerCase(Locale.ROOT).endsWith(".bat") || name.toLowerCase(Locale.ROOT).endsWith(".cmd")) {
            return List.of(name);
        }
        return List.of(name + ".exe", name + ".bat", name + ".cmd", name);
    }

    private Path resolveRuntimePath(String configuredPath) {
        Path path = Paths.get(configuredPath);
        if (path.isAbsolute()) {
            return path.toAbsolutePath().normalize();
        }
        return runtimeRoot().resolve(path).toAbsolutePath().normalize();
    }

    private Path runtimeRoot() {
        return Paths.get(System.getProperty("suite.project.root", System.getProperty("user.dir"))).toAbsolutePath().normalize();
    }

    private ToolRunResult failed(String toolId, List<String> command, String cwd, String message, boolean dryRun) {
        return new ToolRunResult(false, toolId == null ? "" : toolId, command, cwd, null, 0, "", "", message, dryRun, Instant.now());
    }

    private long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String readBounded(java.io.InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int allowed = Math.min(read, maxBytes - total);
            if (allowed > 0) {
                out.write(buffer, 0, allowed);
                total += allowed;
            }
            if (total >= maxBytes) {
                break;
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
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

    private boolean boolAt(Map<String, Object> raw, String... paths) {
        for (String path : paths) {
            Object value = objectAt(raw, path);
            if (value instanceof Boolean bool) {
                return bool;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
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

    private List<String> listAt(Map<String, Object> raw, String... paths) {
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
