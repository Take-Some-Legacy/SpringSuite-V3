package com.takesome.springsuite.command.builtin;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StatusCommand implements SuiteCommand {
    private final Instant startedAt = Instant.now();
    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "status",
                List.of("st"),
                "core",
                "Show SpringSuite runtime status.",
                "Prints process, runtime-root and config/log paths.",
                "status",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        return CommandExecutionResult.ok("SpringSuite READY", Map.of(
                "pid", ManagementFactory.getRuntimeMXBean().getPid(),
                "uptimeSeconds", Duration.between(startedAt, Instant.now()).toSeconds(),
                "java", Runtime.version().toString(),
                "launchRoot", System.getProperty("suite.project.root", ""),
                "configPath", System.getProperty("suite.config.path", ""),
                "logsPath", System.getProperty("suite.logs.path", "")
        ));
    }
}
