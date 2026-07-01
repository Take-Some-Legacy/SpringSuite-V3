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
public class PwdCommand extends UnixWorkspaceCommandSupport implements SuiteCommand {
    public PwdCommand(WorkspaceService workspaceService, ConsoleShellState shellState) { super(workspaceService, shellState); }
    @Override
    public CommandDescriptor descriptor() { return new CommandDescriptor("pwd", List.of(), "unix", "Print current shell working directory.", "Shows the current virtual workspace directory used by UNIX-like commands.", "pwd", CommandRiskLevel.READ_ONLY); }
    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) { return stdout(shellState.currentDirectory() + System.lineSeparator()); }
}
