package com.takesome.springsuite.workspace;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.ConsoleShellState;
import com.takesome.springsuite.command.SuiteCommand;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CatCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public CatCommand(WorkspaceService workspaceService, ConsoleShellState shellState) { super(workspaceService, shellState); }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("cat", List.of("type"), "unix", "Print a text file.", "UNIX-like facade over workspace read.", "cat <path>", CommandRiskLevel.READ_ONLY);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        if (invocation.arg(0).isBlank()) { return CommandExecutionResult.failed("missing_path", "usage: cat <path>"); }
        WorkspaceReadResult result = workspaceService.read(path(invocation, 0, "."), 0, Integer.MAX_VALUE);
        return stdout(result.content());
    }
}
