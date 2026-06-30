package com.takesome.springsuite.agent;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AuthCommand implements SuiteCommand {
    private final SuiteAuthService authService;

    public AuthCommand(SuiteAuthService authService) {
        this.authService = authService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "auth",
                List.of("oauth", "token"),
                "agent",
                "Inspect and manage SpringSuite agent authorization.",
                "Generates and rotates the machine-local bridge token compatible with Python NorthStar-Suite and reports OAuth endpoint/status metadata. Use 'auth token show' only in a trusted local console.",
                "auth <status|token|token show|token rotate|token rotate show>",
                CommandRiskLevel.LOCAL_MUTATION
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "status" : invocation.arg(0).trim().toLowerCase();
        if (action.equals("status") || action.equals("oauth")) {
            return CommandExecutionResult.ok("auth status", Map.of("auth", authService.status()));
        }
        if (action.equals("token")) {
            String sub = invocation.arg(1).trim().toLowerCase();
            boolean show = sub.equals("show") || invocation.arg(2).trim().equalsIgnoreCase("show");
            boolean rotate = sub.equals("rotate");
            BridgeTokenResult result = rotate ? authService.rotateBridgeToken(show) : authService.ensureBridgeToken(show);
            return CommandExecutionResult.ok(rotate ? "bridge token rotated" : "bridge token ready", Map.of("token", result));
        }
        return CommandExecutionResult.failed("bad_auth_action", "Unknown auth action: " + action);
    }
}
