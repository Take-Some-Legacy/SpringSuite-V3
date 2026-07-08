package com.takesome.springsuite.desktop;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.desktop-helper")
public class DesktopHelperProperties {
    private boolean enabled = true;
    private String mode = "assistive";
    private boolean aiEnrichmentEnabled = true;
    private boolean requireApprovalForWriteActions = true;
    private boolean allowDesktopCapture = true;
    private boolean allowClipboardRead = false;
    private boolean allowClipboardWrite = false;
    private boolean allowFormFillPlanning = true;
    private boolean allowAutofillExecution = false;
    private boolean allowSubmitActions = false;
    private String captureToolId = "suite-desktop-capture";
    private Duration contextTtl = Duration.ofSeconds(30);
    private int approvalTokenTtlSeconds = 120;
    private int maxApprovalTokenTtlSeconds = 900;
    private int maxScreenTextChars = 12_000;
    private int maxSuggestionCount = 6;
    private ExecutorPolicy executor = new ExecutorPolicy();
    private Map<String, ExecutorOverride> executors = defaultExecutorOverrides();
    private List<String> sensitiveFieldHints = List.of(
            "password",
            "passcode",
            "token",
            "secret",
            "api key",
            "card",
            "cvv",
            "iban",
            "bank",
            "ssn",
            "social security",
            "passport"
    );
    private Map<String, Surface> surfaces = defaultSurfaces();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = valueOr(mode, "assistive");
    }

    public boolean isAiEnrichmentEnabled() {
        return aiEnrichmentEnabled;
    }

    public void setAiEnrichmentEnabled(boolean aiEnrichmentEnabled) {
        this.aiEnrichmentEnabled = aiEnrichmentEnabled;
    }

    public boolean isRequireApprovalForWriteActions() {
        return requireApprovalForWriteActions;
    }

    public void setRequireApprovalForWriteActions(boolean requireApprovalForWriteActions) {
        this.requireApprovalForWriteActions = requireApprovalForWriteActions;
    }

    public boolean isAllowDesktopCapture() {
        return allowDesktopCapture;
    }

    public void setAllowDesktopCapture(boolean allowDesktopCapture) {
        this.allowDesktopCapture = allowDesktopCapture;
    }

    public boolean isAllowClipboardRead() {
        return allowClipboardRead;
    }

    public void setAllowClipboardRead(boolean allowClipboardRead) {
        this.allowClipboardRead = allowClipboardRead;
    }

    public boolean isAllowClipboardWrite() {
        return allowClipboardWrite;
    }

    public void setAllowClipboardWrite(boolean allowClipboardWrite) {
        this.allowClipboardWrite = allowClipboardWrite;
    }

    public boolean isAllowFormFillPlanning() {
        return allowFormFillPlanning;
    }

    public void setAllowFormFillPlanning(boolean allowFormFillPlanning) {
        this.allowFormFillPlanning = allowFormFillPlanning;
    }

    public boolean isAllowAutofillExecution() {
        return allowAutofillExecution;
    }

    public void setAllowAutofillExecution(boolean allowAutofillExecution) {
        this.allowAutofillExecution = allowAutofillExecution;
    }

    public boolean isAllowSubmitActions() {
        return allowSubmitActions;
    }

    public void setAllowSubmitActions(boolean allowSubmitActions) {
        this.allowSubmitActions = allowSubmitActions;
    }

    public String getCaptureToolId() {
        return captureToolId;
    }

    public void setCaptureToolId(String captureToolId) {
        this.captureToolId = valueOr(captureToolId, "suite-desktop-capture");
    }

    public Duration getContextTtl() {
        return contextTtl;
    }

    public void setContextTtl(Duration contextTtl) {
        this.contextTtl = contextTtl == null ? Duration.ofSeconds(30) : contextTtl;
    }

    public int getApprovalTokenTtlSeconds() {
        return approvalTokenTtlSeconds;
    }

    public void setApprovalTokenTtlSeconds(int approvalTokenTtlSeconds) {
        this.approvalTokenTtlSeconds = clamp(approvalTokenTtlSeconds, 15, 3600, 120);
    }

    public int getMaxApprovalTokenTtlSeconds() {
        return maxApprovalTokenTtlSeconds;
    }

    public void setMaxApprovalTokenTtlSeconds(int maxApprovalTokenTtlSeconds) {
        this.maxApprovalTokenTtlSeconds = clamp(maxApprovalTokenTtlSeconds, 15, 7200, 900);
    }

    public int getMaxScreenTextChars() {
        return maxScreenTextChars;
    }

    public void setMaxScreenTextChars(int maxScreenTextChars) {
        this.maxScreenTextChars = clamp(maxScreenTextChars, 1_000, 50_000, 12_000);
    }

    public int getMaxSuggestionCount() {
        return maxSuggestionCount;
    }

    public void setMaxSuggestionCount(int maxSuggestionCount) {
        this.maxSuggestionCount = clamp(maxSuggestionCount, 1, 20, 6);
    }

    public ExecutorPolicy getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorPolicy executor) {
        this.executor = executor == null ? new ExecutorPolicy() : executor;
    }

    public Map<String, ExecutorOverride> getExecutors() {
        return executors;
    }

    public void setExecutors(Map<String, ExecutorOverride> executors) {
        LinkedHashMap<String, ExecutorOverride> merged = new LinkedHashMap<>(defaultExecutorOverrides());
        if (executors != null) {
            merged.putAll(executors);
        }
        this.executors = merged;
    }

    public List<String> getSensitiveFieldHints() {
        return sensitiveFieldHints;
    }

    public void setSensitiveFieldHints(List<String> sensitiveFieldHints) {
        this.sensitiveFieldHints = sensitiveFieldHints == null ? List.of() : List.copyOf(sensitiveFieldHints);
    }

    public Map<String, Surface> getSurfaces() {
        return surfaces;
    }

    public void setSurfaces(Map<String, Surface> surfaces) {
        this.surfaces = surfaces == null ? defaultSurfaces() : new LinkedHashMap<>(surfaces);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Map<String, Surface> defaultSurfaces() {
        LinkedHashMap<String, Surface> defaults = new LinkedHashMap<>();
        defaults.put("active-window", new Surface(true, "read", List.of("process", "title", "url", "focused-control"), "suite-desktop-capture"));
        defaults.put("screen-text", new Surface(true, "read", List.of("ocr", "visible-text", "selected-text"), "suite-desktop-capture"));
        defaults.put("clipboard", new Surface(false, "read-write", List.of("clipboard-preview", "copy-suggestion"), "platform"));
        defaults.put("browser-form", new Surface(true, "read-plan", List.of("field-detection", "fill-plan", "validation-hints"), "extension-or-accessibility"));
        defaults.put("keyboard-mouse", new Surface(false, "write", List.of("type", "hotkey", "click"), "accessibility-driver"));
        return defaults;
    }

    private static Map<String, ExecutorOverride> defaultExecutorOverrides() {
        LinkedHashMap<String, ExecutorOverride> defaults = new LinkedHashMap<>();
        defaults.put("noop-desktop-action-executor", new ExecutorOverride(true));
        defaults.put("clipboard-desktop-action-executor", new ExecutorOverride(false));
        defaults.put("keyboard-desktop-action-executor", new ExecutorOverride(false));
        defaults.put("mouse-desktop-action-executor", new ExecutorOverride(false));
        defaults.put("browser-dom-desktop-action-executor", new ExecutorOverride(false));
        defaults.put("windows-ui-automation-desktop-action-executor", new ExecutorOverride(false));
        return defaults;
    }

    public static final class ExecutorPolicy {
        private String defaultId = "noop-desktop-action-executor";
        private boolean allowedRealInput = false;

        public String getDefaultId() {
            return defaultId;
        }

        public void setDefaultId(String defaultId) {
            this.defaultId = valueOr(defaultId, "noop-desktop-action-executor");
        }

        public boolean isAllowedRealInput() {
            return allowedRealInput;
        }

        public void setAllowedRealInput(boolean allowedRealInput) {
            this.allowedRealInput = allowedRealInput;
        }
    }

    public static final class ExecutorOverride {
        private Boolean enabled;

        public ExecutorOverride() {
        }

        public ExecutorOverride(Boolean enabled) {
            this.enabled = enabled;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static final class Surface {
        private boolean enabled = true;
        private String access = "read";
        private List<String> capabilities = List.of();
        private String adapter = "";

        public Surface() {
        }

        public Surface(boolean enabled, String access, List<String> capabilities, String adapter) {
            this.enabled = enabled;
            this.access = valueOr(access, "read");
            this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            this.adapter = adapter == null ? "" : adapter.trim();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAccess() {
            return access;
        }

        public void setAccess(String access) {
            this.access = valueOr(access, "read");
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }

        public String getAdapter() {
            return adapter;
        }

        public void setAdapter(String adapter) {
            this.adapter = adapter == null ? "" : adapter.trim();
        }
    }
}
