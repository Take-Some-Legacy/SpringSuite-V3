package com.takesome.springsuite.command;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DateCommand implements SuiteCommand {
    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("date", List.of("now"), "unix", "Print current time.", "UNIX-like date command for the console shell.", "date", CommandRiskLevel.READ_ONLY);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String value = Instant.now().toString();
        return new CommandExecutionResult(true, "ok", "", Map.of("_stdout", value + System.lineSeparator(), "instant", value), Instant.now());
    }
}
