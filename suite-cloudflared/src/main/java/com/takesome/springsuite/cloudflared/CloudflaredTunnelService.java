package com.takesome.springsuite.cloudflared;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.core.platform.PlatformExecutables;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class CloudflaredTunnelService {
    private static final Pattern TRY_CLOUDFLARE_URL = Pattern.compile("https://[-a-zA-Z0-9.]+\\.trycloudflare\\.com");
    private static final long STARTUP_GRACE_MILLIS = 900L;

    private final CloudflaredProperties properties;
    private final OperatorLogService operatorLogService;
    private final Executor processTaskExecutor;
    private final Object lock = new Object();
    private final ArrayDeque<String> recentLines = new ArrayDeque<>();

    private Process process;
    private Instant startedAt;
    private String publicUrl;
    private String lastError;
    private Integer exitCode;
    private List<String> lastCommand = List.of();

    public CloudflaredTunnelService(
            CloudflaredProperties properties,
            OperatorLogService operatorLogService,
            @Qualifier("suiteProcessTaskExecutor") Executor processTaskExecutor
    ) {
        this.properties = properties;
        this.operatorLogService = operatorLogService;
        this.processTaskExecutor = processTaskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        operatorLogService.append(OperatorLogLevel.INFO, "cloudflared", "cloudflared tunnel service ready", Map.of(
                "enabled", properties.isEnabled(),
                "autoStart", properties.isAutoStart(),
                "targetUrl", properties.getTargetUrl()
        ));
        if (properties.isEnabled() && properties.isAutoStart()) {
            start();
        }
    }

    public CloudflaredTunnelStatus start() {
        synchronized (lock) {
            if (!properties.isEnabled()) {
                lastError = "cloudflared service is disabled by suite.cloudflared.enabled=false";
                operatorLogService.append(OperatorLogLevel.WARN, "cloudflared", lastError);
                return statusLocked();
            }
            if (isRunningLocked()) {
                return statusLocked();
            }
            String configError = validateStartConfig();
            if (configError != null) {
                lastError = configError;
                operatorLogService.append(OperatorLogLevel.ERROR, "cloudflared", configError);
                return statusLocked();
            }

            Path runtimeRoot = Path.of("").toAbsolutePath().normalize();
            Optional<Path> resolvedCloudflared = CloudflaredExecutableResolver.resolve(
                    runtimeRoot,
                    properties.getExecutable(),
                    System.getenv()
            );
            if (resolvedCloudflared.isEmpty()) {
                List<String> searched = CloudflaredExecutableResolver.searchCandidates(
                                runtimeRoot,
                                properties.getExecutable(),
                                System.getenv()
                        ).stream()
                        .limit(12)
                        .map(Path::toString)
                        .collect(Collectors.toList());
                lastError = "cloudflared executable was not found; configure suite.cloudflared.executable "
                        + "or " + CloudflaredExecutableResolver.EXECUTABLE_ENV;
                operatorLogService.append(OperatorLogLevel.ERROR, "cloudflared", lastError, Map.of(
                        "configuredExecutable", properties.getExecutable(),
                        "searchedCandidates", searched
                ));
                return statusLocked();
            }

            String cloudflaredExecutable = resolvedCloudflared.get().toString();
            List<String> command = command(cloudflaredExecutable);
            Path runtimeDirectory = runtimeDirectory();
            try {
                Files.createDirectories(runtimeDirectory);
                Path processCacheDirectory = runtimeDirectory.resolve("cache");
                Files.createDirectories(processCacheDirectory);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(runtimeDirectory.toFile());
                Map<String, String> environment = builder.environment();
                environment.put("XDG_CACHE_HOME", processCacheDirectory.toString());
                environment.put(CloudflaredExecutableResolver.EXECUTABLE_ENV, cloudflaredExecutable);
                applyConfiguredUserProfile(environment);
                String originCertPath = properties.getOriginCertPath();
                if (originCertPath != null && !originCertPath.isBlank()) {
                    environment.put("TUNNEL_ORIGIN_CERT", resolveConfiguredPath(originCertPath).toString());
                }
                builder.redirectErrorStream(true);
                process = builder.start();
                Process startedProcess = process;
                startedAt = Instant.now();
                publicUrl = null;
                lastError = null;
                exitCode = null;
                lastCommand = List.copyOf(command);
                clearRecentLinesLocked();
                operatorLogService.append(OperatorLogLevel.INFO, "cloudflared", "cloudflared process started", Map.of(
                        "pid", startedProcess.pid(),
                        "command", command,
                        "cloudflaredExecutable", cloudflaredExecutable,
                        "runtimeDirectory", runtimeDirectory.toString()
                ));
                processTaskExecutor.execute(() -> readProcessOutput(startedProcess));
                try {
                    if (startedProcess.waitFor(STARTUP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                        exitCode = startedProcess.exitValue();
                        process = null;
                        startedAt = null;
                        if (lastError == null || lastError.isBlank()) {
                            lastError = "cloudflared exited during startup with code " + exitCode;
                        }
                    } else if (!properties.getHostname().isBlank()) {
                        publicUrl = "https://" + properties.getHostname();
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    lastError = "interrupted while verifying cloudflared startup";
                }
            } catch (IOException ex) {
                process = null;
                startedAt = null;
                lastError = ex.getMessage();
                operatorLogService.append(OperatorLogLevel.ERROR, "cloudflared", "failed to start cloudflared", Map.of(
                        "error", ex.getMessage(),
                        "command", command,
                        "runtimeDirectory", runtimeDirectory.toString()
                ));
            }
            return statusLocked();
        }
    }

    public CloudflaredTunnelStatus stop() {
        Process toStop;
        List<ProcessHandle> descendants;
        synchronized (lock) {
            if (!isRunningLocked()) {
                return statusLocked();
            }
            toStop = process;
            descendants = toStop.descendants().toList();
            operatorLogService.append(
                    OperatorLogLevel.INFO,
                    "cloudflared",
                    "stopping cloudflared process tree",
                    Map.of(
                            "pid", toStop.pid(),
                            "descendants", descendants.stream().map(ProcessHandle::pid).toList()
                    )
            );
            descendants.forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroy();
                }
            });
            toStop.destroy();
        }

        try {
            boolean exited = toStop.waitFor(
                    properties.getStopTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!exited) {
                descendants.forEach(handle -> {
                    if (handle.isAlive()) {
                        handle.destroyForcibly();
                    }
                });
                toStop.destroyForcibly();
                toStop.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            operatorLogService.append(
                    OperatorLogLevel.WARN,
                    "cloudflared",
                    "interrupted while stopping cloudflared",
                    Map.of(
                            "error",
                            ex.getMessage() == null
                                    ? ex.getClass().getSimpleName()
                                    : ex.getMessage()
                    )
            );
        } finally {
            descendants.forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
        }

        synchronized (lock) {
            exitCode = safeExitCode(toStop).orElse(null);
            process = null;
            startedAt = null;
            publicUrl = null;
            lastError = null;
            operatorLogService.append(
                    OperatorLogLevel.INFO,
                    "cloudflared",
                    "cloudflared process tree stopped",
                    Map.of("exitCode", exitCode == null ? -1 : exitCode)
            );
            return statusLocked();
        }
    }
    public CloudflaredTunnelStatus restart() {
        stop();
        return start();
    }

    public CloudflaredTunnelStatus status() {
        synchronized (lock) {
            return statusLocked();
        }
    }

    public List<String> recentLogs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, properties.getRecentLogLimit()));
        synchronized (lock) {
            ArrayList<String> snapshot = new ArrayList<>(recentLines);
            int from = Math.max(0, snapshot.size() - safeLimit);
            return List.copyOf(snapshot.subList(from, snapshot.size()));
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
    }

    private String validateStartConfig() {
        if (properties.getExecutable() == null || properties.getExecutable().isBlank()) {
            return "cloudflared executable is not configured";
        }
        if (properties.getTargetUrl() == null || properties.getTargetUrl().isBlank()) {
            return "cloudflared target-url is not configured";
        }
        if (properties.getTunnelName() == null || properties.getTunnelName().isBlank()) {
            return "cloudflared tunnel-name is not configured";
        }
        String configError = validateConfiguredFile("cloudflared config", properties.getConfigPath());
        if (configError != null) {
            return configError;
        }
        String credentialsError = validateConfiguredFile("cloudflared credentials", properties.getCredentialsFile());
        if (credentialsError != null) {
            return credentialsError;
        }
        return null;
    }

    private String validateConfiguredFile(String label, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path resolved = resolveConfiguredPath(raw);
        return Files.isRegularFile(resolved) ? null : label + " file not found: " + resolved;
    }

    private List<String> command(String cloudflaredExecutable) {
        if (shouldUseWrapper()) {
            return wrapperCommand(cloudflaredExecutable);
        }
        if (properties.isWrapperEnabled() && properties.getWrapperExecutable() != null && !properties.getWrapperExecutable().isBlank()) {
            operatorLogService.append(OperatorLogLevel.WARN, "cloudflared", "cloudflared wrapper unavailable; falling back to legacy cloudflared executable", Map.of(
                    "wrapperExecutable", resolveExecutablePath(properties.getWrapperExecutable()).toString(),
                    "fallbackExecutable", properties.getExecutable()
            ));
        }
        return legacyCloudflaredCommand(cloudflaredExecutable);
    }

    private List<String> wrapperCommand(String cloudflaredExecutable) {
        ArrayList<String> command = new ArrayList<>();
        command.add(resolveExecutablePath(properties.getWrapperExecutable()).toString());
        command.add("run");
        command.add("--cloudflared");
        command.add(cloudflaredExecutable);
        if (!properties.getConfigPath().isBlank()) {
            command.add("--config");
            command.add(resolveConfiguredPath(properties.getConfigPath()).toString());
        }
        if (!properties.getCredentialsFile().isBlank()) {
            command.add("--credentials-file");
            command.add(resolveConfiguredPath(properties.getCredentialsFile()).toString());
        }
        if (!properties.getTunnelName().isBlank()) {
            command.add("--mode");
            command.add("run");
            command.add("--tunnel");
            command.add(properties.getTunnelName());
        }
        if (properties.getTunnelName().isBlank()) {
            command.add("--url");
            command.add(properties.getTargetUrl());
        }
        command.add("--runtime-dir");
        command.add(runtimeDirectory().toString());
        if (!properties.getExtraArgs().isEmpty()) {
            command.add("--");
            command.addAll(properties.getExtraArgs());
        }
        return command;
    }

    private List<String> legacyCloudflaredCommand(String cloudflaredExecutable) {
        ArrayList<String> command = new ArrayList<>();
        command.add(cloudflaredExecutable);
        command.add("tunnel");
        command.addAll(properties.getExtraArgs());
        if (!properties.getConfigPath().isBlank()) {
            command.add("--config");
            command.add(resolveConfiguredPath(properties.getConfigPath()).toString());
        }
        command.add("run");
        if (!properties.getCredentialsFile().isBlank()) {
            command.add("--credentials-file");
            command.add(resolveConfiguredPath(properties.getCredentialsFile()).toString());
        }
        command.add("--url");
        command.add(properties.getTargetUrl());
        if (!properties.getTunnelName().isBlank()) {
            command.add(properties.getTunnelName());
        }
        return command;
    }

    private boolean shouldUseWrapper() {
        if (!properties.isWrapperEnabled()) {
            return false;
        }
        String executable = properties.getWrapperExecutable();
        if (executable == null || executable.isBlank()) {
            return false;
        }
        return Files.isRegularFile(resolveExecutablePath(executable));
    }

    private Path resolveExecutablePath(String raw) {
        Path runtimeRoot = Path.of("").toAbsolutePath().normalize();
        return PlatformExecutables.resolveExecutable(runtimeRoot, raw)
                .orElseGet(() -> {
                    Path path = Path.of(raw == null ? "" : raw.trim());
                    return path.isAbsolute()
                            ? path.toAbsolutePath().normalize()
                            : runtimeRoot.resolve(path).normalize();
                });
    }

    private void applyConfiguredUserProfile(Map<String, String> environment) {
        String raw = properties.getUserProfile();
        if (raw == null || raw.isBlank()) {
            return;
        }
        Path userProfile = resolveConfiguredPath(raw);
        environment.put("USERPROFILE", userProfile.toString());
        environment.put("HOME", userProfile.toString());
        Path cloudflaredHome = userProfile.resolve(".cloudflared");
        if (Files.isDirectory(cloudflaredHome)) {
            environment.put("CLOUDFLARED_HOME", cloudflaredHome.toString());
        }
    }

    private Path resolveConfiguredPath(String raw) {
        Path path = Path.of(raw == null ? "" : raw.trim());
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : Path.of("").toAbsolutePath().normalize().resolve(path).normalize();
    }

    private Path runtimeDirectory() {
        String raw = properties.getCacheDirectory();
        Path configured = Path.of(raw == null || raw.isBlank() ? ".springsuite/cloudflared" : raw.trim());
        return configured.isAbsolute()
                ? configured.normalize()
                : Path.of("").toAbsolutePath().normalize().resolve(configured).normalize();
    }

    private void readProcessOutput(Process current) {
        if (current == null) {
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleProcessLine(line);
            }
        } catch (IOException ex) {
            synchronized (lock) {
                lastError = ex.getMessage();
            }
            operatorLogService.append(OperatorLogLevel.WARN, "cloudflared", "cloudflared output reader failed", Map.of("error", ex.getMessage()));
        } finally {
            int code = safeExitCode(current).orElse(Integer.MIN_VALUE);
            synchronized (lock) {
                if (process == current) {
                    exitCode = code == Integer.MIN_VALUE ? null : code;
                    process = null;
                    startedAt = null;
                }
                if (code != 0 && code != Integer.MIN_VALUE && (lastError == null || lastError.isBlank())) {
                    lastError = recentLines.isEmpty()
                            ? "cloudflared exited with code " + code
                            : recentLines.getLast();
                }
            }
            operatorLogService.append(OperatorLogLevel.INFO, "cloudflared", "cloudflared process exited", Map.of("exitCode", code));
        }
    }

    private void handleProcessLine(String line) {
        String cleaned = line == null ? "" : line.strip();
        synchronized (lock) {
            recentLines.addLast(cleaned);
            while (recentLines.size() > properties.getRecentLogLimit()) {
                recentLines.removeFirst();
            }
            Matcher matcher = TRY_CLOUDFLARE_URL.matcher(cleaned);
            if (matcher.find()) {
                publicUrl = matcher.group();
            }
            if (looksLikeError(cleaned)) {
                lastError = cleaned;
            }
        }
        operatorLogService.append(looksLikeError(cleaned) ? OperatorLogLevel.WARN : OperatorLogLevel.INFO, "cloudflared", cleaned);
    }

    private boolean looksLikeError(String line) {
        String value = line == null ? "" : line.toLowerCase(java.util.Locale.ROOT);
        return value.contains("\"level\":\"error\"")
                || value.contains(" error ")
                || value.startsWith("error")
                || value.contains("failed")
                || value.contains("unable to")
                || value.contains("credentials file")
                || value.contains("origin certificate")
                || value.contains("cannot determine default origin certificate");
    }

    private CloudflaredTunnelStatus statusLocked() {
        Process current = process;
        boolean running = isRunningLocked();
        return new CloudflaredTunnelStatus(
                properties.isEnabled(),
                running,
                running && current != null ? current.pid() : null,
                properties.getTargetUrl(),
                properties.getTunnelName(),
                properties.getHostname(),
                runtimeDirectory().toString(),
                publicUrl,
                startedAt,
                exitCode,
                lastError,
                lastCommand
        );
    }

    private boolean isRunningLocked() {
        return process != null && process.isAlive();
    }

    private void clearRecentLinesLocked() {
        recentLines.clear();
    }

    private static Optional<Integer> safeExitCode(Process process) {
        if (process == null || process.isAlive()) {
            return Optional.empty();
        }
        try {
            return Optional.of(process.exitValue());
        } catch (IllegalThreadStateException ex) {
            return Optional.empty();
        }
    }
}
