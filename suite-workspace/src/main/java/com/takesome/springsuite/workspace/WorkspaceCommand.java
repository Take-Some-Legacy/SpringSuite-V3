package com.takesome.springsuite.workspace;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceCommand implements SuiteCommand {
    private final WorkspaceService workspaceService;

    public WorkspaceCommand(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "workspace",
                List.of("ws", "files", "fs"),
                "workspace",
                "Browse, read, search and edit files inside configured workspace roots.",
                "Filesystem capability for agents and humans. Reads and writes are bounded by suite.workspace roots, deny-list, file-size limits and write/delete gates. Prefer REST write for large content; console write is for short text patches.",
                "workspace <summary|roots|list|tree|read|search|write|mkdir|delete> [args...]",
                CommandRiskLevel.LOCAL_MUTATION
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String action = invocation.arg(0).isBlank() ? "summary" : invocation.arg(0).trim().toLowerCase();
        return switch (action) {
            case "summary", "status", "help" -> CommandExecutionResult.ok("workspace summary", Map.of("workspace", workspaceService.summary()));
            case "roots" -> CommandExecutionResult.ok("workspace roots", Map.of("roots", workspaceService.summary().roots()));
            case "list", "ls" -> list(invocation);
            case "tree" -> tree(invocation);
            case "read", "cat" -> read(invocation);
            case "search", "grep" -> search(invocation);
            case "write" -> write(invocation);
            case "mkdir" -> mkdir(invocation);
            case "delete", "rm" -> delete(invocation);
            default -> CommandExecutionResult.failed("bad_workspace_action", "Unknown workspace action: " + action);
        };
    }

    private CommandExecutionResult list(CommandInvocation invocation) {
        String path = invocation.arg(1).isBlank() ? "." : invocation.arg(1);
        return CommandExecutionResult.ok("workspace list", Map.of("result", workspaceService.list(path, 100)));
    }

    private CommandExecutionResult tree(CommandInvocation invocation) {
        String path = invocation.arg(1).isBlank() ? "." : invocation.arg(1);
        int depth = parseInt(invocation.arg(2), 3);
        return CommandExecutionResult.ok("workspace tree", Map.of("result", workspaceService.tree(path, depth, 500)));
    }

    private CommandExecutionResult read(CommandInvocation invocation) {
        String path = invocation.arg(1);
        if (path.isBlank()) {
            return CommandExecutionResult.failed("missing_path", "usage: workspace read <path>");
        }
        return CommandExecutionResult.ok("workspace read", Map.of("result", workspaceService.read(path, 0, 65536)));
    }

    private CommandExecutionResult search(CommandInvocation invocation) {
        String query = invocation.arg(1);
        String path = invocation.arg(2).isBlank() ? "." : invocation.arg(2);
        if (query.isBlank()) {
            return CommandExecutionResult.failed("missing_query", "usage: workspace search <query> [path]");
        }
        return CommandExecutionResult.ok("workspace search", Map.of("result", workspaceService.search(query, path, 100, false, false)));
    }

    private CommandExecutionResult write(CommandInvocation invocation) {
        String path = invocation.arg(1);
        if (path.isBlank() || invocation.args().size() < 3) {
            return CommandExecutionResult.failed("bad_write_usage", "usage: workspace write <path> <content>");
        }
        String content = String.join(" ", invocation.args().subList(2, invocation.args().size()));
        WorkspaceWriteResult result = workspaceService.write(new WorkspaceWriteRequest(path, content, true, false, ""));
        return new CommandExecutionResult(result.ok(), result.ok() ? "ok" : "workspace_write_rejected", result.message(), Map.of("result", result), java.time.Instant.now());
    }

    private CommandExecutionResult mkdir(CommandInvocation invocation) {
        String path = invocation.arg(1);
        if (path.isBlank()) {
            return CommandExecutionResult.failed("missing_path", "usage: workspace mkdir <path>");
        }
        return CommandExecutionResult.ok("workspace mkdir", Map.of("result", workspaceService.mkdir(path, false)));
    }

    private CommandExecutionResult delete(CommandInvocation invocation) {
        String path = invocation.arg(1);
        if (path.isBlank()) {
            return CommandExecutionResult.failed("missing_path", "usage: workspace delete <path> [--recursive] [--dry-run]");
        }
        boolean recursive = invocation.args().contains("--recursive");
        boolean dryRun = invocation.args().contains("--dry-run");
        return CommandExecutionResult.ok("workspace delete", Map.of("result", workspaceService.delete(new WorkspaceDeleteRequest(path, recursive, dryRun))));
    }

    private int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
