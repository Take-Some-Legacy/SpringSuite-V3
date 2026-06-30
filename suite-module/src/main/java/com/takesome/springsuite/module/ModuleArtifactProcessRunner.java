package com.takesome.springsuite.module;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class ModuleArtifactProcessRunner {
    ModuleArtifactResult run(List<String> command, Path cwd, int timeoutSeconds, String path, boolean redact) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (cwd != null) {
                builder.directory(cwd.toFile());
            }
            process = builder.start();
            process.getOutputStream().close();
            boolean done = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new ModuleArtifactResult(false, "process timed out", path, preview(command, redact), null, "", "");
            }
            String stdout = readBounded(process.getInputStream(), 8000);
            String stderr = readBounded(process.getErrorStream(), 4000);
            return new ModuleArtifactResult(process.exitValue() == 0, process.exitValue() == 0 ? "ok" : "non-zero exit", path, preview(command, redact), process.exitValue(), stdout, stderr);
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new ModuleArtifactResult(false, ex.getMessage(), path, preview(command, redact), null, "", "");
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0 && total < maxBytes) {
            int allowed = Math.min(read, maxBytes - total);
            out.write(buffer, 0, allowed);
            total += allowed;
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
