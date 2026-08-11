package com.takesome.springsuite.command;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class TrueCommand implements SuiteCommand {
    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("true", List.of(), "unix", "Return success.", "UNIX-like no-op success command for shell scripts.", "true", CommandRiskLevel.READ_ONLY);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        return CommandExecutionResult.ok("");
    }
}
