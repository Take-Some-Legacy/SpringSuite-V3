package com.takesome.springsuite.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Read-only access to supervisor incident reports for connected operators and AI.
 */
@Component
public final class IncidentCommand implements SuiteCommand {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final int DEFAULT_LIST_LIMIT = 20;
    private static final int MAX_LIST_LIMIT = 100;

    private final ObjectMapper objectMapper;

    public IncidentCommand(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "incident",
                List.of("last-incident", "recovery-incident"),
                "diagnostics",
                "Read SpringSuite supervisor incidents prepared for operator and AI analysis.",
                "Returns the current recovery incident or a bounded list of recent incident reports. It never mutates incident data.",
                "incident [current|list [limit]]",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).trim().toLowerCase(Locale.ROOT);
        if (action.isBlank() || action.equals("current") || action.equals("latest")) {
            return current();
        }
        if (action.equals("list") || action.equals("recent")) {
            return list(parseLimit(invocation.arg(1)));
        }
        return CommandExecutionResult.failed(
                "incident_action_invalid",
                "Unknown incident action. Use: incident current or incident list [limit]."
        );
    }

    private CommandExecutionResult current() {
        Path path = incidentsRoot().resolve("current.json");
        if (!Files.isRegularFile(path)) {
            return new CommandExecutionResult(
                    true,
                    "incident_none",
                    "No current SpringSuite recovery incident is available.",
                    Map.of(
                            "available", false,
                            "path", path.toString(),
                            "aiInstruction", "No recovery incident requires analysis."
                    ),
                    Instant.now()
            );
        }

        try {
            Map<String, Object> incident = readIncident(path);
            LinkedHashMap<String, Object> data = new LinkedHashMap<>();
            data.put("available", true);
            data.put("path", path.toString());
            data.put("incident", incident);
            data.put("aiInstruction", Map.of(
                    "objective", "Identify the root cause, implement a minimal permanent fix, run regressions, and deploy only through the supervised transaction pipeline.",
                    "repository", System.getProperty("suite.project.root", ""),
                    "runtime", System.getProperty("suite.working.directory", ""),
                    "command", "incident current"
            ));
            return CommandExecutionResult.ok("current incident loaded", data);
        } catch (Exception ex) {
            return CommandExecutionResult.failed(
                    "incident_read_failed",
                    "Could not read current incident: " + safeMessage(ex)
            );
        }
    }

    private CommandExecutionResult list(int limit) {
        Path root = incidentsRoot();
        if (!Files.isDirectory(root)) {
            return CommandExecutionResult.ok("no incidents", Map.of(
                    "count", 0,
                    "incidents", List.of(),
                    "root", root.toString()
            ));
        }

        try (var paths = Files.list(root)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equalsIgnoreCase("current.json"))
                    .sorted(Comparator.comparingLong(this::lastModified).reversed())
                    .limit(limit)
                    .toList();

            List<Map<String, Object>> incidents = new ArrayList<>();
            for (Path file : files) {
                try {
                    Map<String, Object> report = readIncident(file);
                    LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
                    summary.put("incidentId", report.getOrDefault("incidentId", stripExtension(file.getFileName().toString())));
                    summary.put("occurredAt", report.getOrDefault("occurredAt", ""));
                    summary.put("severity", report.getOrDefault("severity", ""));
                    summary.put("phase", report.getOrDefault("phase", ""));
                    summary.put("message", report.getOrDefault("message", ""));
                    summary.put("deploymentId", report.getOrDefault("deploymentId", ""));
                    summary.put("recoveryAction", report.getOrDefault("recoveryAction", ""));
                    summary.put("path", file.toString());
                    incidents.add(Map.copyOf(summary));
                } catch (Exception ignored) {
                    incidents.add(Map.of(
                            "incidentId", stripExtension(file.getFileName().toString()),
                            "path", file.toString(),
                            "readable", false
                    ));
                }
            }

            return CommandExecutionResult.ok("incident list loaded", Map.of(
                    "count", incidents.size(),
                    "limit", limit,
                    "incidents", List.copyOf(incidents),
                    "root", root.toString()
            ));
        } catch (Exception ex) {
            return CommandExecutionResult.failed(
                    "incident_list_failed",
                    "Could not list incidents: " + safeMessage(ex)
            );
        }
    }

    private Map<String, Object> readIncident(Path path) throws Exception {
        return objectMapper.readValue(path.toFile(), MAP_TYPE);
    }

    private Path incidentsRoot() {
        String workingDirectory = System.getProperty("suite.working.directory", System.getProperty("user.dir", "."));
        return Path.of(workingDirectory).toAbsolutePath().normalize().resolve(".springsuite").resolve("incidents");
    }

    private int parseLimit(String raw) {
        try {
            return Math.max(1, Math.min(MAX_LIST_LIMIT, Integer.parseInt(raw.trim())));
        } catch (Exception ignored) {
            return DEFAULT_LIST_LIMIT;
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String stripExtension(String value) {
        int index = value.lastIndexOf('.');
        return index <= 0 ? value : value.substring(0, index);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
