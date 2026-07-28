package com.takesome.springsuite.workspace.fs;

import com.takesome.springsuite.core.platform.PlatformExecutables;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "suite.fs")
public class SuiteFsProperties {
    private String backend = "auto";
    private String root = ".";
    private String workerPath = PlatformExecutables.suiteBinaryPath("suite-fs-worker");
    private int protocolVersion = 1;
    private Duration startTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(30);
    private int maxEntries = 0;
    private int maxReadBytes = 0;
    private int maxLineBytes = 0;
    private boolean restartOnCrash = true;
    private int maxRestarts = 3;
    private boolean allowSymlinks = false;
    private boolean allowAbsolutePaths = false;

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend == null || backend.isBlank() ? "auto" : backend.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root == null || root.isBlank() ? "." : root.trim();
    }

    public String getWorkerPath() {
        return workerPath;
    }

    public void setWorkerPath(String workerPath) {
        this.workerPath = workerPath == null || workerPath.isBlank() ? PlatformExecutables.suiteBinaryPath("suite-fs-worker") : workerPath.trim();
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = Math.max(1, protocolVersion);
    }

    public Duration getStartTimeout() {
        return startTimeout;
    }

    public void setStartTimeout(Duration startTimeout) {
        this.startTimeout = startTimeout == null || startTimeout.isNegative() || startTimeout.isZero()
                ? Duration.ofSeconds(3)
                : startTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                ? Duration.ofSeconds(30)
                : requestTimeout;
    }

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = Math.max(0, maxEntries);
    }

    public int getMaxReadBytes() {
        return maxReadBytes;
    }

    public void setMaxReadBytes(int maxReadBytes) {
        this.maxReadBytes = Math.max(0, maxReadBytes);
    }

    public int getMaxLineBytes() {
        return maxLineBytes;
    }

    public void setMaxLineBytes(int maxLineBytes) {
        this.maxLineBytes = Math.max(0, maxLineBytes);
    }

    public boolean isRestartOnCrash() {
        return restartOnCrash;
    }

    public void setRestartOnCrash(boolean restartOnCrash) {
        this.restartOnCrash = restartOnCrash;
    }

    public int getMaxRestarts() {
        return maxRestarts;
    }

    public void setMaxRestarts(int maxRestarts) {
        this.maxRestarts = Math.max(0, maxRestarts);
    }

    public boolean isAllowSymlinks() {
        return allowSymlinks;
    }

    public void setAllowSymlinks(boolean allowSymlinks) {
        this.allowSymlinks = allowSymlinks;
    }

    public boolean isAllowAbsolutePaths() {
        return allowAbsolutePaths;
    }

    public void setAllowAbsolutePaths(boolean allowAbsolutePaths) {
        this.allowAbsolutePaths = allowAbsolutePaths;
    }
}
