package com.takesome.springsuite.desktop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.takesome.springsuite.config.SuiteWorkingDirectoryBootstrap;
import com.takesome.springsuite.core.process.ManagedProcess;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DesktopAgentSidecarRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(DesktopAgentSidecarRuntime.class);
    private static final String SOURCE = "desktop-agent-sidecar";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final DesktopAgentSidecarProperties properties;
    private final ObjectMapper objectMapper;
    private final OperatorLogService logService;
    private final HttpClient httpClient;
    private final ExecutorService outputExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "spring-suite-desktop-sidecar-io");
        thread.setDaemon(true);
        return thread;
    });
    private final ArrayDeque<String> outputTail = new ArrayDeque<>();

    private volatile Process process;
    private volatile ManagedProcess managedProcess;
    private volatile Path executable;
    private volatile String token = "";
    private volatile String baseUrl = "";
    private volatile Instant startedAt;
    private volatile Instant lastHealthAt;
    private volatile String lastCode = "stopped";
    private volatile String lastMessage = "Нативный desktop-agent ещё не запущен.";
    private volatile boolean healthy;

    public DesktopAgentSidecarRuntime(
            DesktopAgentSidecarProperties properties,
            ObjectMapper objectMapper,
            OperatorLogService logService
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.logService = logService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getRequestTimeout())
                .build();
    }

    public synchronized SidecarStatus start() {
        if (!properties.isEnabled()) {
            update("disabled", "Нативный desktop-agent отключён.", false);
            return status();
        }
        if (!isWindows()) {
            update("unsupported_platform", "Нативный desktop-agent для форм сейчас требует Windows.", false);
            return status();
        }
        if (isAlive() && healthy) {
            return status();
        }

        stopInternal();
        Path resolved = resolveExecutable();
        if (resolved == null && properties.isAutoBuild()) {
            resolved = buildSiblingProject();
        }
        if (resolved == null) {
            update("executable_missing", "Исполняемый файл suite-desktop-agent не найден.", false);
            logService.append(OperatorLogLevel.ERROR, SOURCE, lastMessage, Map.of(
                    "suiteRoot", suiteRoot().toString(),
                    "projectRoot", resolveProjectRoot().toString(),
                    "autoBuild", properties.isAutoBuild()
            ));
            return status();
        }

        validateLoopback(properties.getHost());
        int port = properties.getPort() > 0 ? properties.getPort() : reservePort(properties.getHost());
        String runtimeToken = UUID.randomUUID().toString() + UUID.randomUUID();
        String listen = properties.getHost() + ":" + port;

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    resolved.toString(),
                    "serve",
                    "--listen", listen,
                    "--token", runtimeToken
            );
            builder.directory(resolved.getParent().toFile());
            builder.redirectErrorStream(true);
            ManagedProcess startedOwner = ManagedProcess.start(
                    builder,
                    "desktop-agent-sidecar",
                    properties.getShutdownTimeout(),
                    Duration.ofSeconds(3)
            );
            Process started = startedOwner.process();

            this.managedProcess = startedOwner;
            this.process = started;
            this.executable = resolved;
            this.token = runtimeToken;
            this.baseUrl = "http://" + listen;
            this.startedAt = Instant.now();
            this.healthy = false;
            this.outputTail.clear();
            drainOutput(started);

            Instant deadline = Instant.now().plus(properties.getStartupTimeout());
            Exception lastError = null;
            while (Instant.now().isBefore(deadline)) {
                if (!started.isAlive()) {
                    update("process_exited", "Нативный desktop-agent завершился при запуске с кодом " + started.exitValue() + ".", false);
                    break;
                }
                try {
                    Map<String, Object> health = request("GET", "/health", null, true);
                    if (booleanValue(health.get("ok"))) {
                        lastHealthAt = Instant.now();
                        update("running", "Нативный desktop-agent запущен и исправен.", true);
                        logService.append(OperatorLogLevel.INFO, SOURCE, "нативный desktop-agent запущен", Map.of(
                                "pid", started.pid(),
                                "executable", resolved.toString(),
                                "baseUrl", baseUrl
                        ));
                        return status();
                    }
                } catch (Exception ex) {
                    lastError = ex;
                }
                sleep(120L);
            }
            if (!healthy && lastError != null) {
                update("health_timeout", "Нативный desktop-agent не прошёл проверку готовности: " + safeMessage(lastError), false);
            }
        } catch (Exception ex) {
            update("start_failed", "Не удалось запустить нативный desktop-agent: " + safeMessage(ex), false);
        }

        if (!healthy) {
            logService.append(OperatorLogLevel.ERROR, SOURCE, lastMessage, Map.of(
                    "executable", resolved.toString(),
                    "outputTail", outputSnapshot()
            ));
            stopInternal();
        }
        return status();
    }

    public synchronized SidecarStatus restart() {
        stopInternal();
        return start();
    }

    public Map<String, Object> inspect() {
        ensureRunning();
        Map<String, Object> response = request("GET", "/v1/inspect", null, false);
        if (!booleanValue(response.get("ok"))) {
            throw new IllegalStateException(firstText(text(response.get("message")), "Не удалось получить сведения об активной форме."));
        }
        return response;
    }

    public Map<String, Object> fill(Map<String, Object> payload) {
        ensureRunning();
        return request("POST", "/v1/fill", payload == null ? Map.of() : payload, false);
    }

    public synchronized SidecarStatus refreshHealth() {
        if (!isAlive() || baseUrl.isBlank()) {
            update("stopped", "Нативный desktop-agent не запущен.", false);
            return status();
        }
        try {
            Map<String, Object> response = request("GET", "/health", null, true);
            lastHealthAt = Instant.now();
            update(booleanValue(response.get("ok")) ? "running" : "unhealthy",
                    booleanValue(response.get("ok")) ? "Нативный desktop-agent исправен." : "Проверка состояния нативного desktop-agent завершилась ошибкой.",
                    booleanValue(response.get("ok")));
        } catch (Exception ex) {
            update("health_failed", safeMessage(ex), false);
        }
        return status();
    }

    public SidecarStatus status() {
        Process current = process;
        return new SidecarStatus(
                properties.isEnabled(),
                current != null && current.isAlive(),
                healthy,
                current == null ? null : current.pid(),
                executable == null ? "" : executable.toString(),
                baseUrl,
                startedAt,
                lastHealthAt,
                lastCode,
                lastMessage,
                outputSnapshot(),
                Map.of(
                        "autoStart", properties.isAutoStart(),
                        "autoBuild", properties.isAutoBuild(),
                        "configuredProjectRoot", properties.getProjectRoot(),
                        "configuredPort", properties.getPort()
                )
        );
    }

    private void ensureRunning() {
        if (isAlive() && healthy) {
            return;
        }
        SidecarStatus status = start();
        if (!status.running() || !status.healthy()) {
            throw new IllegalStateException(firstText(status.message(), "Нативный desktop-agent недоступен."));
        }
    }

    private Map<String, Object> request(String method, String path, Map<String, Object> body, boolean healthRequest) {
        if (baseUrl.isBlank() || token.isBlank()) {
            throw new IllegalStateException("Адрес нативного desktop-agent не инициализирован.");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(properties.getRequestTimeout())
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");
            if ("POST".equals(method)) {
                String json = objectMapper.writeValueAsString(body == null ? Map.of() : body);
                builder.header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> parsed = response.body() == null || response.body().isBlank()
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(objectMapper.readValue(response.body(), MAP_TYPE));
            parsed.put("httpStatus", response.statusCode());
            if (response.statusCode() >= 500 || (healthRequest && response.statusCode() >= 400)) {
                throw new IllegalStateException("HTTP-ошибка нативного desktop-agent: " + response.statusCode() + ": " + firstText(text(parsed.get("message")), response.body()));
            }
            return Map.copyOf(parsed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос к нативному desktop-agent был прерван.", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Запрос к нативному desktop-agent завершился ошибкой: " + safeMessage(ex), ex);
        }
    }

    private Path resolveExecutable() {
        ArrayList<Path> candidates = new ArrayList<>();
        Path root = suiteRoot();
        addConfiguredCandidate(candidates, root, properties.getExecutable());
        candidates.add(root.resolve("suiteBinaries/suite-desktop-agent.exe"));
        candidates.add(root.resolve("suiteBinaries/suite-desktop-agent"));
        Path project = resolveProjectRoot();
        candidates.add(project.resolve("dist/suite-desktop-agent.exe"));
        candidates.add(project.resolve("dist/suite-desktop-agent"));
        candidates.add(project.resolve("build/suite-desktop-agent.exe"));
        candidates.add(project.resolve("build/suite-desktop-agent"));
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return findOnPath("suite-desktop-agent.exe", "suite-desktop-agent");
    }

    private Path buildSiblingProject() {
        Path project = resolveProjectRoot();
        Path source = project.resolve("cmd/suite-desktop-agent");
        if (!Files.isDirectory(project) || !Files.isDirectory(source)) {
            return null;
        }
        Path dist = project.resolve("dist");
        Path output = dist.resolve(isWindows() ? "suite-desktop-agent.exe" : "suite-desktop-agent");
        ManagedProcess buildOwner = null;
        ExecutorService buildOutputExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "spring-suite-desktop-agent-build-output");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Files.createDirectories(dist);
            ProcessBuilder builder = new ProcessBuilder(
                    "go", "build", "-trimpath", "-ldflags", "-s -w",
                    "-o", output.toString(), "./cmd/suite-desktop-agent"
            );
            builder.directory(project.toFile());
            builder.redirectErrorStream(true);
            buildOwner = ManagedProcess.start(
                    builder,
                    "desktop-agent-build",
                    Duration.ofMillis(250),
                    Duration.ofSeconds(3)
            );
            Process build = buildOwner.process();
            Future<String> outputFuture = buildOutputExecutor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(build.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder captured = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (captured.length() < 8_000) {
                            if (!captured.isEmpty()) {
                                captured.append(System.lineSeparator());
                            }
                            captured.append(line, 0, Math.min(line.length(), 8_000 - captured.length()));
                        }
                    }
                    return captured.toString();
                }
            });
            boolean finished = build.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                buildOwner.terminate(Duration.ofMillis(250), Duration.ofSeconds(3));
                update("build_timeout", "Истекло время сборки suite-desktop-agent-go.", false);
                return null;
            }
            buildOwner.complete();
            String outputText = outputFuture.get(2, TimeUnit.SECONDS);
            if (build.exitValue() != 0 || !Files.isRegularFile(output)) {
                update("build_failed", "Сборка suite-desktop-agent-go завершилась ошибкой: " + truncate(outputText, 2_000), false);
                return null;
            }
            logService.append(OperatorLogLevel.INFO, SOURCE, "desktop-agent собран из соседнего проекта", Map.of(
                    "project", project.toString(),
                    "output", output.toString()
            ));
            return output.toAbsolutePath().normalize();
        } catch (Exception ex) {
            update("build_failed", "Сборка suite-desktop-agent-go завершилась ошибкой: " + safeMessage(ex), false);
            return null;
        } finally {
            if (buildOwner != null && buildOwner.isAlive()) {
                buildOwner.terminate(Duration.ofMillis(100), Duration.ofSeconds(3));
            }
            buildOutputExecutor.shutdownNow();
        }
    }

    private void drainOutput(Process started) {
        outputExecutor.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (outputTail) {
                        outputTail.addLast(truncate(line, 2_000));
                        while (outputTail.size() > 100) {
                            outputTail.removeFirst();
                        }
                    }
                    LOGGER.debug("[suite-desktop-agent] {}", line);
                }
            } catch (IOException ex) {
                if (started.isAlive()) {
                    LOGGER.debug("Ошибка чтения вывода нативного desktop-agent", ex);
                }
            }
        });
    }

    private synchronized void stopInternal() {
        Process current = process;
        ManagedProcess owner = managedProcess;
        process = null;
        managedProcess = null;
        healthy = false;
        token = "";
        baseUrl = "";
        if (owner != null) {
            ManagedProcess.TerminationReport report = owner.terminate(
                    properties.getShutdownTimeout(),
                    Duration.ofSeconds(3)
            );
            if (!report.clean()) {
                logService.append(OperatorLogLevel.ERROR, SOURCE, "desktop-agent process tree left survivors", Map.of(
                        "rootPid", report.rootPid(),
                        "observedPids", report.observedPids(),
                        "survivingPids", report.survivingPids()
                ));
            }
            return;
        }
        if (current != null && current.isAlive()) {
            ManagedProcess.adopt(
                    current,
                    "desktop-agent-sidecar-recovered",
                    properties.getShutdownTimeout(),
                    Duration.ofSeconds(3)
            ).terminate();
        }
    }

    private boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    private int reservePort(String host) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(host, 0));
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось зарезервировать локальный порт для desktop-agent.", ex);
        }
    }

    private void validateLoopback(String host) {
        try {
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalArgumentException("Адрес desktop-agent должен быть локальным loopback-адресом: " + host);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Некорректный адрес desktop-agent: " + host, ex);
        }
    }

    private Path resolveProjectRoot() {
        Path configured = Path.of(properties.getProjectRoot());
        return configured.isAbsolute()
                ? configured.toAbsolutePath().normalize()
                : suiteRoot().resolve(configured).toAbsolutePath().normalize();
    }

    private Path suiteRoot() {
        return SuiteWorkingDirectoryBootstrap.installedDirectory();
    }

    private void addConfiguredCandidate(List<Path> candidates, Path root, String configured) {
        if (configured == null || configured.isBlank()) {
            return;
        }
        Path path = Path.of(configured);
        candidates.add(path.isAbsolute() ? path : root.resolve(path));
    }

    private Path findOnPath(String... names) {
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) {
            return null;
        }
        for (String directory : pathValue.split(java.io.File.pathSeparator)) {
            for (String name : names) {
                Path candidate = Path.of(directory).resolve(name).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private List<String> outputSnapshot() {
        synchronized (outputTail) {
            return List.copyOf(outputTail);
        }
    }

    private void update(String code, String message, boolean healthy) {
        this.lastCode = code == null ? "" : code;
        this.lastMessage = message == null ? "" : message;
        this.healthy = healthy;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(text(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value == null ? "" : value;
        }
        return value.substring(0, limit) + "...";
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    @PreDestroy
    public synchronized void stop() {
        stopInternal();
        outputExecutor.shutdownNow();
    }

    public record SidecarStatus(
            boolean enabled,
            boolean running,
            boolean healthy,
            Long pid,
            String executable,
            String baseUrl,
            Instant startedAt,
            Instant lastHealthAt,
            String code,
            String message,
            List<String> outputTail,
            Map<String, Object> metadata
    ) {
        public SidecarStatus {
            executable = executable == null ? "" : executable;
            baseUrl = baseUrl == null ? "" : baseUrl;
            code = code == null ? "" : code;
            message = message == null ? "" : message;
            outputTail = outputTail == null ? List.of() : List.copyOf(outputTail);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
