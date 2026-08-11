package com.takesome.springsuite.command;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ClearCommand implements SuiteCommand {
    private static final String CLEAR_SCREEN = "\033[2J\033[H";

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "clear",
                List.of("cls", "reset-screen"),
                "console",
                "Clear the terminal screen.",
                "Core CLI command that prints ANSI clear-screen and cursor-home escape sequences, then returns to the main CLI prompt.",
                "clear",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        System.out.print(CLEAR_SCREEN);
        System.out.flush();
        return CommandExecutionResult.ok("screen cleared", Map.of(
                "ansi", "ESC[2J ESC[H",
                "scope", "core",
                "_consoleSilent", CommandExecutionContext.isConsole()
        ));
    }
}
