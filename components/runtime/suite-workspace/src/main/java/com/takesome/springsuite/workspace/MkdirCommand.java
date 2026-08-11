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
public class MkdirCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public MkdirCommand(WorkspaceService workspaceService, ConsoleShellState shellState) { super(workspaceService, shellState); }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("mkdir", List.of("md"), "unix", "Create a directory.", "UNIX-like facade over workspace mkdir.", "mkdir <path>", CommandRiskLevel.LOCAL_MUTATION);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        if (invocation.arg(0).isBlank()) { return CommandExecutionResult.failed("missing_path", "usage: mkdir <path>"); }
        WorkspaceMutationResult result = workspaceService.mkdir(path(invocation, 0, "."), false);
        return new CommandExecutionResult(result.ok(), result.ok() ? "ok" : "mkdir_failed", result.message(), Map.of("result", result), java.time.Instant.now());
    }
}
