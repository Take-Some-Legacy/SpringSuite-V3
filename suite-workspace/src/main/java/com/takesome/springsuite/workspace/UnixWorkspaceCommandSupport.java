package com.takesome.springsuite.workspace;

import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.ConsoleShellState;
import java.time.Instant;
import java.util.Map;

abstract class UnixWorkspaceCommandSupport {
    protected final WorkspaceService workspaceService;
    protected final ConsoleShellState shellState;

    UnixWorkspaceCommandSupport(WorkspaceService workspaceService, ConsoleShellState shellState) {
        this.workspaceService = workspaceService;
        this.shellState = shellState;
    }

    protected String path(CommandInvocation invocation, int index, String fallback) {
        String value = invocation.arg(index);
        return shellState.resolve(value == null || value.isBlank() ? fallback : value);
    }

    protected CommandExecutionResult stdout(String text) {
        return new CommandExecutionResult(true, "ok", "", Map.of("_stdout", text == null ? "" : text, "_consoleRaw", true), Instant.now());
    }

    protected int parseInt(String raw, int fallback, int min, int max) {
        try {
            int value = raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw.trim());
            return Math.max(min, Math.min(max, value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
