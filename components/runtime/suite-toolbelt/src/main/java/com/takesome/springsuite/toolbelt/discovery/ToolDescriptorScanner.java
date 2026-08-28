package com.takesome.springsuite.toolbelt.discovery;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.toolbelt.DescriptorToolRuntime;
import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolbeltProperties;
import com.takesome.springsuite.toolbelt.support.ToolDescriptorValues;
import com.takesome.springsuite.toolbelt.support.ToolbeltPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class ToolDescriptorScanner {
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

    public ToolDescriptorScanner(
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

    public ToolDiscoveryResult discover() {
        LinkedHashMap<String, ToolDescriptor> discovered = new LinkedHashMap<>();
        ArrayList<String> diagnostics = new ArrayList<>();
        ArrayList<String> resolvedRoots = new ArrayList<>();
        Set<Path> scannedDescriptorFiles = new LinkedHashSet<>();
        for (Path root : configuredDescriptorRoots(resolvedRoots, diagnostics)) {
            scanDescriptorRoot(root, discovered, scannedDescriptorFiles, diagnostics);
        }
        if (properties.isIncludePathTools()) {
            scanPathTools(discovered);
        }
        return new ToolDiscoveryResult(discovered, diagnostics, resolvedRoots);
    }

    private List<Path> configuredDescriptorRoots(List<String> rootsOut, List<String> diagnosticsOut) {
        LinkedHashMap<String, Path> unique = new LinkedHashMap<>();
        for (String configuredRoot : properties.effectiveScanRoots()) {
            if (configuredRoot == null || configuredRoot.isBlank()) {
                continue;
            }
            Path resolved = ToolbeltPaths.resolveRuntimePath(configuredRoot);
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
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
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
            Path repoRoot = ToolbeltPaths.descriptorRepoRoot(scanRoot);
            Map<String, Object> raw = objectMapper.readValue(descriptorPath.toFile(), MAP_TYPE);
            String id = ToolDescriptorValues.firstNonBlank(
                    ToolDescriptorValues.stringAt(raw, "id"),
                    ToolDescriptorValues.stringAt(raw, "tool_id"),
                    ToolDescriptorValues.stringAt(raw, "descriptor_id"),
                    ToolDescriptorValues.stringAt(raw, "descriptorId"),
                    ToolDescriptorValues.stringAt(raw, "tool.id"),
                    ToolDescriptorValues.stringAt(raw, "name"),
                    scanRoot.relativize(descriptorPath.getParent()).toString().replace('\\', '.').replace('/', '.')
            );
            String normalizedId = ToolDescriptorValues.normalize(id);
            if (normalizedId.isBlank()) {
                diagnosticsOut.add("tool descriptor has empty id: " + descriptorPath);
                return;
            }
            if (discovered.containsKey(normalizedId)) {
                diagnosticsOut.add("duplicate tool id ignored: " + id + " at " + descriptorPath + " first=" + discovered.get(normalizedId).descriptorPath());
                return;
            }

            String name = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "name"), ToolDescriptorValues.stringAt(raw, "display_name"), ToolDescriptorValues.stringAt(raw, "displayName"), id);
            String title = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "title"), ToolDescriptorValues.stringAt(raw, "displayName"), ToolDescriptorValues.stringAt(raw, "display_name"), name);
            String kind = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "kind"), ToolDescriptorValues.stringAt(raw, "type"), "descriptor-cli");
            String description = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "description"), ToolDescriptorValues.stringAt(raw, "summary"));
            String schema = ToolDescriptorValues.stringAt(raw, "schema");
            String owner = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "owner"), ToolDescriptorValues.stringAt(raw, "publisher"));
            String maturity = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "maturity"), ToolDescriptorValues.stringAt(raw, "lifecycle"));
            String sourceType = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "source_type"), ToolDescriptorValues.stringAt(raw, "sourceType"));
            String root = ToolDescriptorValues.stringAt(raw, "root");
            String packageRoot = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "package_root"), ToolDescriptorValues.stringAt(raw, "packageRoot"));
            String sourceRoot = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "source_root"), ToolDescriptorValues.stringAt(raw, "sourceRoot"));
            String cargoManifest = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "cargo_manifest"), ToolDescriptorValues.stringAt(raw, "cargoManifest"));
            String installPath = ToolDescriptorValues.firstNonBlank(ToolDescriptorValues.stringAt(raw, "install_path"), ToolDescriptorValues.stringAt(raw, "installPath"));
            List<String> defaultArgs = ToolDescriptorValues.listAt(raw, "default_args", "defaultArgs");
            List<String> validationArgs = ToolDescriptorValues.listAt(raw, "validation_args", "validationArgs", "validation.args");
            List<String> capabilities = ToolDescriptorValues.listAt(raw, "capabilities");
            List<String> formats = ToolDescriptorValues.listAt(raw, "formats");
            List<String> contentKinds = ToolDescriptorValues.listAt(raw, "content_kinds", "contentKinds");
            List<String> tags = ToolDescriptorValues.mergeLists(
                    ToolDescriptorValues.listAt(raw, "tags", "categories", "category"),
                    capabilities,
                    formats,
                    contentKinds
            );
            String executable = descriptorRuntime.resolveExecutable(descriptorPath.getParent(), repoRoot, raw);
            List<String> template = descriptorRuntime.commandTemplate(raw, executable);
            List<String> safeCommands = ToolDescriptorValues.safeCommandIds(raw);
            boolean alwaysWrite = ToolDescriptorValues.boolAt(raw, "alwaysWrite", "always_write", "annotations.alwaysWrite");
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
            Optional<Path> executable = ToolbeltPaths.findOnPath(tool);
            String id = ToolDescriptorValues.normalize("path." + tool);
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
                    pathValidationArgs(tool),
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

    private List<String> pathValidationArgs(String tool) {
        String normalized = tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "java", "javac" -> List.of("-version");
            case "cloudflared" -> List.of("version");
            default -> List.of("--version");
        };
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
}
