package com.takesome.springsuite.desktop;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.desktop-helper.agent.sidecar")
public class DesktopAgentSidecarProperties {
    private boolean enabled = true;
    private boolean autoStart = true;
    private boolean autoBuild = false;
    private String executable = "";
    private String projectRoot = "../suite-desktop-agent-go";
    private String host = "127.0.0.1";
    private int port;
    private Duration startupTimeout = Duration.ofSeconds(12);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private Duration shutdownTimeout = Duration.ofSeconds(3);

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

    public boolean isAutoBuild() {
        return autoBuild;
    }

    public void setAutoBuild(boolean autoBuild) {
        this.autoBuild = autoBuild;
    }

    public String getExecutable() {
        return executable;
    }

    public void setExecutable(String executable) {
        this.executable = text(executable);
    }

    public String getProjectRoot() {
        return projectRoot;
    }

    public void setProjectRoot(String projectRoot) {
        this.projectRoot = textOr(projectRoot, "../suite-desktop-agent-go");
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = textOr(host, "127.0.0.1");
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = Math.max(0, Math.min(65535, port));
    }

    public Duration getStartupTimeout() {
        return startupTimeout;
    }

    public void setStartupTimeout(Duration startupTimeout) {
        this.startupTimeout = positive(startupTimeout, Duration.ofSeconds(12));
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = positive(requestTimeout, Duration.ofSeconds(10));
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = positive(shutdownTimeout, Duration.ofSeconds(3));
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String textOr(String value, String fallback) {
        String normalized = text(value);
        return normalized.isBlank() ? fallback : normalized;
    }
}
