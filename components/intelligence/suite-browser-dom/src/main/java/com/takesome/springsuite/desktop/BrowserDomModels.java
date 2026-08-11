package com.takesome.springsuite.desktop;

import com.takesome.springsuite.desktop.DesktopBridgeModels.DesktopSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BrowserDomModels {
    private BrowserDomModels() {
    }

    public record BrowserDomSnapshotRequest(
            String schema,
            String pageId,
            String capturedAt,
            String url,
            String title,
            String language,
            String browser,
            String activeElementSelector,
            List<BrowserDomForm> forms,
            Map<String, Object> metadata
    ) {
        public BrowserDomSnapshotRequest {
            schema = textOr(schema, "spring-suite.browser_dom_snapshot.v1");
            pageId = text(pageId);
            capturedAt = text(capturedAt);
            url = text(url);
            title = text(title);
            language = text(language);
            browser = textOr(browser, "browser-extension");
            activeElementSelector = text(activeElementSelector);
            forms = forms == null ? List.of() : forms.stream().filter(value -> value != null).toList();
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record BrowserDomForm(
            String id,
            String name,
            String action,
            String method,
            boolean active,
            List<BrowserDomField> fields,
            List<BrowserDomSubmitControl> submitControls,
            Map<String, Object> metadata
    ) {
        public BrowserDomForm {
            id = text(id);
            name = text(name);
            action = text(action);
            method = textOr(method, "get");
            fields = fields == null ? List.of() : fields.stream().filter(value -> value != null).toList();
            submitControls = submitControls == null ? List.of() : submitControls.stream().filter(value -> value != null).toList();
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record BrowserDomField(
            String id,
            String label,
            String name,
            String type,
            String role,
            String placeholder,
            boolean required,
            boolean focused,
            boolean sensitive,
            boolean readOnly,
            boolean disabled,
            boolean visible,
            boolean valuePresent,
            List<String> options,
            Map<String, Object> metadata
    ) {
        public BrowserDomField {
            id = text(id);
            label = text(label);
            name = text(name);
            type = textOr(type, "text");
            role = text(role);
            placeholder = text(placeholder);
            options = options == null ? List.of() : options.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record BrowserDomSubmitControl(
            String id,
            String label,
            String type,
            boolean disabled,
            Map<String, Object> metadata
    ) {
        public BrowserDomSubmitControl {
            id = text(id);
            label = text(label);
            type = textOr(type, "submit");
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record BrowserDomStatus(
            boolean enabled,
            boolean tokenRequired,
            Instant lastReceivedAt,
            long receivedSnapshots,
            long acceptedSnapshots,
            long rejectedSnapshots,
            String lastUrl,
            String lastOrigin,
            String lastCode,
            String lastMessage,
            int lastFormCount,
            int lastFieldCount,
            Map<String, Object> metadata
    ) {
        public BrowserDomStatus {
            lastUrl = text(lastUrl);
            lastOrigin = text(lastOrigin);
            lastCode = textOr(lastCode, "idle");
            lastMessage = text(lastMessage);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record BrowserDomIngestResult(
            boolean ok,
            String code,
            String message,
            DesktopSnapshot snapshot,
            BrowserDomStatus status,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public BrowserDomIngestResult {
            code = textOr(code, ok ? "ok" : "failed");
            message = text(message);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static BrowserDomIngestResult failed(String code, String message, BrowserDomStatus status, List<String> warnings, Map<String, Object> metadata) {
            return new BrowserDomIngestResult(false, code, message, null, status, warnings, metadata);
        }
    }


    public record BrowserDomFillField(
            String fieldId,
            String label,
            String selector,
            String action,
            String value,
            String type,
            Map<String, Object> metadata
    ) {
        public BrowserDomFillField {
            fieldId = text(fieldId);
            label = text(label);
            selector = text(selector);
            action = textOr(action, "fill").toLowerCase();
            value = text(value);
            type = textOr(type, "text").toLowerCase();
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record BrowserDomFillCommand(
            String commandId,
            String pageId,
            String pageUrl,
            String snapshotId,
            Instant createdAt,
            Instant expiresAt,
            boolean preserveExistingValues,
            boolean allowSubmit,
            List<BrowserDomFillField> fields,
            Map<String, Object> metadata
    ) {
        public BrowserDomFillCommand {
            commandId = text(commandId);
            pageId = text(pageId);
            pageUrl = text(pageUrl);
            snapshotId = text(snapshotId);
            createdAt = createdAt == null ? Instant.now() : createdAt;
            expiresAt = expiresAt == null ? createdAt : expiresAt;
            fields = fields == null ? List.of() : fields.stream().filter(value -> value != null).toList();
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    public record BrowserDomCommandAckRequest(
            String pageId,
            String pageUrl,
            boolean ok,
            int filledCount,
            int skippedCount,
            int failedCount,
            List<String> warnings,
            Map<String, Object> metadata
    ) {
        public BrowserDomCommandAckRequest {
            pageId = text(pageId);
            pageUrl = text(pageUrl);
            filledCount = Math.max(0, filledCount);
            skippedCount = Math.max(0, skippedCount);
            failedCount = Math.max(0, failedCount);
            warnings = warnings == null ? List.of() : warnings.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record BrowserDomCommandAckResult(
            boolean ok,
            String code,
            String message,
            String commandId,
            int filledCount,
            int skippedCount,
            int failedCount,
            Map<String, Object> metadata
    ) {
        public BrowserDomCommandAckResult {
            code = textOr(code, ok ? "ok" : "failed");
            message = text(message);
            commandId = text(commandId);
            filledCount = Math.max(0, filledCount);
            skippedCount = Math.max(0, skippedCount);
            failedCount = Math.max(0, failedCount);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    private static String text(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        return normalized.length() <= 2_048 ? normalized : normalized.substring(0, 2_048);
    }

    private static String textOr(String value, String fallback) {
        String normalized = text(value);
        return normalized.isBlank() ? fallback : normalized;
    }
}
