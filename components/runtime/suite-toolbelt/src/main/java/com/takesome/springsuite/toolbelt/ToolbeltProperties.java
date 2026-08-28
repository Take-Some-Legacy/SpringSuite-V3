package com.takesome.springsuite.toolbelt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.toolbelt")
public class ToolbeltProperties {
    private boolean enabled = true;
    /**
     * Canonical descriptor scan roots. Each entry may be absolute or relative to the suite runtime root.
     * SpringSuite must discover its own tools from the runtime root by default.
     * The legacy roots property below is kept as a backward-compatible alias.
     */
    private List<String> scanRoots = new ArrayList<>(List.of("tools"));
    private List<String> roots = new ArrayList<>();
    private boolean includePathTools = true;
    private boolean allowExecution = false;
    private boolean validateBeforePublish = true;
    private Duration validationTimeout = Duration.ofSeconds(2);
    private Duration defaultTimeout = Duration.ZERO;
    private int maxStdoutBytes = 0;
    private int maxStderrBytes = 0;
    private List<String> pathTools = new ArrayList<>(List.of("git", "java", "javac", "python", "py", "cloudflared", "gradle"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getScanRoots() {
        return scanRoots;
    }

    public void setScanRoots(List<String> scanRoots) {
        this.scanRoots = scanRoots == null ? new ArrayList<>() : new ArrayList<>(scanRoots);
    }

    public List<String> getRoots() {
        return roots;
    }

    public void setRoots(List<String> roots) {
        this.roots = roots == null ? new ArrayList<>() : new ArrayList<>(roots);
    }

    public List<String> effectiveScanRoots() {
        return scanRoots == null || scanRoots.isEmpty() ? roots : scanRoots;
    }

    public boolean isIncludePathTools() {
        return includePathTools;
    }

    public void setIncludePathTools(boolean includePathTools) {
        this.includePathTools = includePathTools;
    }

    public boolean isAllowExecution() {
        return allowExecution;
    }

    public void setAllowExecution(boolean allowExecution) {
        this.allowExecution = allowExecution;
    }

    public boolean isValidateBeforePublish() {
        return validateBeforePublish;
    }

    public void setValidateBeforePublish(boolean validateBeforePublish) {
        this.validateBeforePublish = validateBeforePublish;
    }

    public Duration getValidationTimeout() {
        return validationTimeout;
    }

    public void setValidationTimeout(Duration validationTimeout) {
        this.validationTimeout = validationTimeout == null || validationTimeout.isNegative() ? Duration.ofSeconds(2) : validationTimeout;
    }

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout == null || defaultTimeout.isNegative() ? Duration.ZERO : defaultTimeout;
    }

    public int getMaxStdoutBytes() {
        return maxStdoutBytes;
    }

    public void setMaxStdoutBytes(int maxStdoutBytes) {
        this.maxStdoutBytes = Math.max(0, maxStdoutBytes);
    }

    public int getMaxStderrBytes() {
        return maxStderrBytes;
    }

    public void setMaxStderrBytes(int maxStderrBytes) {
        this.maxStderrBytes = Math.max(0, maxStderrBytes);
    }

    public List<String> getPathTools() {
        return pathTools;
    }

    public void setPathTools(List<String> pathTools) {
        this.pathTools = pathTools == null ? new ArrayList<>() : new ArrayList<>(pathTools);
    }
}
