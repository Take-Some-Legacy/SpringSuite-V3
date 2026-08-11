package com.takesome.springsuite.workspace;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.ConsoleShellState;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.workspace.fs.WorkspacePathPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TouchCommand implements SuiteCommand {
    private final ConsoleShellState shellState;
    private final WorkspacePathPolicy pathPolicy;
    private final WorkspaceAccessGuard accessGuard;

    public TouchCommand(ConsoleShellState shellState, WorkspacePathPolicy pathPolicy, WorkspaceProperties properties) {
        this.shellState = shellState;
        this.pathPolicy = pathPolicy;
        this.accessGuard = new WorkspaceAccessGuard(properties);
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor("touch", List.of(), "unix", "Create a file or update timestamp.", "UNIX-like touch constrained by workspace policy.", "touch <path>", CommandRiskLevel.LOCAL_MUTATION);
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) throws Exception {
        if (invocation.arg(0).isBlank()) {
            return CommandExecutionResult.failed("missing_path", "usage: touch <path>");
        }
        accessGuard.ensureWrite();
        Path target = pathPolicy.resolveSafe(shellState.resolve(invocation.arg(0)));
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(target)) {
            Files.setLastModifiedTime(target, FileTime.from(Instant.now()));
        } else {
            Files.writeString(target, "");
        }
        return CommandExecutionResult.ok("touched", Map.of("path", pathPolicy.displayPath(target)));
    }
}
