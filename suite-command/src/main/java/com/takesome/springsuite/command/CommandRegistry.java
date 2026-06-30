package com.takesome.springsuite.command;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class CommandRegistry {
    private final Map<String, SuiteCommand> commandsByName = new LinkedHashMap<>();
    private final List<SuiteCommand> commands = new ArrayList<>();
    private final OperatorLogService logService;

    public CommandRegistry(List<SuiteCommand> commands, OperatorLogService logService) {
        this.logService = logService;
        commands.stream()
                .sorted(Comparator.comparing(command -> command.descriptor().name()))
                .forEach(this::register);
    }

    public List<CommandDescriptor> descriptors() {
        return commands.stream()
                .map(SuiteCommand::descriptor)
                .sorted(Comparator.comparing(CommandDescriptor::name))
                .toList();
    }

    public CommandExecutionResult executeRaw(String rawLine) {
        List<String> tokens = CommandTokenizer.tokenize(rawLine);
        if (tokens.isEmpty()) {
            return CommandExecutionResult.ok("empty command ignored");
        }

        String commandName = normalize(tokens.get(0));
        SuiteCommand command = commandsByName.get(commandName);
        if (command == null) {
            return CommandExecutionResult.failed("unknown_command", "Unknown command: " + commandName + ". Run: help");
        }

        CommandInvocation invocation = new CommandInvocation(rawLine, commandName, tokens.subList(1, tokens.size()), Instant.now());
        try {
            CommandExecutionResult result = command.execute(invocation);
            logService.append(result.ok() ? OperatorLogLevel.INFO : OperatorLogLevel.WARN,
                    "command",
                    rawLine,
                    Map.of("ok", result.ok(), "code", result.code(), "command", command.descriptor().name()));
            return result;
        } catch (Exception ex) {
            logService.append(OperatorLogLevel.ERROR, "command", "command failed", Map.of(
                    "line", rawLine,
                    "command", command.descriptor().name(),
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
            return CommandExecutionResult.failed("command_exception", ex.getMessage() == null
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage());
        }
    }

    public SuiteCommand find(String nameOrAlias) {
        return commandsByName.get(normalize(nameOrAlias));
    }

    public synchronized void register(SuiteCommand command) {
        CommandDescriptor descriptor = command.descriptor();
        put(descriptor.name(), command);
        for (String alias : descriptor.aliases()) {
            put(alias, command);
        }
        if (!commands.contains(command)) {
            commands.add(command);
            commands.sort(Comparator.comparing(item -> item.descriptor().name()));
        }
    }

    private void put(String name, SuiteCommand command) {
        String normalized = normalize(name);
        if (normalized.isBlank()) {
            return;
        }
        SuiteCommand previous = commandsByName.putIfAbsent(normalized, command);
        if (previous != null && previous != command) {
            throw new IllegalStateException("Duplicate command name or alias: " + normalized);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
