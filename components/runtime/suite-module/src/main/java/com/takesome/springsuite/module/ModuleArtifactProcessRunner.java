package com.takesome.springsuite.module;

import com.takesome.springsuite.core.process.ManagedProcess;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ModuleArtifactProcessRunner {
    ModuleArtifactResult run(List<String> command, Path cwd, int timeoutSeconds, String path, boolean redact) {
        ManagedProcess managedProcess = null;
        ExecutorService outputExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "suite-module-artifact-output");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            managedProcess = ManagedProcess.start(
                    builder,
                    "module-artifact:" + path,
                    Duration.ofMillis(250),
                    Duration.ofSeconds(3)
            );
            Process process = managedProcess.process();
            process.getOutputStream().close();
            Future<String> stdout = outputExecutor.submit(() -> readBounded(process.getInputStream(), 8_000));
            Future<String> stderr = outputExecutor.submit(() -> readBounded(process.getErrorStream(), 4_000));
            boolean done = timeoutSeconds <= 0
                    ? waitWithoutTimeout(process)
                    : process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                managedProcess.terminate(Duration.ofMillis(250), Duration.ofSeconds(3));
                return new ModuleArtifactResult(
                        false,
                        "process timed out",
                        path,
                        preview(command, redact),
                        null,
                        getOutput(stdout),
                        getOutput(stderr)
                );
            }
            managedProcess.complete();
            int exit = process.exitValue();
            return new ModuleArtifactResult(
                    exit == 0,
                    exit == 0 ? "ok" : "non-zero exit",
                    path,
                    preview(command, redact),
                    exit,
                    stdout.get(),
                    stderr.get()
            );
        } catch (Exception ex) {
            if (managedProcess != null) {
                managedProcess.terminate(Duration.ofMillis(100), Duration.ofSeconds(3));
            }
            return new ModuleArtifactResult(
                    false,
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    path,
                    preview(command, redact),
                    null,
                    "",
                    ""
            );
        } finally {
            if (managedProcess != null && managedProcess.isAlive()) {
                managedProcess.terminate(Duration.ofMillis(100), Duration.ofSeconds(3));
            }
            outputExecutor.shutdownNow();
        }
    }

    private boolean waitWithoutTimeout(Process process) throws InterruptedException {
        process.waitFor();
        return true;
    }

    private String getOutput(Future<String> output) {
        try {
            return output.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            return "";
        }
    }

    private List<String> preview(List<String> command, boolean redact) {
        if (!redact) {
            return command;
        }
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < command.size(); i++) {
            String value = command.get(i);
            out.add(value);
            if ((value.equals("-storepass") || value.equals("-keypass")) && i + 1 < command.size()) {
                out.add("***");
                i++;
            }
        }
        return out;
    }

    private String readBounded(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        byte[] buffer = new byte[4 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            int allowed = Math.min(read, Math.max(0, maxBytes - total));
            if (allowed > 0) {
                out.write(buffer, 0, allowed);
                total += allowed;
            }
            // Continue draining after the capture limit to avoid pipe deadlocks.
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
