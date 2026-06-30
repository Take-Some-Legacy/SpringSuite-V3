package com.takesome.springsuite.toolbelt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.toolbelt")
public class ToolbeltProperties {
    private boolean enabled = true;
    private List<String> roots = new ArrayList<>(List.of("tools/toolbelt"));
    private boolean includePathTools = true;
    private boolean allowExecution = false;
    private Duration defaultTimeout = Duration.ofSeconds(30);
    private int maxStdoutBytes = 12_000;
    private int maxStderrBytes = 8_000;
    private List<String> pathTools = new ArrayList<>(List.of("git", "java", "javac", "python", "py", "cloudflared", "gradle"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getRoots() {
        return roots;
    }

    public void setRoots(List<String> roots) {
        this.roots = roots == null ? new ArrayList<>() : new ArrayList<>(roots);
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

    public Duration getDefaultTimeout() {
        return defaultTimeout;
    }

    public void setDefaultTimeout(Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout == null ? Duration.ofSeconds(30) : defaultTimeout;
    }

    public int getMaxStdoutBytes() {
        return maxStdoutBytes;
    }

    public void setMaxStdoutBytes(int maxStdoutBytes) {
        this.maxStdoutBytes = Math.max(1024, maxStdoutBytes);
    }

    public int getMaxStderrBytes() {
        return maxStderrBytes;
    }

    public void setMaxStderrBytes(int maxStderrBytes) {
        this.maxStderrBytes = Math.max(1024, maxStderrBytes);
    }

    public List<String> getPathTools() {
        return pathTools;
    }

    public void setPathTools(List<String> pathTools) {
        this.pathTools = pathTools == null ? new ArrayList<>() : new ArrayList<>(pathTools);
    }
}
