package com.takesome.springsuite.command;

import java.time.Instant;
import java.util.List;

public record CommandInvocation(
        String rawLine,
        String commandName,
        List<String> args,
        Instant timestamp
) {
    public CommandInvocation {
        rawLine = rawLine == null ? "" : rawLine;
        commandName = commandName == null ? "" : commandName.trim().toLowerCase();
        args = args == null ? List.of() : List.copyOf(args);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public String arg(int index) {
        return index < 0 || index >= args.size() ? "" : args.get(index);
    }
}
