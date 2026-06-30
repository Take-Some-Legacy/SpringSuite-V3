package com.takesome.springsuite.command.builtin;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.ConsoleCommandProperties;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
public class ExitCommand implements SuiteCommand {
    private final ConfigurableApplicationContext applicationContext;
    private final ConsoleCommandProperties properties;

    public ExitCommand(ConfigurableApplicationContext applicationContext, ConsoleCommandProperties properties) {
        this.applicationContext = applicationContext;
        this.properties = properties;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "exit",
                List.of("quit", "shutdown"),
                "process",
                "Shutdown SpringSuite.",
                "Gracefully closes the Spring application context if allowed by config.",
                "exit",
                CommandRiskLevel.SHUTDOWN
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        if (!properties.isAllowShutdown()) {
            return CommandExecutionResult.failed("shutdown_disabled", "Console shutdown command is disabled by config");
        }
        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            applicationContext.close();
        }, "suite-console-shutdown");
        shutdownThread.setDaemon(false);
        shutdownThread.start();
        return CommandExecutionResult.ok("SpringSuite shutdown scheduled");
    }
}
