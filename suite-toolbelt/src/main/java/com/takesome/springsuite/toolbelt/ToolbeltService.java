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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final Set<String> SKIPPED_DESCRIPTOR_PARTS = Set.of(
            ".git",
            ".takesome",
            ".northstar",
            "target",
            "node_modules",
            "logs",
            "cache",
            "dist",
            "out",
            "bin",
            "obj",
            "artifacts",
            "__pycache__"
    );

    private final ToolbeltProperties properties;
    private final OperatorLogService logService;
    private final ObjectMapper objectMapper;
    private final DescriptorToolRuntime descriptorRuntime;
    private final Object lock = new Object();
    private final LinkedHashMap<String, ToolDescriptor> tools = new LinkedHashMap<>();
    private final ArrayList<String> diagnostics = new ArrayList<>();
    private final ArrayList<String> resolvedRoots = new ArrayList<>();
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
        Map<String, Long> bySource = groupBy(snapshot, ToolDescriptor::source);
        Map<String, Long> byKind = groupBy(snapshot, ToolDescriptor::kind);
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

    public ToolInventory inventory() {
        List<ToolDescriptor> snapshot = listTools();
        List<ToolIndexEntry> index = index();
        int descriptorCount = (int) snapshot.stream().filter(tool -> "descriptor".equals(tool.source())).count();
        int pathToolCount = (int) snapshot.stream().filter(tool -> "path".equals(tool.source())).count();
        int availableCount = (int) snapshot.stream().filter(ToolDescriptor::available).count();
        List<String> rootsSnapshot;
        List<String> diagnosticsSnapshot;
        synchronized (lock) {
            rootsSnapshot = List.copyOf(resolvedRoots);
            diagnosticsSnapshot = List.copyOf(diagnostics);
        }
        return new ToolInventory(
                properties.isEnabled(),
                snapshot.size(),
                descriptorCount,
                pathToolCount,
                availableCount,
                snapshot.size() - availableCount,
                scannedAt,
                rootsSnapshot,
                diagnosticsSnapshot,
                groupBy(snapshot, ToolDescriptor::source),
                groupBy(snapshot, ToolDescriptor::kind),
                groupBy(snapshot, ToolDescriptor::owner),
                groupBy(snapshot, ToolDescriptor::maturity),
                groupBy(snapshot, ToolDescriptor::sourceType),
                groupTags(snapshot),
                index
        );
    }

    public List<ToolDescriptor> listTools() {
        synchronized (lock) {
            return tools.values().stream()
                    .sorted(Comparator.comparing(ToolDescriptor::id))
                    .toList();
        }
    }

    public List<ToolIndexEntry> index() {
        return listTools().stream()
                .map(this::indexEntry)
                .sorted(Comparator.comparing(ToolIndexEntry::id))
                .toList();
    }

    public List<ToolDescriptor> search(String query, int limit, String source, String kind, Boolean available, String tag) {
        int safeLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        List<String> terms = tokenize(query == null ? "" : query);
        String sourceFilter = normalize(source);
        String kindFilter = normalize(kind);
        String tagFilter = normalize(tag);
        return listTools().stream()
                .filter(tool -> sourceFilter.isBlank() || normalize(tool.source()).equals(sourceFilter))
                .filter(tool -> kindFilter.isBlank() || normalize(tool.kind()).equals(kindFilter))
                .filter(tool -> available == null || tool.available() == available)
                .filter(tool -> tagFilter.isBlank() || hasTag(tool, tagFilter))
                .map(tool -> new ScoredTool(tool, score(tool, terms)))
                .filter(scored -> scored.score() >= 0)
                .sorted(Comparator.comparingInt(ScoredTool::score).reversed()
                        .thenComparing(scored -> scored.tool().id()))
                .limit(safeLimit)
                .map(ScoredTool::tool)
                .toList();
    }

    public Optional<ToolDescriptor> find(String idOrName) {
        String normalized = normalize(idOrName);
        synchronized (lock) {
            ToolDescriptor direct = tools.get(normalized);
            if (direct != null) {
                return Optional.of(direct);
            }
            return tools.values().stream()
                    .filter(tool -> matchesIdentity(tool, normalized))
                    .findFirst();
        }
    }

    public ToolbeltSummary refresh() {
        LinkedHashMap<String, ToolDescriptor> discovered = new LinkedHashMap<>();
        ArrayList<String> newDiagnostics = new ArrayList<>();
        ArrayList<String> newResolvedRoots = new ArrayList<>();
        if (!properties.isEnabled()) {
            synchronized (lock) {
                tools.clear();
                diagnostics.clear();
                resolvedRoots.clear();
                scannedAt = Instant.now();
            }
            return summary();
        }

        Set<Path> scannedDescriptorFiles = new LinkedHashSet<>();
        for (Path root : configuredDescriptorRoots(newResolvedRoots, newDiagnostics)) {
            scanDescriptorRoot(root, discovered, scannedDescriptorFiles, newDiagnostics);
        }
        if (properties.isIncludePathTools()) {
            scanPathTools(discovered);
        }

        synchronized (lock) {
            tools.clear();
            tools.putAll(discovered);
            diagnostics.clear();
            diagnostics.addAll(newDiagnostics);
            resolvedRoots.clear();
            resolvedRoots.addAll(newResolvedRoots);
            scannedAt = Instant.now();
        }
        ToolbeltSummary summary = summary();
        logService.append(OperatorLogLevel.INFO, "toolbelt", "toolbelt scan complete", Map.of(
                "count", summary.count(),
                "available", summary.availableCount(),
                "unavailable", summary.unavailableCount(),
                "diagnostics", newDiagnostics.size()
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

    private List<Path> configuredDescriptorRoots(List<String> rootsOut, List<String> diagnosticsOut) {
        LinkedHashMap<String, Path> unique = new LinkedHashMap<>();
        for (String configuredRoot : properties.effectiveScanRoots()) {
            if (configuredRoot == null || configuredRoot.isBlank()) {
                continue;
            }
            Path resolved = resolveRuntimePath(configuredRoot);
            rootsOut.add(resolved.toString());
            String key = resolved.toString().toLowerCase(Locale.ROOT);
            if (unique.containsKey(key)) {
                diagnosticsOut.add("duplicate tool root ignored: " + resolved);
                continue;
            }
            unique.put(key, resolved);
        }
        return List.copyOf(unique.values());
    }

    private void scanDescriptorRoot(
            Path root,
            LinkedHashMap<String, ToolDescriptor> discovered,
            Set<Path> scannedDescriptorFiles,
            List<String> diagnosticsOut
    ) {
        if (!Files.isDirectory(root)) {
            String message = "toolbelt root not found: " + root;
            diagnosticsOut.add(message);
            logService.append(OperatorLogLevel.WARN, "toolbelt", "toolbelt root not found", Map.of("root", root.toString()));
            return;
        }
        try (Stream<Path> stream = Files.walk(root, 16)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase("tool.json"))
                    .filter(path -> !isSkippedDescriptorPath(root, path))
                    .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> {
                        Path normalized = path.toAbsolutePath().normalize();
                        if (!scannedDescriptorFiles.add(normalized)) {
                            diagnosticsOut.add("duplicate descriptor path ignored: " + normalized);
                            return;
                        }
                        scanDescriptor(path, root, discovered, diagnosticsOut);
                    });
        } catch (IOException ex) {
            String message = "toolbelt scan failed: " + root + " -> " + ex.getMessage();
            diagnosticsOut.add(message);
            logService.append(OperatorLogLevel.WARN, "toolbelt", "toolbelt scan failed", Map.of(
                    "root", root.toString(),
                    "error", ex.getMessage()
            ));
        }
    }

    private void scanDescriptor(
            Path descriptorPath,
            Path scanRoot,
            LinkedHashMap<String, ToolDescriptor> discovered,
            List<String> diagnosticsOut
    ) {
        try {
            Path repoRoot = descriptorRepoRoot(scanRoot);
            Map<String, Object> raw = objectMapper.readValue(descriptorPath.toFile(), MAP_TYPE);
            String id = firstNonBlank(
                    stringAt(raw, "id"),
                    stringAt(raw, "tool_id"),
                    stringAt(raw, "descriptor_id"),
                    stringAt(raw, "descriptorId"),
                    stringAt(raw, "tool.id"),
                    stringAt(raw, "name"),
                    scanRoot.relativize(descriptorPath.getParent()).toString().replace('\\', '.').replace('/', '.')
            );
            String normalizedId = normalize(id);
            if (normalizedId.isBlank()) {
                diagnosticsOut.add("tool descriptor has empty id: " + descriptorPath);
                return;
            }
            if (discovered.containsKey(normalizedId)) {
                diagnosticsOut.add("duplicate tool id ignored: " + id + " at " + descriptorPath + " first=" + discovered.get(normalizedId).descriptorPath());
                return;
            }

            String name = firstNonBlank(stringAt(raw, "name"), stringAt(raw, "display_name"), stringAt(raw, "displayName"), id);
            String title = firstNonBlank(stringAt(raw, "title"), stringAt(raw, "displayName"), stringAt(raw, "display_name"), name);
            String kind = firstNonBlank(stringAt(raw, "kind"), stringAt(raw, "type"), "descriptor-cli");
            String description = firstNonBlank(stringAt(raw, "description"), stringAt(raw, "summary"));
            String schema = stringAt(raw, "schema");
            String owner = firstNonBlank(stringAt(raw, "owner"), stringAt(raw, "publisher"));
            String maturity = firstNonBlank(stringAt(raw, "maturity"), stringAt(raw, "lifecycle"));
            String sourceType = firstNonBlank(stringAt(raw, "source_type"), stringAt(raw, "sourceType"));
            String root = stringAt(raw, "root");
            String packageRoot = firstNonBlank(stringAt(raw, "package_root"), stringAt(raw, "packageRoot"));
            String sourceRoot = firstNonBlank(stringAt(raw, "source_root"), stringAt(raw, "sourceRoot"));
            String cargoManifest = firstNonBlank(stringAt(raw, "cargo_manifest"), stringAt(raw, "cargoManifest"));
            String installPath = firstNonBlank(stringAt(raw, "install_path"), stringAt(raw, "installPath"));
            List<String> defaultArgs = listAt(raw, "default_args", "defaultArgs");
            List<String> validationArgs = listAt(raw, "validation_args", "validationArgs", "validation.args");
            List<String> capabilities = listAt(raw, "capabilities");
            List<String> formats = listAt(raw, "formats");
            List<String> contentKinds = listAt(raw, "content_kinds", "contentKinds");
            List<String> tags = mergeLists(
                    listAt(raw, "tags", "categories", "category"),
                    capabilities,
                    formats,
                    contentKinds
            );
            String executable = descriptorRuntime.resolveExecutable(descriptorPath.getParent(), repoRoot, raw);
            List<String> template = descriptorRuntime.commandTemplate(raw, executable);
            List<String> safeCommands = safeCommandIds(raw);
            boolean alwaysWrite = boolAt(raw, "alwaysWrite", "always_write", "annotations.alwaysWrite");
            boolean available = !executable.isBlank() && Files.isRegularFile(Paths.get(executable));
            String availability = available ? "available" : "executable not resolved from descriptor";
            ToolDescriptor descriptor = new ToolDescriptor(
                    normalizedId,
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
                    schema,
                    owner,
                    maturity,
                    sourceType,
                    root,
                    packageRoot,
                    sourceRoot,
                    cargoManifest,
                    installPath,
                    defaultArgs,
                    validationArgs,
                    capabilities,
                    formats,
                    contentKinds,
                    available,
                    availability,
                    alwaysWrite,
                    raw
            );
            discovered.put(descriptor.id(), descriptor);
        } catch (Exception ex) {
            String message = "tool descriptor parse failed: " + descriptorPath + " -> " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            diagnosticsOut.add(message);
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
                    "",
                    "host",
                    "",
                    "path",
                    "",
                    "",
                    "",
                    "",
                    "",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    executable.isPresent(),
                    executable.map(path -> "available").orElse("not found on PATH"),
                    false,
                    Map.of()
            ));
        }
    }

    private ToolIndexEntry indexEntry(ToolDescriptor tool) {
        List<String> terms = tokenize(searchableText(tool)).stream()
                .distinct()
                .limit(160)
                .toList();
        return new ToolIndexEntry(
                tool.id(),
                publicToolName(tool.id()),
                tool.name(),
                tool.title(),
                tool.source(),
                tool.kind(),
                tool.descriptorPath(),
                tool.available(),
                terms
        );
    }

    private int score(ToolDescriptor tool, List<String> terms) {
        if (terms.isEmpty()) {
            return 1;
        }
        String id = normalize(tool.id());
        String publicName = normalize(publicToolName(tool.id()));
        String name = normalize(tool.name());
        String title = normalize(tool.title());
        String description = normalize(tool.description());
        String searchable = normalize(searchableText(tool));
        int score = 0;
        for (String term : terms) {
            if (!searchable.contains(term)) {
                return -1;
            }
            if (id.equals(term) || publicName.equals(term)) {
                score += 1000;
            } else if (name.equals(term) || title.equals(term)) {
                score += 600;
            } else if (id.contains(term) || publicName.contains(term)) {
                score += 250;
            } else if (name.contains(term) || title.contains(term)) {
                score += 180;
            } else if (description.contains(term)) {
                score += 80;
            } else {
                score += 20;
            }
        }
        return score;
    }

    private String searchableText(ToolDescriptor tool) {
        StringBuilder builder = new StringBuilder(1024);
        append(builder, tool.id());
        append(builder, publicToolName(tool.id()));
        append(builder, tool.name());
        append(builder, tool.title());
        append(builder, tool.source());
        append(builder, tool.kind());
        append(builder, tool.description());
        append(builder, tool.schema());
        append(builder, tool.owner());
        append(builder, tool.maturity());
        append(builder, tool.sourceType());
        append(builder, tool.descriptorPath());
        append(builder, tool.executable());
        appendList(builder, tool.commandTemplate());
        appendList(builder, tool.safeCommandIds());
        appendList(builder, tool.tags());
        appendList(builder, tool.defaultArgs());
        appendList(builder, tool.validationArgs());
        appendList(builder, tool.capabilities());
        appendList(builder, tool.formats());
        appendList(builder, tool.contentKinds());
        appendRawValues(builder, tool.raw(), 0);
        return builder.toString();
    }

    private boolean matchesIdentity(ToolDescriptor tool, String normalized) {
        return normalize(tool.id()).equals(normalized)
                || normalize(tool.name()).equals(normalized)
                || normalize(tool.title()).equals(normalized)
                || normalize(publicToolName(tool.id())).equals(normalized);
    }

    private boolean hasTag(ToolDescriptor tool, String normalizedTag) {
        return mergeLists(tool.tags(), tool.capabilities(), tool.formats(), tool.contentKinds()).stream()
                .map(this::normalize)
                .anyMatch(value -> value.equals(normalizedTag) || value.contains(normalizedTag));
    }

    private Map<String, Long> groupBy(List<ToolDescriptor> snapshot, java.util.function.Function<ToolDescriptor, String> keyFunction) {
        return snapshot.stream()
                .map(keyFunction)
                .map(value -> value == null || value.isBlank() ? "unknown" : value)
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }

    private Map<String, Long> groupTags(List<ToolDescriptor> snapshot) {
        return snapshot.stream()
                .flatMap(tool -> mergeLists(tool.tags(), tool.capabilities(), tool.formats(), tool.contentKinds()).stream())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(value -> value, LinkedHashMap::new, Collectors.counting()));
    }

    private boolean isSkippedDescriptorPath(Path root, Path descriptorPath) {
        Path relative;
        try {
            relative = root.toAbsolutePath().normalize().relativize(descriptorPath.toAbsolutePath().normalize());
        } catch (IllegalArgumentException ex) {
            relative = descriptorPath;
        }
        for (Path part : relative) {
            if (SKIPPED_DESCRIPTOR_PARTS.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Path descriptorRepoRoot(Path scanRoot) {
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

    private List<String> safeCommandIds(Map<String, Object> raw) {
        LinkedHashSet<String> safe = new LinkedHashSet<>(listAt(raw, "safeCommandIds", "safe_command_ids", "safeCommands"));
        Object commandsRaw = objectAt(raw, "commands");
        if (commandsRaw instanceof List<?> commands) {
            for (Object commandRaw : commands) {
                if (!(commandRaw instanceof Map<?, ?> command)) {
                    continue;
                }
                String commandId = firstNonBlank(
                        stringFromObject(command.get("id")),
                        stringFromObject(command.get("command_id")),
                        stringFromObject(command.get("commandId"))
                );
                if (commandId.isBlank()) {
                    continue;
                }
                boolean explicitlySafe = boolFromObject(command.get("safe"));
                boolean readOnlySafe = boolFromObject(command.get("readOnlyHint"))
                        && boolFromObject(command.get("idempotentHint"))
                        && !boolFromObject(command.get("destructiveHint"));
                if (explicitlySafe || readOnlySafe) {
                    safe.add(commandId);
                }
            }
        }
        return List.copyOf(safe);
    }

    @SafeVarargs
    private final List<String> mergeLists(List<String>... lists) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (List<String> values : lists) {
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    merged.add(value.trim());
                }
            }
        }
        return List.copyOf(merged);
    }

    private void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(' ').append(value);
        }
    }

    private void appendList(StringBuilder builder, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            append(builder, value);
        }
    }

    private void appendRawValues(StringBuilder builder, Object value, int depth) {
        if (value == null || depth > 5) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                append(builder, String.valueOf(entry.getKey()));
                appendRawValues(builder, entry.getValue(), depth + 1);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                appendRawValues(builder, item, depth + 1);
            }
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            append(builder, String.valueOf(value));
        }
    }

    private List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String[] parts = value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}._-]+");
        ArrayList<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) {
                result.add(part);
            }
        }
        return result;
    }

    private String publicToolName(String descriptorId) {
        String raw = descriptorId == null ? "" : descriptorId.trim();
        String text = raw.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        text = text.replaceAll("^_+|_+$", "");
        if (text.isBlank() || !Character.isAlphabetic(text.charAt(0))) {
            text = "tool" + (text.isBlank() ? "" : "_" + text);
        }
        if (!text.startsWith("tool_")) {
            text = "tool_" + text;
        }
        return text.length() > 64 ? text.substring(0, 64) : text;
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

    private String stringAt(Map<String, Object> raw, String path) {
        Object value = objectAt(raw, path);
        return stringFromObject(value);
    }

    private String stringFromObject(Object value) {
        return value instanceof String string ? string.trim() : "";
    }

    private boolean boolAt(Map<String, Object> raw, String... paths) {
        for (String path : paths) {
            if (boolFromObject(objectAt(raw, path))) {
                return true;
            }
        }
        return false;
    }

    private boolean boolFromObject(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String string) {
            String normalized = string.trim().toLowerCase(Locale.ROOT);
            return normalized.equals("true") || normalized.equals("yes") || normalized.equals("1");
        }
        return false;
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

    private List<String> listAt(Map<String, Object> raw, String... paths) {
        for (String path : paths) {
            Object value = objectAt(raw, path);
            if (value instanceof List<?> list) {
                return list.stream()
                        .map(String::valueOf)
                        .filter(item -> !item.isBlank())
                        .toList();
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

    private record ScoredTool(ToolDescriptor tool, int score) {
    }
}
