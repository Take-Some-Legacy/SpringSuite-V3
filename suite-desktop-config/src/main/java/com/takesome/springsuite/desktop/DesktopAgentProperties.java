package com.takesome.springsuite.desktop;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.desktop-helper.agent")
public class DesktopAgentProperties {
    private boolean enabled = true;
    private boolean trayEnabled = true;
    private boolean overlayEnabled = true;
    private Duration pollInterval = Duration.ofMillis(900);
    private Duration stableFor = Duration.ofMillis(700);
    private Duration repeatAfter = Duration.ofSeconds(30);
    private int minimumFieldCount = 1;
    private int maximumActionCount = 0;
    private String locale = "ru-RU";
    private Map<String, Object> autofillProfile = new LinkedHashMap<>();
    private Map<String, Object> constraints = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrayEnabled() {
        return trayEnabled;
    }

    public void setTrayEnabled(boolean trayEnabled) {
        this.trayEnabled = trayEnabled;
    }

    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    public void setOverlayEnabled(boolean overlayEnabled) {
        this.overlayEnabled = overlayEnabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = positive(pollInterval, Duration.ofMillis(900));
    }

    public Duration getStableFor() {
        return stableFor;
    }

    public void setStableFor(Duration stableFor) {
        this.stableFor = positive(stableFor, Duration.ofMillis(700));
    }

    public Duration getRepeatAfter() {
        return repeatAfter;
    }

    public void setRepeatAfter(Duration repeatAfter) {
        this.repeatAfter = positive(repeatAfter, Duration.ofSeconds(30));
    }

    public int getMinimumFieldCount() {
        return minimumFieldCount;
    }

    public void setMinimumFieldCount(int minimumFieldCount) {
        this.minimumFieldCount = Math.max(1, minimumFieldCount);
    }

    public int getMaximumActionCount() {
        return maximumActionCount;
    }

    public void setMaximumActionCount(int maximumActionCount) {
        this.maximumActionCount = Math.max(0, maximumActionCount);
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale == null || locale.isBlank() ? "ru-RU" : locale.trim();
    }

    public Map<String, Object> getAutofillProfile() {
        return autofillProfile;
    }

    public void setAutofillProfile(Map<String, Object> autofillProfile) {
        this.autofillProfile = autofillProfile == null ? new LinkedHashMap<>() : new LinkedHashMap<>(autofillProfile);
    }

    public Map<String, Object> getConstraints() {
        return constraints;
    }

    public void setConstraints(Map<String, Object> constraints) {
        this.constraints = constraints == null ? new LinkedHashMap<>() : new LinkedHashMap<>(constraints);
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
