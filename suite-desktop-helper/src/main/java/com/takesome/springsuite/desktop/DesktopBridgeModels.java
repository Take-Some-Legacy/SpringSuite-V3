package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopHelperModels.DesktopFocusContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class DesktopBridgeModels {
    private DesktopBridgeModels() {
    }

    public record DesktopCaptureRequest(
            String source,
            List<String> args,
            String cwd,
            Integer timeoutSec,
            Integer maxStdoutBytes,
            Boolean dryRun,
            Boolean store,
            Map<String, Object> metadata
    ) {
        public DesktopCaptureRequest {
            source = textOr(source, "suite-desktop-capture");
            args = args == null || args.isEmpty() ? List.of("capture") : List.copyOf(args);
            cwd = text(cwd);
            timeoutSec = timeoutSec == null || timeoutSec < 1 ? 15 : Math.min(timeoutSec, 120);
            maxStdoutBytes = maxStdoutBytes == null || maxStdoutBytes < 1 ? 256_000 : Math.min(maxStdoutBytes, 2_000_000);
            dryRun = dryRun == null ? Boolean.FALSE : dryRun;
            store = store == null ? Boolean.TRUE : store;
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static DesktopCaptureRequest defaults() {
            return new DesktopCaptureRequest("suite-desktop-capture", List.of("capture"), "", 15, 256_000, false, true, Map.of());
        }
    }

    public record NormalizedDesktopSnapshot(
            String source,
            Instant capturedAt,
            DesktopFocusContext context,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public NormalizedDesktopSnapshot {
            source = textOr(source, "external");
            capturedAt = capturedAt == null ? Instant.now() : capturedAt;
            context = context == null ? DesktopFocusContext.empty() : context;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record DesktopSnapshot(
            String snapshotId,
            String source,
            Instant capturedAt,
            Instant ingestedAt,
            Instant expiresAt,
            boolean stale,
            DesktopFocusContext context,
            Map<String, Object> metadata
    ) {
        public DesktopSnapshot {
            snapshotId = text(snapshotId);
            source = textOr(source, "external");
            capturedAt = capturedAt == null ? Instant.now() : capturedAt;
            ingestedAt = ingestedAt == null ? Instant.now() : ingestedAt;
            expiresAt = expiresAt == null ? ingestedAt : expiresAt;
            context = context == null ? DesktopFocusContext.empty() : context;
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public DesktopSnapshot withStale(boolean stale) {
            return new DesktopSnapshot(snapshotId, source, capturedAt, ingestedAt, expiresAt, stale, context, metadata);
        }
    }

    public record DesktopSnapshotResult(
            boolean ok,
            String code,
            String message,
            DesktopSnapshot snapshot,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public DesktopSnapshotResult {
            code = textOr(code, ok ? "ok" : "failed");
            message = text(message);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static DesktopSnapshotResult ok(String message, DesktopSnapshot snapshot, List<String> warnings, Map<String, Object> metadata) {
            return new DesktopSnapshotResult(true, "ok", message, snapshot, warnings, metadata);
        }

        public static DesktopSnapshotResult failed(String code, String message, List<String> warnings, Map<String, Object> metadata) {
            return new DesktopSnapshotResult(false, code, message, null, warnings, metadata);
        }
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String textOr(String value, String fallback) {
        String normalized = text(value);
        return normalized.isBlank() ? fallback : normalized;
    }
}
