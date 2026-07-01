package com.takesome.springsuite.cloudflared;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

@Service
public class CloudflaredTunnelService {
    private static final Pattern TRY_CLOUDFLARE_URL = Pattern.compile("https://[-a-zA-Z0-9.]+\\.trycloudflare\\.com");

    private final CloudflaredProperties properties;
    private final OperatorLogService operatorLogService;
    private final TaskExecutor processTaskExecutor;
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
            @Qualifier("suiteProcessTaskExecutor") TaskExecutor processTaskExecutor
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

            List<String> command = command();
            Path runtimeDirectory = runtimeDirectory();
            try {
                Files.createDirectories(runtimeDirectory);
                Path processCacheDirectory = runtimeDirectory.resolve("cache");
                Files.createDirectories(processCacheDirectory);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(runtimeDirectory.toFile());
                Map<String, String> environment = builder.environment();
                environment.put("CLOUDFLARED_HOME", runtimeDirectory.toString());
                environment.put("HOME", runtimeDirectory.toString());
                environment.put("USERPROFILE", runtimeDirectory.toString());
                environment.put("XDG_CONFIG_HOME", runtimeDirectory.toString());
                environment.put("XDG_CACHE_HOME", processCacheDirectory.toString());
                builder.redirectErrorStream(true);
                process = builder.start();
                startedAt = Instant.now();
                publicUrl = null;
                lastError = null;
                exitCode = null;
                lastCommand = List.copyOf(command);
                clearRecentLinesLocked();
                operatorLogService.append(OperatorLogLevel.INFO, "cloudflared", "cloudflared process started", Map.of(
                        "pid", process.pid(),
                        "command", command,
                        "runtimeDirectory", runtimeDirectory.toString()
                ));
                processTaskExecutor.execute(this::readProcessOutput);
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
        synchronized (lock) {
            if (!isRunningLocked()) {
                return statusLocked();
            }
            toStop = process;
            operatorLogService.append(OperatorLogLevel.INFO, "cloudflared", "stopping cloudflared process", Map.of("pid", toStop.pid()));
            toStop.destroy();
        }

        try {
            boolean exited = toStop.waitFor(properties.getStopTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                toStop.destroyForcibly();
                toStop.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            operatorLogService.append(OperatorLogLevel.WARN, "cloudflared", "interrupted while stopping cloudflared", Map.of("error", ex.getMessage()));
        }

        synchronized (lock) {
            exitCode = safeExitCode(toStop).orElse(null);
            process = null;
            startedAt = null;
            operatorLogService.append(OperatorLogLevel.INFO, "cloudflared", "cloudflared process stopped", Map.of("exitCode", exitCode));
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
        return null;
    }

    private List<String> command() {
        ArrayList<String> command = new ArrayList<>();
        command.add(properties.getExecutable());
        command.add("tunnel");
        command.addAll(properties.getExtraArgs());
        command.add("run");
        command.add("--url");
        command.add(properties.getTargetUrl());
        if (!properties.getTunnelName().isBlank()) {
            command.add(properties.getTunnelName());
        }
        return command;
    }

    private Path runtimeDirectory() {
        String raw = properties.getCacheDirectory();
        Path configured = Path.of(raw == null || raw.isBlank() ? ".springsuite/cloudflared" : raw.trim());
        return configured.isAbsolute()
                ? configured.normalize()
                : Path.of("").toAbsolutePath().normalize().resolve(configured).normalize();
    }

    private void readProcessOutput() {
        Process current;
        synchronized (lock) {
            current = process;
        }
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
        }
        operatorLogService.append(OperatorLogLevel.INFO, "cloudflared", cleaned);
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
