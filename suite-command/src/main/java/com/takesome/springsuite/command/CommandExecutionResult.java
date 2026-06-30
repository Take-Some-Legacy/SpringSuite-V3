package com.takesome.springsuite.command;

import java.time.Instant;
import java.util.Map;

public record CommandExecutionResult(
        boolean ok,
        String code,
        String message,
        Map<String, Object> data,
        Instant timestamp
) {
    public CommandExecutionResult {
        code = code == null || code.isBlank() ? (ok ? "ok" : "failed") : code.trim();
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static CommandExecutionResult ok(String message) {
        return new CommandExecutionResult(true, "ok", message, Map.of(), Instant.now());
    }

    public static CommandExecutionResult ok(String message, Map<String, Object> data) {
        return new CommandExecutionResult(true, "ok", message, data, Instant.now());
    }

    public static CommandExecutionResult failed(String code, String message) {
        return new CommandExecutionResult(false, code, message, Map.of(), Instant.now());
    }
}
