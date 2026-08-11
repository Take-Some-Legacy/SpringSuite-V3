package com.takesome.springsuite.command;

import com.takesome.springsuite.core.mode.SuiteOperatorMode;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class UnameCommand implements SuiteCommand {
    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("uname", List.of("whoami"), "unix", "Print suite/runtime identity.", "UNIX-like system identity command for SpringSuite.", "uname [-a]", CommandRiskLevel.READ_ONLY);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String value = "SpringSuite " + System.getProperty("suite.version", "unknown")
                + " build=" + System.getProperty("suite.build", "unknown")
                + " java=" + Runtime.version()
                + " pid=" + ManagementFactory.getRuntimeMXBean().getPid()
                + " operatorMode=" + SuiteOperatorMode.name()
                + " operatorSource=" + SuiteOperatorMode.source();
        return new CommandExecutionResult(true, "ok", "", Map.of("_stdout", value + System.lineSeparator(), "identity", value), Instant.now());
    }
}
