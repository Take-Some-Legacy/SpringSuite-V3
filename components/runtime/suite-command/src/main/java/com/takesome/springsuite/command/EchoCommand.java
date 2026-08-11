package com.takesome.springsuite.command;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class EchoCommand implements SuiteCommand {
    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("echo", List.of(), "unix", "Print arguments.", "UNIX-like echo command for console scripts.", "echo [text...]", CommandRiskLevel.READ_ONLY);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String text = String.join(" ", invocation.args()) + System.lineSeparator();
        return new CommandExecutionResult(true, "ok", "", Map.of("_stdout", text, "_consoleRaw", true), Instant.now());
    }
}
