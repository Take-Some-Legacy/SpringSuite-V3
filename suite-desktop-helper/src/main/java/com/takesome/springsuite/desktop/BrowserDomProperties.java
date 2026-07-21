package com.takesome.springsuite.desktop;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.desktop-helper.browser-dom")
public class BrowserDomProperties {
    public static final String SNAPSHOT_ENDPOINT = "/api/desktop-helper/browser-dom/snapshot";
    public static final String STATUS_ENDPOINT = "/api/desktop-helper/browser-dom/status";
    public static final String COMMAND_NEXT_ENDPOINT = "/api/desktop-helper/browser-dom/commands/next";
    public static final String COMMAND_ACK_ENDPOINT = "/api/desktop-helper/browser-dom/commands/{commandId}/ack";

    private boolean enabled = true;
    private boolean requireToken = true;
    private boolean writeEnabled = true;
    private boolean preserveExistingValues = true;
    private String token = "";
    private Duration maxSnapshotAge = Duration.ofSeconds(20);
    private Duration maxFutureSkew = Duration.ofSeconds(30);
    private Duration commandTtl = Duration.ofSeconds(20);
    private int maxForms = 64;
    private int maxFieldsPerForm = 256;
    private int maxOptionsPerField = 100;
    private List<String> allowedSchemes = List.of("http", "https");

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRequireToken() {
        return requireToken;
    }

    public void setRequireToken(boolean requireToken) {
        this.requireToken = requireToken;
    }

    public boolean isWriteEnabled() {
        return writeEnabled;
    }

    public void setWriteEnabled(boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public boolean isPreserveExistingValues() {
        return preserveExistingValues;
    }

    public void setPreserveExistingValues(boolean preserveExistingValues) {
        this.preserveExistingValues = preserveExistingValues;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public boolean tokenRequired() {
        return requireToken || !token.isBlank();
    }

    public Duration getMaxSnapshotAge() {
        return maxSnapshotAge;
    }

    public void setMaxSnapshotAge(Duration maxSnapshotAge) {
        this.maxSnapshotAge = safeDuration(maxSnapshotAge, Duration.ofSeconds(20));
    }

    public Duration getMaxFutureSkew() {
        return maxFutureSkew;
    }

    public void setMaxFutureSkew(Duration maxFutureSkew) {
        this.maxFutureSkew = safeDuration(maxFutureSkew, Duration.ofSeconds(30));
    }

    public Duration getCommandTtl() {
        return commandTtl;
    }

    public void setCommandTtl(Duration commandTtl) {
        this.commandTtl = safeDuration(commandTtl, Duration.ofSeconds(20));
    }

    public int getMaxForms() {
        return maxForms;
    }

    public void setMaxForms(int maxForms) {
        this.maxForms = clamp(maxForms, 1, 256, 64);
    }

    public int getMaxFieldsPerForm() {
        return maxFieldsPerForm;
    }

    public void setMaxFieldsPerForm(int maxFieldsPerForm) {
        this.maxFieldsPerForm = clamp(maxFieldsPerForm, 1, 2_000, 256);
    }

    public int getMaxOptionsPerField() {
        return maxOptionsPerField;
    }

    public void setMaxOptionsPerField(int maxOptionsPerField) {
        this.maxOptionsPerField = clamp(maxOptionsPerField, 1, 1_000, 100);
    }

    public List<String> getAllowedSchemes() {
        return allowedSchemes;
    }

    public void setAllowedSchemes(List<String> allowedSchemes) {
        if (allowedSchemes == null || allowedSchemes.isEmpty()) {
            this.allowedSchemes = List.of("http", "https");
            return;
        }
        this.allowedSchemes = allowedSchemes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    public String getEndpointPath() {
        return SNAPSHOT_ENDPOINT;
    }

    private static Duration safeDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
