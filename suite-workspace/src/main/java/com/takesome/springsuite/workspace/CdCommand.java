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
public class CdCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public CdCommand(WorkspaceService workspaceService, ConsoleShellState shellState) {
        super(workspaceService, shellState);
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("cd", List.of("chdir"), "unix", "Change current shell working directory.", "Moves the virtual shell directory inside workspace policy boundaries.", "cd [path]", CommandRiskLevel.READ_ONLY);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String target = path(invocation, 0, ".");
        WorkspaceListResult listed = workspaceService.list(target, 1);
        shellState.changeDirectory(listed.path());
        return CommandExecutionResult.ok("directory changed", Map.of("cwd", shellState.currentDirectory()));
    }
}
