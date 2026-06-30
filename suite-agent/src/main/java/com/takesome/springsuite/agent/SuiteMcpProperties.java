package com.takesome.springsuite.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.mcp")
public class SuiteMcpProperties {
    private boolean enabled = true;
    private String endpoint = "/mcp";
    private String protocolVersion = "2025-03-26";
    private String serverName = "spring-suite";
    private String serverTitle = "SpringSuite Agent Bridge";
    private String description = "SpringSuite / NOESIS local software-authoring bridge.";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = normalizePath(endpoint, "/mcp");
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? "2025-03-26" : protocolVersion;
    }

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName == null || serverName.isBlank() ? "spring-suite" : serverName;
    }

    public String getServerTitle() {
        return serverTitle;
    }

    public void setServerTitle(String serverTitle) {
        this.serverTitle = serverTitle == null || serverTitle.isBlank() ? "SpringSuite Agent Bridge" : serverTitle;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? "" : description;
    }

    private String normalizePath(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }
}
