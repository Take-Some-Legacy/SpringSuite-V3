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
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class CommandRegistry {
    private final Map<String, SuiteCommand> commandsByName = new LinkedHashMap<>();
    private final List<SuiteCommand> commands = new ArrayList<>();
    private final AtomicInteger activeExecutions = new AtomicInteger();
    private final OperatorLogService logService;

    public CommandRegistry(List<SuiteCommand> commands, OperatorLogService logService) {
        this.logService = logService;
        commands.stream()
                .sorted(Comparator.comparing(command -> command.descriptor().name()))
                .forEach(this::register);
    }

    public synchronized List<CommandDescriptor> descriptors() {
        return commands.stream()
                .map(SuiteCommand::descriptor)
                .sorted(Comparator.comparing(CommandDescriptor::name))
                .toList();
    }

    public int activeExecutions() {
        return activeExecutions.get();
    }


    public CommandExecutionResult executeScript(String rawLine) {
        List<CommandScriptParser.Step> steps = CommandScriptParser.parse(rawLine);
        if (steps.isEmpty()) {
            return CommandExecutionResult.ok("empty command ignored");
        }
        if (steps.size() == 1) {
            return executeRaw(steps.get(0).command());
        }

        ArrayList<Map<String, Object>> stepResults = new ArrayList<>();
        StringBuilder stdout = new StringBuilder();
        boolean previousOk = true;
        CommandExecutionResult last = CommandExecutionResult.ok("script initialized");
        for (CommandScriptParser.Step step : steps) {
            if (step.operator() == CommandScriptParser.Operator.ON_SUCCESS && !previousOk) {
                LinkedHashMap<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("command", step.command());
                skipped.put("skipped", true);
                skipped.put("reason", "previous command failed");
                stepResults.add(skipped);
                continue;
            }
            if (step.operator() == CommandScriptParser.Operator.ON_FAILURE && previousOk) {
                LinkedHashMap<String, Object> skipped = new LinkedHashMap<>();
                skipped.put("command", step.command());
                skipped.put("skipped", true);
                skipped.put("reason", "previous command succeeded");
                stepResults.add(skipped);
                continue;
            }
            last = executeRaw(step.command());
            previousOk = last.ok();
            Object stepStdout = last.data().get("_stdout");
            if (stepStdout != null) {
                stdout.append(stepStdout);
                if (!stdout.toString().endsWith(System.lineSeparator())) {
                    stdout.append(System.lineSeparator());
                }
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<>();
            item.put("command", step.command());
            item.put("ok", last.ok());
            item.put("code", last.code());
            item.put("message", last.message());
            stepResults.add(item);
        }
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        data.put("steps", stepResults);
        if (!stdout.isEmpty()) {
            data.put("_stdout", stdout.toString());
            data.put("_consoleRaw", true);
        }
        return new CommandExecutionResult(
                last.ok(),
                last.ok() ? "ok" : "script_failed",
                last.ok() ? "script complete" : "script failed",
                data,
                Instant.now()
        );
    }
    public CommandExecutionResult executeRaw(String rawLine) {
        List<String> tokens = CommandTokenizer.tokenize(rawLine);
        if (tokens.isEmpty()) {
            return CommandExecutionResult.ok("empty command ignored");
        }

        String commandName = normalize(tokens.get(0));
        SuiteCommand command;
        synchronized (this) {
            command = commandsByName.get(commandName);
        }
        if (command == null) {
            return CommandExecutionResult.failed("unknown_command", "Unknown command: " + commandName + ". Run: help");
        }

        CommandInvocation invocation = new CommandInvocation(rawLine, commandName, tokens.subList(1, tokens.size()), Instant.now());
        activeExecutions.incrementAndGet();
        try {
            CommandExecutionResult result = ConsoleProgress.run(command.descriptor().name(), () -> command.execute(invocation));
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

    public synchronized SuiteCommand find(String nameOrAlias) {
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

    public synchronized void unregister(SuiteCommand command) {
        if (command == null) {
            return;
        }
        CommandDescriptor descriptor = command.descriptor();
        removeIfMapped(descriptor.name(), command);
        for (String alias : descriptor.aliases()) {
            removeIfMapped(alias, command);
        }
        commands.remove(command);
    }

    private void removeIfMapped(String name, SuiteCommand command) {
        String normalized = normalize(name);
        if (normalized.isBlank()) {
            return;
        }
        commandsByName.computeIfPresent(normalized, (key, current) -> current == command ? null : current);
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
