package com.takesome.springsuite.cloudflared;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.cloudflared")
public class CloudflaredProperties {
    private boolean enabled = false;
    private boolean autoStart = false;
    private String executable = "";
    private String targetUrl = "";
    private String tunnelName = "";
    private String hostname = "";
    private List<String> extraArgs = new ArrayList<>();
    private Duration stopTimeout = Duration.ofSeconds(5);
    private int recentLogLimit = 300;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = executable;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public String getTunnelName() {
        return tunnelName;
    }

    public void setTunnelName(String tunnelName) {
        this.tunnelName = tunnelName == null ? "" : tunnelName.trim();
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname == null ? "" : hostname.trim();
    }

    public List<String> getExtraArgs() {
        return extraArgs;
    }

    public void setExtraArgs(List<String> extraArgs) {
        this.extraArgs = extraArgs == null ? new ArrayList<>() : new ArrayList<>(extraArgs);
    }

    public Duration getStopTimeout() {
        return stopTimeout;
    }

    public void setStopTimeout(Duration stopTimeout) {
        this.stopTimeout = stopTimeout == null ? Duration.ofSeconds(5) : stopTimeout;
    }

    public int getRecentLogLimit() {
        return recentLogLimit;
    }

    public void setRecentLogLimit(int recentLogLimit) {
        this.recentLogLimit = Math.max(50, recentLogLimit);
    }
}
