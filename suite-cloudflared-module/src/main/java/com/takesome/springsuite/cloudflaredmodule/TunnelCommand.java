package com.takesome.springsuite.cloudflaredmodule;

import com.takesome.springsuite.cloudflared.CloudflaredTunnelService;
import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TunnelCommand implements SuiteCommand {
    private final CloudflaredTunnelService tunnelService;

    public TunnelCommand(CloudflaredTunnelService tunnelService) {
        this.tunnelService = tunnelService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "tunnel",
                List.of("cf", "cloudflared"),
                "process",
                "Control cloudflared tunnel.",
                "Supports tunnel status/start/stop/restart/logs.",
                "tunnel <status|start|stop|restart|logs> [limit]",
                CommandRiskLevel.PROCESS_CONTROL
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "status" : invocation.arg(0).trim().toLowerCase();
        return switch (action) {
            case "status", "st" -> CommandExecutionResult.ok("cloudflared status", Map.of("status", tunnelService.status()));
            case "start" -> CommandExecutionResult.ok("cloudflared start requested", Map.of("status", tunnelService.start()));
            case "stop" -> CommandExecutionResult.ok("cloudflared stop requested", Map.of("status", tunnelService.stop()));
            case "restart" -> CommandExecutionResult.ok("cloudflared restart requested", Map.of("status", tunnelService.restart()));
            case "logs" -> CommandExecutionResult.ok("cloudflared recent logs", Map.of("logs", tunnelService.recentLogs(parseLimit(invocation.arg(1), 40))));
            default -> CommandExecutionResult.failed("bad_tunnel_action", "Unknown tunnel action: " + action);
        };
    }

    private int parseLimit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(300, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
