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
public class TreeCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public TreeCommand(WorkspaceService workspaceService, ConsoleShellState shellState) { super(workspaceService, shellState); }
    @Override
    public CommandDescriptor descriptor() { return new CommandDescriptor("tree", List.of(), "unix", "Show a recursive workspace tree.", "UNIX-like facade over workspace tree.", "tree [path] [depth] [limit]", CommandRiskLevel.READ_ONLY); }
    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        WorkspaceListResult result = workspaceService.tree(path(invocation, 0, "."), parseInt(invocation.arg(1), 3, 0, 64), parseInt(invocation.arg(2), 500, 1, 100000));
        StringBuilder out = new StringBuilder(result.path()).append(System.lineSeparator());
        for (WorkspaceEntry entry : result.entries()) {
            out.append("  ").append(entry.path());
            if (entry.directory()) { out.append('/'); }
            out.append(System.lineSeparator());
        }
        if (result.truncated()) { out.append("... truncated").append(System.lineSeparator()); }
        return stdout(out.toString());
    }
}
