package com.takesome.springsuite.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopCaptureRequest;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshotResult;
import com.takesome.springsuite.desktop.DesktopBridgeModels.NormalizedDesktopSnapshot;
import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.observability.SuiteTelemetry;
import com.takesome.springsuite.toolbelt.ToolRunResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DesktopBridgeService implements DesktopSnapshotIngestor {
    private static final String SOURCE = "desktop-bridge";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DesktopHelperProperties properties;
    private final DesktopCaptureAdapter captureAdapter;
    private final DesktopContextNormalizer normalizer;
    private final DesktopSnapshotCache cache;
    private final ObjectMapper objectMapper;
    private final OperatorLogService logService;
    private final SuiteTelemetry telemetry;

    public DesktopBridgeService(
            DesktopHelperProperties properties,
            DesktopCaptureAdapter captureAdapter,
            DesktopContextNormalizer normalizer,
            DesktopSnapshotCache cache,
            ObjectMapper objectMapper,
            OperatorLogService logService,
            SuiteTelemetry telemetry
    ) {
        this.properties = properties;
        this.captureAdapter = captureAdapter;
        this.normalizer = normalizer;
        this.cache = cache;
        this.objectMapper = objectMapper;
        this.logService = logService;
        this.telemetry = telemetry;
    }

    public DesktopSnapshotResult capture(DesktopCaptureRequest request) {
        DesktopCaptureRequest safeRequest = request == null ? DesktopCaptureRequest.defaults() : request;
        if (!properties.isEnabled()) {
            return DesktopSnapshotResult.failed("desktop_helper_disabled", "Desktop helper is disabled.", List.of(), Map.of());
        }
        if (!properties.isAllowDesktopCapture()) {
            return DesktopSnapshotResult.failed("desktop_capture_disabled", "Desktop capture is disabled by policy.", List.of("suite.desktop-helper.allow-desktop-capture=false"), Map.of());
        }

        ToolRunResult run = captureAdapter.capture(safeRequest);
        ArrayList<String> warnings = new ArrayList<>();
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        String correlationId = telemetry.newCorrelationId();
        metadata.put(SuiteTelemetry.CORRELATION_ID, correlationId);
        metadata.put("toolId", run.toolId());
        metadata.put("toolOk", run.ok());
        metadata.put("exitCode", run.exitCode());
        metadata.put("durationMs", run.durationMs());
        metadata.put("dryRun", run.dryRun());
        metadata.put("commandPreview", run.commandPreview());
        metadata.put("stderrPreview", truncate(run.stderr(), 2_000));
        metadata.put("toolMessage", run.message());

        if (!run.ok()) {
            warnings.add("Desktop capture tool failed: " + fallback(run.message(), "non-zero exit"));
            logService.append(OperatorLogLevel.WARN, SOURCE, "desktop capture failed", metadata);
            return DesktopSnapshotResult.failed("desktop_capture_failed", "Desktop capture tool failed.", warnings, metadata);
        }

        Map<String, Object> raw = parseCaptureStdout(run.stdout(), warnings, metadata);
        if (raw.isEmpty()) {
            raw = Map.of(
                    "source", safeRequest.source(),
                    "capturedAt", run.timestamp().toString(),
                    "screenText", Map.of("visibleText", truncate(run.stdout(), properties.getMaxScreenTextChars()))
            );
            warnings.add("Capture stdout was not structured JSON; converted stdout into visible text context.");
        }
        mergeIfAbsent(raw, "source", safeRequest.source());
        mergeIfAbsent(raw, "capturedAt", run.timestamp().toString());

        NormalizedDesktopSnapshot normalized = normalizer.normalize(safeRequest.source(), raw, null);
        warnings.addAll(normalized.warnings());
        DesktopSnapshot snapshot = safeRequest.store()
                ? cache.store(withMetadata(normalized, metadata), properties.getContextTtl())
                : transientSnapshot(withMetadata(normalized, metadata));

        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop snapshot captured", Map.of(
                SuiteTelemetry.CORRELATION_ID, correlationId,
                "snapshotId", snapshot.snapshotId(),
                "source", snapshot.source(),
                "stored", safeRequest.store(),
                "warnings", warnings.size(),
                "fields", snapshot.context().form().fields().size()
        ));
        return DesktopSnapshotResult.ok("Desktop snapshot captured.", snapshot, warnings, metadata);
    }

    @Override
    public DesktopSnapshotResult ingest(Map<String, Object> body) {
        if (!properties.isEnabled()) {
            return DesktopSnapshotResult.failed("desktop_helper_disabled", "Desktop helper is disabled.", List.of(), Map.of());
        }
        if (body == null || body.isEmpty()) {
            return DesktopSnapshotResult.failed("snapshot_body_missing", "No desktop snapshot payload was supplied.", List.of(), Map.of());
        }

        String source = firstText(text(body.get("source")), text(body.get("adapter")), "external");
        boolean store = bool(body.get("store"), true);
        DesktopFocusContext explicitContext = convertExplicitContext(body.get("context"));
        Map<String, Object> raw = snapshotPayload(body);
        if (raw.isEmpty() && explicitContext != null) {
            raw = Map.of("source", source, "capturedAt", Instant.now().toString());
        }

        NormalizedDesktopSnapshot normalized = normalizer.normalize(source, raw, explicitContext);
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        String correlationId = firstText(text(body.get(SuiteTelemetry.CORRELATION_ID)), telemetry.newCorrelationId());
        metadata.put(SuiteTelemetry.CORRELATION_ID, correlationId);
        metadata.put("ingestSource", source);
        metadata.put("stored", store);
        metadata.put("payloadKeys", body.keySet().stream().sorted().toList());
        metadata.putAll(normalized.metadata());

        DesktopSnapshot snapshot = store
                ? cache.store(withMetadata(normalized, metadata), properties.getContextTtl())
                : transientSnapshot(withMetadata(normalized, metadata));

        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop snapshot ingested", Map.of(
                SuiteTelemetry.CORRELATION_ID, correlationId,
                "snapshotId", snapshot.snapshotId(),
                "source", snapshot.source(),
                "stored", store,
                "warnings", normalized.warnings().size(),
                "fields", snapshot.context().form().fields().size()
        ));
        return DesktopSnapshotResult.ok("Desktop snapshot ingested.", snapshot, normalized.warnings(), metadata);
    }

    public DesktopSnapshotResult latest() {
        return cache.latest()
                .map(snapshot -> DesktopSnapshotResult.ok(
                        snapshot.stale() ? "Latest desktop snapshot is stale." : "Latest desktop snapshot is available.",
                        snapshot,
                        snapshot.stale() ? List.of("Snapshot TTL has expired; capture or ingest a fresh context before executing any action.") : List.of(),
                        Map.of("stale", snapshot.stale())
                ))
                .orElseGet(() -> DesktopSnapshotResult.failed("snapshot_missing", "No desktop snapshot has been captured or ingested yet.", List.of(), Map.of()));
    }

    public DesktopSnapshotResult current() {
        return cache.current()
                .map(snapshot -> DesktopSnapshotResult.ok("Current desktop snapshot is available.", snapshot, List.of(), Map.of("stale", false)))
                .orElseGet(() -> cache.latest()
                        .map(snapshot -> DesktopSnapshotResult.failed("snapshot_stale", "Desktop snapshot is stale.", List.of("Capture or ingest a fresh snapshot before using current context."), Map.of(
                                "snapshotId", snapshot.snapshotId(),
                                "expiresAt", snapshot.expiresAt(),
                                "stale", true
                        )))
                        .orElseGet(() -> DesktopSnapshotResult.failed("snapshot_missing", "No current desktop snapshot is available.", List.of(), Map.of())));
    }

    public void clear() {
        cache.clear();
        logService.append(OperatorLogLevel.INFO, SOURCE, "desktop snapshot cache cleared", Map.of());
    }

    private Map<String, Object> parseCaptureStdout(String stdout, List<String> warnings, Map<String, Object> metadata) {
        String trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isBlank()) {
            warnings.add("Capture stdout is empty.");
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(trimmed, MAP_TYPE);
            metadata.put("stdoutJson", true);
            metadata.put("stdoutLength", trimmed.length());
            return new LinkedHashMap<>(parsed);
        } catch (JsonProcessingException ex) {
            metadata.put("stdoutJson", false);
            metadata.put("stdoutLength", trimmed.length());
            warnings.add("Capture stdout is not JSON: " + safeMessage(ex));
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> snapshotPayload(Map<String, Object> body) {
        Object snapshot = body.get("snapshot");
        if (snapshot instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return normalized;
        }
        if (body.containsKey("context") && body.size() <= 4) {
            LinkedHashMap<String, Object> raw = new LinkedHashMap<>();
            raw.put("source", firstText(text(body.get("source")), "external"));
            raw.put("capturedAt", firstText(text(body.get("capturedAt")), Instant.now().toString()));
            return raw;
        }
        return new LinkedHashMap<>(body);
    }

    private DesktopFocusContext convertExplicitContext(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(value, DesktopFocusContext.class);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private NormalizedDesktopSnapshot withMetadata(NormalizedDesktopSnapshot normalized, Map<String, Object> additionalMetadata) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(normalized.metadata());
        if (additionalMetadata != null) {
            merged.putAll(additionalMetadata);
        }
        return new NormalizedDesktopSnapshot(normalized.source(), normalized.capturedAt(), normalized.context(), normalized.warnings(), merged);
    }

    private DesktopSnapshot transientSnapshot(NormalizedDesktopSnapshot normalized) {
        Instant now = Instant.now();
        return new DesktopSnapshot(
                "transient-" + now.toEpochMilli(),
                normalized.source(),
                normalized.capturedAt(),
                now,
                now.plus(properties.getContextTtl()),
                false,
                normalized.context(),
                normalized.metadata()
        );
    }

    private void mergeIfAbsent(Map<String, Object> raw, String key, Object value) {
        if (raw instanceof LinkedHashMap<String, Object> linked && !linked.containsKey(key) && value != null) {
            linked.put(key, value);
        }
    }

    private boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase()) {
                case "true", "1", "yes", "y", "on" -> true;
                case "false", "0", "no", "n", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value).trim();
        }
        return "";
    }

    private String truncate(String value, int limit) {
        if (value == null || limit <= 0 || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, limit)) + "…";
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
