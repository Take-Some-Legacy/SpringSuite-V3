package com.takesome.springsuite.desktop;

import java.util.List;
import java.util.Map;

public final class RealInputSelfTestModels {
    private RealInputSelfTestModels() {
    }

    public record RealInputSelfTestRequest(
            boolean perform,
            boolean testClipboardPaste,
            boolean testTyping,
            boolean testClick,
            String testText,
            Integer timeoutMs,
            Map<String, Object> metadata
    ) {
        public RealInputSelfTestRequest {
            testText = textOr(testText, "SpringSuite real input self-test");
            timeoutMs = timeoutMs == null || timeoutMs <= 0 ? 5000 : Math.min(timeoutMs, 30000);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static RealInputSelfTestRequest diagnosticsOnly() {
            return new RealInputSelfTestRequest(false, true, true, true, "SpringSuite real input self-test", 5000, Map.of());
        }
    }

    public record RealInputSelfTestResult(
            boolean ok,
            String summary,
            boolean perform,
            List<RealInputSelfTestCheck> checks,
            List<String> warnings,
            Map<String, Object> policy,
            Map<String, Object> metadata
    ) {
        public RealInputSelfTestResult {
            summary = text(summary);
            checks = checks == null ? List.of() : List.copyOf(checks);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            policy = DesktopHelperModels.safeMap(policy);
            metadata = DesktopHelperModels.safeMap(metadata);
        }
    }

    public record RealInputSelfTestCheck(
            String id,
            String title,
            boolean ok,
            String status,
            String message,
            Map<String, Object> metadata
    ) {
        public RealInputSelfTestCheck {
            id = text(id);
            title = text(title);
            status = textOr(status, ok ? "ok" : "failed");
            message = text(message);
            metadata = DesktopHelperModels.safeMap(metadata);
        }

        public static RealInputSelfTestCheck ok(String id, String title, String message, Map<String, Object> metadata) {
            return new RealInputSelfTestCheck(id, title, true, "ok", message, metadata);
        }

        public static RealInputSelfTestCheck warn(String id, String title, String message, Map<String, Object> metadata) {
            return new RealInputSelfTestCheck(id, title, false, "warning", message, metadata);
        }

        public static RealInputSelfTestCheck failed(String id, String title, String message, Map<String, Object> metadata) {
            return new RealInputSelfTestCheck(id, title, false, "failed", message, metadata);
        }
    }

    static String text(String value) {
        return value == null ? "" : value.trim();
    }

    static String textOr(String value, String fallback) {
        String normalized = text(value);
        return normalized.isBlank() ? text(fallback) : normalized;
    }
}
