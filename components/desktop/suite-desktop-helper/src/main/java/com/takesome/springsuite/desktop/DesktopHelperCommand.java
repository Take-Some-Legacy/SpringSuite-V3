package com.takesome.springsuite.desktop;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DesktopHelperCommand implements SuiteCommand {
    private final DesktopHelperService desktopHelperService;

    public DesktopHelperCommand(DesktopHelperService desktopHelperService) {
        this.desktopHelperService = desktopHelperService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "desktop-helper",
                List.of("desktop", "dh"),
                "desktop",
                "Inspect the desktop helper AI suite.",
                "Shows desktop-helper status, integration schema and safe surface configuration.",
                "desktop-helper [status|schema|surfaces|endpoints]",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String subcommand = invocation.arg(0).isBlank() ? "status" : invocation.arg(0).toLowerCase();
        return switch (subcommand) {
            case "schema" -> CommandExecutionResult.ok("desktop helper schema", Map.of("schema", desktopHelperService.schema()));
            case "surfaces" -> CommandExecutionResult.ok("desktop helper surfaces", Map.of("surfaces", desktopHelperService.schema().surfaces()));
            case "endpoints" -> CommandExecutionResult.ok("desktop helper endpoints", Map.of("endpoints", desktopHelperService.status().endpoints()));
            case "status" -> CommandExecutionResult.ok("desktop helper status", Map.of("status", desktopHelperService.status()));
            default -> CommandExecutionResult.failed("unknown_subcommand", "Usage: " + descriptor().usage());
        };
    }
}
