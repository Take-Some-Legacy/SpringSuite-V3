package com.takesome.springsuite.workspace;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.ConsoleShellState;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RmCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public RmCommand(WorkspaceService workspaceService, ConsoleShellState shellState) { super(workspaceService, shellState); }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("rm", List.of("del", "unlink"), "unix", "Delete a workspace path.", "UNIX-like facade over workspace delete.", "rm [-r|--recursive] [--dry-run] <path>", CommandRiskLevel.LOCAL_MUTATION);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String target = "";
        boolean recursive = false;
        boolean dryRun = false;
        for (String arg : invocation.args()) {
            if (arg.equals("-r") || arg.equals("-R") || arg.equals("--recursive") || arg.equals("-rf") || arg.equals("-fr")) { recursive = true; continue; }
            if (arg.equals("--dry-run")) { dryRun = true; continue; }
            if (!arg.startsWith("-") && target.isBlank()) { target = arg; }
        }
        if (target.isBlank()) { return CommandExecutionResult.failed("missing_path", "usage: rm [-r] [--dry-run] <path>"); }
        WorkspaceMutationResult result = workspaceService.delete(new WorkspaceDeleteRequest(shellState.resolve(target), recursive, dryRun));
        return new CommandExecutionResult(result.ok(), result.ok() ? "ok" : "rm_failed", result.message(), Map.of("result", result), java.time.Instant.now());
    }
}
