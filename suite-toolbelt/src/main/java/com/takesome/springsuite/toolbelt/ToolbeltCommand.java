package com.takesome.springsuite.toolbelt;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ToolbeltCommand implements SuiteCommand {
    private final ToolbeltService toolbeltService;

    public ToolbeltCommand(ToolbeltService toolbeltService) {
        this.toolbeltService = toolbeltService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "toolbelt",
                List.of("tools", "tb"),
                "tools",
                "Inspect and operate the Suite toolbelt registry.",
                "Lists discovered descriptor tools and PATH tools, refreshes registry, shows metadata and can dry-run or execute tools if enabled.",
                "toolbelt <summary|list|info|refresh|dry-run|run> [toolId] [args...]",
                CommandRiskLevel.PROCESS_CONTROL
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "summary" : invocation.arg(0).trim().toLowerCase();
        return switch (action) {
            case "summary", "status" -> CommandExecutionResult.ok("toolbelt summary", Map.of("summary", toolbeltService.summary()));
            case "list", "ls" -> CommandExecutionResult.ok("tools: " + toolbeltService.listTools().size(), Map.of(
                    "tools", toolbeltService.listTools().stream().map(this::line).toList()
            ));
            case "info" -> info(invocation);
            case "refresh", "scan" -> CommandExecutionResult.ok("toolbelt refreshed", Map.of("summary", toolbeltService.refresh()));
            case "dry-run" -> run(invocation, true);
            case "run" -> run(invocation, false);
            default -> CommandExecutionResult.failed("bad_toolbelt_action", "Unknown toolbelt action: " + action);
        };
    }

    private CommandExecutionResult info(CommandInvocation invocation) {
        String id = invocation.arg(1);
        if (id.isBlank()) {
            return CommandExecutionResult.failed("missing_tool_id", "usage: toolbelt info <toolId>");
        }
        return toolbeltService.find(id)
                .map(tool -> CommandExecutionResult.ok(tool.id(), Map.of("tool", tool)))
                .orElseGet(() -> CommandExecutionResult.failed("tool_not_found", "Tool not found: " + id));
    }

    private CommandExecutionResult run(CommandInvocation invocation, boolean dryRun) {
        String id = invocation.arg(1);
        if (id.isBlank()) {
            return CommandExecutionResult.failed("missing_tool_id", dryRun
                    ? "usage: toolbelt dry-run <toolId> [args...]"
                    : "usage: toolbelt run <toolId> [args...]");
        }
        List<String> args = invocation.args().size() <= 2 ? List.of() : invocation.args().subList(2, invocation.args().size());
        ToolRunResult result = toolbeltService.run(new ToolRunRequest(id, args, "", "", 0, 12000, 8000, dryRun));
        return new CommandExecutionResult(result.ok(), result.ok() ? "ok" : "tool_failed", result.message(), Map.of("result", result), result.timestamp());
    }

    private String line(ToolDescriptor tool) {
        String state = tool.available() ? "available" : "missing";
        return tool.id() + " [" + tool.source() + "/" + tool.kind() + "/" + state + "] " + tool.description();
    }
}
