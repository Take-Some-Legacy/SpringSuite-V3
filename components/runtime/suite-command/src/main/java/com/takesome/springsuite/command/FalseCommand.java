package com.takesome.springsuite.command;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FalseCommand implements SuiteCommand {
    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("false", List.of(), "unix", "Return failure.", "UNIX-like no-op failure command for shell scripts.", "false", CommandRiskLevel.READ_ONLY);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        return CommandExecutionResult.failed("false", "");
    }
}
