package com.takesome.springsuite.command.builtin;

import com.takesome.springsuite.command.CommandDescriptor;
import com.takesome.springsuite.command.CommandExecutionResult;
import com.takesome.springsuite.command.CommandInvocation;
import com.takesome.springsuite.command.CommandRiskLevel;
import com.takesome.springsuite.command.SuiteCommand;
import com.takesome.springsuite.logging.OperatorLogEntry;
import com.takesome.springsuite.logging.OperatorLogService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LogsCommand implements SuiteCommand {
    private final OperatorLogService logService;

    public LogsCommand(OperatorLogService logService) {
        this.logService = logService;
    }

    @Override
    public CommandDescriptor descriptor() {
        return new CommandDescriptor(
                "logs",
                List.of("log"),
                "diagnostics",
                "Show recent operator log entries.",
                "Reads the in-memory operator log ring buffer.",
                "logs [limit]",
                CommandRiskLevel.READ_ONLY
        );
    }

    @Override
    public CommandExecutionResult execute(CommandInvocation invocation) {
        int limit = parseLimit(invocation.arg(0), 10);
        List<String> lines = logService.recent(limit).stream()
                .map(this::line)
                .toList();
        return CommandExecutionResult.ok("Recent logs: " + lines.size(), Map.of("entries", lines));
    }

    private String line(OperatorLogEntry entry) {
        return entry.timestamp() + " " + entry.level() + " [" + entry.source() + "] " + entry.message();
    }

    private int parseLimit(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Math.min(200, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
