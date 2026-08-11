package com.takesome.springsuite.module;

import java.util.List;

public record ModuleBuildRequest(String cwd, List<String> command, Integer timeoutSeconds) {
    public ModuleBuildRequest {
        command = command == null ? List.of() : List.copyOf(command);
        timeoutSeconds = timeoutSeconds == null ? 300 : timeoutSeconds;
    }
}
