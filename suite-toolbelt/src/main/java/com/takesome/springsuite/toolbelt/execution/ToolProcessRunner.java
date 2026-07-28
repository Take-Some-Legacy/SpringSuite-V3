package com.takesome.springsuite.toolbelt.execution;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
import com.takesome.springsuite.toolbelt.DescriptorToolRuntime;
import com.takesome.springsuite.toolbelt.ToolDescriptor;
import com.takesome.springsuite.toolbelt.ToolRunRequest;
import com.takesome.springsuite.toolbelt.ToolRunResult;
import com.takesome.springsuite.toolbelt.ToolbeltProperties;
import com.takesome.springsuite.toolbelt.support.ToolbeltPaths;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ToolProcessRunner {
    private final ToolbeltProperties properties;
    private final DescriptorToolRuntime descriptorRuntime;

    public ToolProcessRunner(ToolbeltProperties properties, DescriptorToolRuntime descriptorRuntime) {
        this.properties = properties;
        this.descriptorRuntime = descriptorRuntime;
    }

    public ToolRunResult run(ToolDescriptor descriptor, ToolRunRequest request) {
        if (descriptor == null) {
            return failed(request.toolId(), List.of(), "", "unknown tool: " + request.toolId(), request.dryRun());
        }
        if (!SuiteOperatorMode.isElevated() && !properties.isAllowExecution() && !request.dryRun()) {
            return failed(descriptor.id(), descriptor.commandTemplate(), "", "tool execution disabled by suite.toolbelt.allow-execution=false", false);
        }
        if (!descriptor.available()) {
            return failed(descriptor.id(), descriptor.commandTemplate(), "", descriptor.availabilityMessage(), request.dryRun());
        }

        List<String> command = descriptorRuntime.buildRuntimeCommand(descriptor, request.args());
        Path cwd = request.cwd().isBlank() ? ToolbeltPaths.runtimeRoot() : ToolbeltPaths.resolveRuntimePath(request.cwd());
        if (command.isEmpty() || descriptorRuntime.isDescriptorSentinel(command.get(0))) {
            return failed(descriptor.id(), command, cwd.toString(), "descriptor tool executable is not resolved", request.dryRun());
        }

        if (request.dryRun()) {
            return new ToolRunResult(true, descriptor.id(), command, cwd.toString(), null, 0, "", "", "dry run", true, Instant.now());
        }

        Instant start = Instant.now();
        long started = System.nanoTime();
        Process process = null;
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "suite-toolbelt-output-reader");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(cwd.toFile());
            process = builder.start();
            Process runningProcess = process;
            if (!request.stdin().isBlank()) {
                try (OutputStream input = process.getOutputStream()) {
                    input.write(request.stdin().getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }

            int stdoutLimit = effectiveLimit(request.maxStdoutBytes(), properties.getMaxStdoutBytes());
            int stderrLimit = effectiveLimit(request.maxStderrBytes(), properties.getMaxStderrBytes());
            Future<String> stdout = executor.submit(() -> readBounded(runningProcess.getInputStream(), stdoutLimit));
            Future<String> stderr = executor.submit(() -> readBounded(runningProcess.getErrorStream(), stderrLimit));

            long timeoutSec = request.timeoutSec() > 0 ? request.timeoutSec() : properties.getDefaultTimeout().toSeconds();
            boolean finished;
            if (timeoutSec <= 0) {
                process.waitFor();
                finished = true;
            } else {
                finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            }
            if (!finished) {
                process.destroyForcibly();
                return new ToolRunResult(false, descriptor.id(), command, cwd.toString(), null, elapsedMs(started),
                        stdout.get(2, TimeUnit.SECONDS), stderr.get(2, TimeUnit.SECONDS), "tool timed out", false, start);
            }
            int exit = process.exitValue();
            return new ToolRunResult(exit == 0, descriptor.id(), command, cwd.toString(), exit, elapsedMs(started),
                    stdout.get(), stderr.get(), exit == 0 ? "ok" : "non-zero exit", false, start);
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return failed(descriptor.id(), command, cwd.toString(), ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), false);
        } finally {
            executor.shutdownNow();
        }
    }

    private ToolRunResult failed(String toolId, List<String> command, String cwd, String message, boolean dryRun) {
        return new ToolRunResult(false, toolId == null ? "" : toolId, command, cwd, null, 0, "", "", message, dryRun, Instant.now());
    }

    private long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String readBounded(java.io.InputStream input, int maxBytes) throws IOException {
        int initialCapacity = maxBytes > 0 ? Math.min(maxBytes, 64 * 1024) : 64 * 1024;
        ByteArrayOutputStream out = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int allowed = maxBytes <= 0 ? read : Math.min(read, Math.max(0, maxBytes - total));
            if (allowed > 0) {
                out.write(buffer, 0, allowed);
                total += allowed;
            }
            // Always drain the pipe after the capture cap is reached, otherwise child processes can deadlock.
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int effectiveLimit(int requested, int configured) {
        if (requested > 0) {
            return configured > 0 ? Math.min(requested, configured) : requested;
        }
        return configured > 0 ? configured : 0;
    }
}
