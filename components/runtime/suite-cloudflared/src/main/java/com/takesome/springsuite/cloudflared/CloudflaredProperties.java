package com.takesome.springsuite.cloudflared;

import com.takesome.springsuite.core.platform.PlatformExecutables;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.cloudflared")
public class CloudflaredProperties {
    private boolean enabled = true;
    private boolean autoStart = true;
    private String executable = "cloudflared";
    private boolean wrapperEnabled = true;
    private String wrapperExecutable = PlatformExecutables.suiteBinaryPath("suite-cloudflared-wrapper");
    private String targetUrl = "http://localhost:8090";
    private String tunnelName = "spring-suite-test";
    private String hostname = "testspring.kaylas-systems.ru";
    private String cacheDirectory = ".springsuite/cloudflared";
    private String originCertPath = "";
    private String userProfile = "";
    private String configPath = "";
    private String credentialsFile = "";
    private List<String> extraArgs = new ArrayList<>(List.of("--no-autoupdate"));
    private Duration stopTimeout = Duration.ofSeconds(5);
    private int recentLogLimit = 0;

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
        this.executable = executable == null || executable.isBlank() ? "cloudflared" : executable.trim();
    }

    public boolean isWrapperEnabled() {
        return wrapperEnabled;
    }

    public void setWrapperEnabled(boolean wrapperEnabled) {
        this.wrapperEnabled = wrapperEnabled;
    }

    public String getWrapperExecutable() {
        return wrapperExecutable;
    }

    public void setWrapperExecutable(String wrapperExecutable) {
        this.wrapperExecutable = wrapperExecutable == null || wrapperExecutable.isBlank()
                ? PlatformExecutables.suiteBinaryPath("suite-cloudflared-wrapper")
                : wrapperExecutable.trim();
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl == null || targetUrl.isBlank() ? "http://localhost:8090" : targetUrl.trim();
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

    public String getCacheDirectory() {
        return cacheDirectory;
    }

    public void setCacheDirectory(String cacheDirectory) {
        this.cacheDirectory = cacheDirectory == null || cacheDirectory.isBlank() ? ".springsuite/cloudflared" : cacheDirectory.trim();
    }

    public String getOriginCertPath() {
        return originCertPath;
    }

    public void setOriginCertPath(String originCertPath) {
        this.originCertPath = originCertPath == null ? "" : originCertPath.trim();
    }

    public String getUserProfile() {
        return userProfile;
    }

    public void setUserProfile(String userProfile) {
        this.userProfile = userProfile == null ? "" : userProfile.trim();
    }

    public String getConfigPath() {
        return configPath;
    }

    public void setConfigPath(String configPath) {
        this.configPath = configPath == null ? "" : configPath.trim();
    }

    public String getCredentialsFile() {
        return credentialsFile;
    }

    public void setCredentialsFile(String credentialsFile) {
        this.credentialsFile = credentialsFile == null ? "" : credentialsFile.trim();
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
        this.recentLogLimit = Math.max(0, recentLogLimit);
    }
}
