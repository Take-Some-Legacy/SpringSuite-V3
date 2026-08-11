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
public class GrepCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public GrepCommand(WorkspaceService workspaceService, ConsoleShellState shellState) { super(workspaceService, shellState); }
    @Override
    public CommandDescriptor descriptor() { return new CommandDescriptor("grep", List.of(), "unix", "Search text files.", "UNIX-like facade over workspace search.", "grep <query> [path]", CommandRiskLevel.READ_ONLY); }
    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        String query = invocation.arg(0);
        if (query.isBlank()) { return CommandExecutionResult.failed("missing_query", "usage: grep <query> [path]"); }
        WorkspaceSearchResult result = workspaceService.search(query, path(invocation, 1, "."), 100, false, false);
        StringBuilder out = new StringBuilder();
        for (WorkspaceSearchMatch match : result.matches()) {
            out.append(match.path()).append(':').append(match.lineNumber()).append(':').append(match.line()).append(System.lineSeparator());
        }
        if (result.truncated()) { out.append("... truncated").append(System.lineSeparator()); }
        return stdout(out.toString());
    }
}
