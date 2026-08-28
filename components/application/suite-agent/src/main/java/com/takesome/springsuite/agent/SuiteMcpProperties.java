package com.takesome.springsuite.agent;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.mcp")
public class SuiteMcpProperties {
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-03-26";
    public static final String LEGACY_PROTOCOL_VERSION = "2024-11-05";

    private boolean enabled = true;
    private String endpoint = "/mcp";
    private String protocolVersion = DEFAULT_PROTOCOL_VERSION;
    private List<String> supportedProtocolVersions = List.of(DEFAULT_PROTOCOL_VERSION, LEGACY_PROTOCOL_VERSION);
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
        this.protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? DEFAULT_PROTOCOL_VERSION : protocolVersion.trim();
    }

    public List<String> getSupportedProtocolVersions() {
        LinkedHashSet<String> versions = new LinkedHashSet<>();
        versions.add(protocolVersion);
        versions.addAll(supportedProtocolVersions == null ? List.of() : supportedProtocolVersions);
        versions.add(LEGACY_PROTOCOL_VERSION);
        return List.copyOf(versions);
    }

    public void setSupportedProtocolVersions(List<String> supportedProtocolVersions) {
        this.supportedProtocolVersions = supportedProtocolVersions == null
                ? List.of(DEFAULT_PROTOCOL_VERSION, LEGACY_PROTOCOL_VERSION)
                : supportedProtocolVersions.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .toList();
    }

    public boolean supportsProtocolVersion(String version) {
        return version != null && getSupportedProtocolVersions().contains(version.trim());
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
