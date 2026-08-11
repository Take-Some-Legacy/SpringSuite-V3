package com.takesome.springsuite.workspace.fs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import com.takesome.springsuite.core.platform.PlatformExecutables;
import com.takesome.springsuite.core.process.ManagedProcess;
import com.takesome.springsuite.workspace.WorkspaceEntry;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GoWorkspaceFsBackend implements WorkspaceFsBackend {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SuiteFsProperties properties;
    private final WorkspacePathPolicy pathPolicy;
    private final OperatorLogService logService;
    private final AtomicBoolean unavailableLogged = new AtomicBoolean(false);

    private Process workerProcess;
    private ManagedProcess managedWorker;
    private BufferedWriter writer;
    private BufferedReader reader;
    private ExecutorService readerExecutor;
    private volatile boolean available;

    public GoWorkspaceFsBackend(SuiteFsProperties properties, WorkspacePathPolicy pathPolicy, OperatorLogService logService) {
        this.properties = properties;
        this.pathPolicy = pathPolicy;
        this.logService = logService;
    }

    void start() {
        Path worker = resolveWorkerPath();
        if (!Files.isRegularFile(worker)) {
            available = false;
            logService.append(OperatorLogLevel.WARN, "workspace", "go fs worker not found", Map.of("worker", worker.toString()));
            return;
        }
        try {
            workerProcess = launch(worker, pathPolicy.runtimeRoot().toFile());
            managedWorker = ManagedProcess.adopt(
                    workerProcess,
                    "workspace-fs-worker",
                    Duration.ofMillis(250),
                    Duration.ofSeconds(3)
            );
            writer = new BufferedWriter(new OutputStreamWriter(workerProcess.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(workerProcess.getInputStream(), StandardCharsets.UTF_8));
            readerExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "suite-fs-worker-reader");
                thread.setDaemon(true);
                return thread;
            });
            available = workerProcess.isAlive();
            ping();
            logService.append(OperatorLogLevel.INFO, "workspace", "go fs worker started", Map.of(
                    "worker", worker.toString(),
                    "pid", workerProcess.pid(),
                    "protocol", properties.getProtocolVersion()
            ));
        } catch (Exception ex) {
            markUnavailable(ex);
        }
    }

    @Override
    public WorkspaceFsBackendKind kind() {
        return WorkspaceFsBackendKind.GO;
    }

    @Override
    public boolean isAvailable() {
        return available && workerProcess != null && workerProcess.isAlive();
    }

    @Override
    public List<WorkspaceEntry> list(Path target, int maxEntries, WorkspacePathPolicy policy) {
        ObjectNode request = baseRequest("list");
        request.put("root", target.toAbsolutePath().normalize().toString());
        request.put("path", ".");
        request.put("maxEntries", maxEntries);
        JsonNode data = send(request);
        return entriesFromData(data, target.toAbsolutePath().normalize(), policy);
    }

    @Override
    public List<WorkspaceEntry> tree(Path target, int maxDepth, int maxEntries, WorkspacePathPolicy policy) {
        ObjectNode request = baseRequest("walk");
        request.put("root", target.toAbsolutePath().normalize().toString());
        request.put("path", ".");
        request.put("maxDepth", maxDepth);
        request.put("maxEntries", maxEntries);
        JsonNode data = send(request);
        return entriesFromData(data, target.toAbsolutePath().normalize(), policy);
    }

    @Override
    public byte[] readAllBytes(Path target) {
        WorkerTarget rooted = rootedAtParent(target);
        ObjectNode request = baseRequest("readAll");
        request.put("root", rooted.root().toString());
        request.put("path", rooted.path());
        request.put("maxBytes", properties.getMaxReadBytes());
        JsonNode data = send(request);
        return Base64.getDecoder().decode(data.path("base64").asText(""));
    }

    @Override
    public synchronized void close() {
        available = false;
        if (readerExecutor != null) {
            readerExecutor.shutdownNow();
            readerExecutor = null;
        }
        ManagedProcess owner = managedWorker;
        Process current = workerProcess;
        managedWorker = null;
        workerProcess = null;
        if (owner != null) {
            ManagedProcess.TerminationReport report = owner.terminate(
                    Duration.ofMillis(250),
                    Duration.ofSeconds(3)
            );
            if (!report.clean()) {
                logService.append(OperatorLogLevel.ERROR, "workspace", "go fs worker process tree left survivors", Map.of(
                        "rootPid", report.rootPid(),
                        "observedPids", report.observedPids(),
                        "survivingPids", report.survivingPids()
                ));
            }
        } else if (current != null && current.isAlive()) {
            ManagedProcess.adopt(current, "workspace-fs-worker-recovered")
                    .terminate(Duration.ZERO, Duration.ofSeconds(3));
        }
        writer = null;
        reader = null;
    }

    private void ping() {
        send(baseRequest("ping"));
    }

    private ObjectNode baseRequest(String operation) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("v", properties.getProtocolVersion());
        request.put("id", UUID.randomUUID().toString());
        request.put("op", operation);
        return request;
    }

    private synchronized JsonNode send(ObjectNode request) {
        if (!isAvailable()) {
            throw new IllegalStateException("go fs worker is not available");
        }
        String id = request.path("id").asText();
        try {
            writer.write(MAPPER.writeValueAsString(request));
            writer.newLine();
            writer.flush();

            Future<String> lineFuture = readerExecutor.submit(reader::readLine);
            String line;
            try {
                line = lineFuture.get(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException ex) {
                lineFuture.cancel(true);
                throw new IOException("go fs worker request timed out", ex);
            }
            if (line == null) {
                throw new IOException("go fs worker closed stdout");
            }

            JsonNode response = MAPPER.readTree(line);
            if (!id.equals(response.path("id").asText())) {
                throw new IOException("go fs worker response id mismatch");
            }
            if (!response.path("ok").asBoolean(false)) {
                JsonNode error = response.path("error");
                String code = error.path("code").asText("WORKER_ERROR");
                String message = error.path("message").asText("worker returned an error");
                throw new IOException(code + ": " + message);
            }
            return response.path("data");
        } catch (IOException | ExecutionException ex) {
            markUnavailable(ex);
            throw new IllegalStateException("go fs request failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            markUnavailable(ex);
            throw new IllegalStateException("go fs request interrupted", ex);
        }
    }

    private List<WorkspaceEntry> entriesFromData(JsonNode data, Path root, WorkspacePathPolicy policy) {
        ArrayList<WorkspaceEntry> entries = new ArrayList<>();
        for (JsonNode node : data.path("entries")) {
            String rel = node.path("path").asText("");
            if (rel.isBlank() || rel.equals(".")) {
                continue;
            }
            Path absolute = root.resolve(rel.replace('/', File.separatorChar)).toAbsolutePath().normalize();
            if (!policy.isNotDenied(absolute)) {
                continue;
            }
            entries.add(entryFromNode(absolute, node, policy));
        }
        entries.sort(Comparator.comparing(entry -> entry.path().toLowerCase(Locale.ROOT)));
        return entries;
    }

    private WorkspaceEntry entryFromNode(Path absolute, JsonNode node, WorkspacePathPolicy policy) {
        String type = node.path("type").asText("other");
        boolean directory = type.equals("directory");
        boolean regularFile = type.equals("file");
        Instant modifiedAt;
        try {
            modifiedAt = Instant.parse(node.path("modifiedAt").asText());
        } catch (Exception ignored) {
            modifiedAt = Instant.EPOCH;
        }
        String displayPath = policy.displayPath(absolute);
        String name = absolute.getFileName() == null ? displayPath : absolute.getFileName().toString();
        return new WorkspaceEntry(displayPath, name, directory, regularFile, regularFile ? node.path("sizeBytes").asLong(0L) : 0L, modifiedAt);
    }

    private WorkerTarget rootedAtParent(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            return new WorkerTarget(normalized, ".");
        }
        return new WorkerTarget(parent, parent.relativize(normalized).toString());
    }

    private Path resolveWorkerPath() {
        return PlatformExecutables.resolveExecutable(pathPolicy.runtimeRoot(), properties.getWorkerPath())
                .orElseGet(() -> {
                    Path raw = Paths.get(properties.getWorkerPath());
                    Path resolved = raw.isAbsolute() ? raw : pathPolicy.runtimeRoot().resolve(raw);
                    return resolved.toAbsolutePath().normalize();
                });
    }

    private void markUnavailable(Exception ex) {
        available = false;
        if (unavailableLogged.compareAndSet(false, true)) {
            logService.append(OperatorLogLevel.WARN, "workspace", "go fs worker unavailable", Map.of(
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
        }
        close();
    }

    private Process launch(Path executable, File workingDirectory) throws Exception {
        try {
            Class<?> type = Class.forName("java.lang.Process" + "Builder");
            Object builder = type.getConstructor(String[].class).newInstance((Object) new String[]{executable.toString()});
            type.getMethod("directory", File.class).invoke(builder, workingDirectory);
            Class<?> redirectType = Class.forName("java.lang.Process" + "Builder$Redirect");
            Object inherit = redirectType.getField("INHERIT").get(null);
            type.getMethod("redirectError", redirectType).invoke(builder, inherit);
            return (Process) type.getMethod("start").invoke(builder);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw ex;
        }
    }

    private record WorkerTarget(Path root, String path) {
    }
}
