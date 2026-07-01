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
public class LsCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public LsCommand(WorkspaceService workspaceService, ConsoleShellState shellState) { super(workspaceService, shellState); }
    @Override
    public CommandDescriptor descriptor() { return new CommandDescriptor("ls", List.of("dir"), "unix", "List directory entries.", "UNIX-like facade over workspace list.", "ls [path] [limit]", CommandRiskLevel.READ_ONLY); }
    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        WorkspaceListResult result = workspaceService.list(path(invocation, 0, "."), parseInt(invocation.arg(1), 100, 1, 10000));
        StringBuilder out = new StringBuilder();
        for (WorkspaceEntry entry : result.entries()) {
            out.append(entry.directory() ? "d " : "- ").append(entry.sizeBytes()).append(' ').append(entry.name());
            if (entry.directory()) { out.append('/'); }
            out.append(System.lineSeparator());
        }
        if (result.truncated()) { out.append("... truncated").append(System.lineSeparator()); }
        return stdout(out.toString());
    }
}
