package com.takesome.springsuite.module;

import java.util.List;

public record ModuleArtifactResult(boolean ok, String message, String path, List<String> command, Integer exitCode, String stdout, String stderr) {
    public ModuleArtifactResult {
        message = message == null ? "" : message;
        path = path == null ? "" : path;
        command = command == null ? List.of() : List.copyOf(command);
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
    }
}
