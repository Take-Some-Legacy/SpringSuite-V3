package com.takesome.springsuite.command;

import com.takesome.springsuite.logging.ConsoleAnsiBootstrap;
import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ConsoleCommandListener {
    private final CommandRegistry commandRegistry;
    private final ConsoleCommandProperties properties;
    private final OperatorLogService logService;
    private final ConsoleShellState shellState;
    private volatile boolean running;
    private volatile boolean ansiOutputDisabled;
    private Thread listenerThread;

    public ConsoleCommandListener(
            CommandRegistry commandRegistry,
            ConsoleCommandProperties properties,
            OperatorLogService logService,
            ConsoleShellState shellState
    ) {
        this.commandRegistry = commandRegistry;
        this.properties = properties;
        this.logService = logService;
        this.shellState = shellState;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.isEnabled()) {
            logService.append(OperatorLogLevel.INFO, "console", "console command listener disabled");
            return;
        }
        if (listenerThread != null) {
            return;
        }
        running = true;
        listenerThread = new Thread(this::runLoop, "suite-console-command-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        logService.append(OperatorLogLevel.INFO, "console", "console command listener started", Map.of(
                "prompt", prompt(),
                "mode", shellState.modeBanner(),
                "completion", "tab"
        ));
    }

    private void runLoop() {
        if (properties.isPrintWelcome()) {
            consolePrintln("");
            consolePrintln("SpringSuite shell ready. " + shellState.modeBanner());
            if (shellState.modeBanner().startsWith("mode=ELEVATED")) {
                consoleError("[SpringSuite][WARN] console running in elevated operator mode: " + shellState.modeBanner());
            }
            consolePrintln("UNIX-like commands: pwd, cd, ls, cat, grep, mkdir, rm, touch. Use ';', '&&' and '||'.");
        }

        try {
            runJLineLoop();
        } catch (Throwable ex) {
            logService.append(OperatorLogLevel.WARN, "console", "jline console unavailable; falling back to basic console", Map.of(
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
            runBasicLoop();
        }
    }

    private void runJLineLoop() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .build();
        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(new RegistryCompleter())
                .variable(LineReader.LIST_MAX, 100)
                .build();

        while (running) {
            String line;
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException ex) {
                continue;
            } catch (EndOfFileException ex) {
                running = false;
                break;
            }
            handleLine(line);
        }
    }

    private void runBasicLoop() {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (running) {
                consolePrint(prompt());
                String line = reader.readLine();
                if (line == null) {
                    running = false;
                    break;
                }
                handleLine(line);
            }
        } catch (IOException ex) {
            logService.append(OperatorLogLevel.WARN, "console", "console listener stopped", Map.of(
                    "error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()
            ));
        }
    }

    private void handleLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        long started = System.nanoTime();
        String sanitizedLine = sanitizeConsoleLine(line);
        try {
            CommandExecutionResult result = CommandExecutionContext.runAs(
                    CommandExecutionContext.Source.CONSOLE,
                    () -> commandRegistry.executeScript(line)
            );
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            logService.append(result.ok() ? OperatorLogLevel.INFO : OperatorLogLevel.WARN, "console", "console command completed", Map.of(
                    "line", sanitizedLine,
                    "ok", result.ok(),
                    "code", result.code(),
                    "durationMs", durationMs,
                    "activeExecutions", commandRegistry.activeExecutions()
            ));
            printResult(result);
        } catch (Exception ex) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            logService.append(OperatorLogLevel.ERROR, "console", "console command exception", Map.of(
                    "line", sanitizedLine,
                    "durationMs", durationMs,
                    "error", message
            ));
            printResult(CommandExecutionResult.failed("command_exception", message));
        }
    }

    private String prompt() {
        return shellState.prompt(properties.getPrompt());
    }

    private void printResult(CommandExecutionResult result) {
        if (Boolean.TRUE.equals(result.data().get("_consoleSilent"))) {
            return;
        }
        Object stdout = result.data().get("_stdout");
        if (stdout != null) {
            String text = Objects.toString(stdout, "");
            consolePrint(text);
            if (!text.endsWith("\n") && !text.endsWith("\r")) {
                consolePrintln("");
            }
            if (Boolean.TRUE.equals(result.data().get("_consoleRaw"))) {
                return;
            }
        }
        String marker = result.ok() ? "OK" : "ERR";
        consolePrintln(marker + " " + result.code() + " :: " + result.message());
        if (!result.data().isEmpty()) {
            result.data().forEach((key, value) -> {
                if (!key.startsWith("_")) {
                    printValue("  ", key, value);
                }
            });
        }
    }

    private void printValue(String indent, String key, Object value) {
        if (value instanceof List<?> list) {
            consolePrintln(indent + key + ":");
            if (list.isEmpty()) {
                consolePrintln(indent + "  <empty>");
                return;
            }
            for (Object item : list) {
                printListItem(indent + "  ", item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            consolePrintln(indent + key + ":");
            if (map.isEmpty()) {
                consolePrintln(indent + "  <empty>");
                return;
            }
            map.forEach((childKey, childValue) -> printValue(indent + "  ", String.valueOf(childKey), childValue));
            return;
        }
        consolePrintln(indent + key + " = " + value);
    }

    private void printListItem(String indent, Object item) {
        if (item instanceof Map<?, ?> map) {
            consolePrintln(indent + "-");
            map.forEach((key, value) -> printValue(indent + "  ", String.valueOf(key), value));
            return;
        }
        consolePrintln(indent + "- " + Objects.toString(item, ""));
    }


    private void consolePrint(String text) {
        writeConsole(text, false, false);
    }

    private void consolePrintln(String text) {
        writeConsole(text, true, false);
    }

    private void consoleError(String text) {
        writeConsole(text, true, true);
    }

    private void writeConsole(String text, boolean newline, boolean errorStream) {
        String value = Objects.toString(text, "");
        try {
            PrintStream stream = errorStream ? System.err : System.out;
            if (newline) {
                stream.println(value);
            } else {
                stream.print(value);
            }
            stream.flush();
        } catch (RuntimeException | LinkageError failure) {
            disableAnsiAfterConsoleFailure(failure);
            PrintStream plain = errorStream ? ConsoleAnsiBootstrap.plainErr() : ConsoleAnsiBootstrap.plainOut();
            if (newline) {
                plain.println(value);
            } else {
                plain.print(value);
            }
            plain.flush();
        }
    }

    private void disableAnsiAfterConsoleFailure(Throwable failure) {
        if (ansiOutputDisabled) {
            return;
        }
        ansiOutputDisabled = true;
        ConsoleAnsiBootstrap.disableAfterFailure(failure);
        try {
            logService.append(OperatorLogLevel.WARN, "console", "ANSI console output disabled after Jansi failure", Map.of(
                    "error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(),
                    "type", failure.getClass().getName()
            ));
        } catch (RuntimeException ignored) {
            ConsoleAnsiBootstrap.plainErr().println("[SpringSuite][WARN] ANSI console output disabled: " + failure);
        }
    }

    private String sanitizeConsoleLine(String line) {
        String value = line == null ? "" : line.trim();
        if (value.isBlank()) {
            return "";
        }
        value = value.replaceAll("(?i)(authorization\s*[:=]\s*bearer\s+)[^\s]+", "$1<redacted>");
        value = value.replaceAll("(?i)(api[-_]?key\s*[:=]\s*)[^\s]+", "$1<redacted>");
        value = value.replaceAll("sk-[A-Za-z0-9_-]+", "sk-<redacted>");
        value = value.replaceAll("sess-[A-Za-z0-9_-]+", "sess-<redacted>");
        return value.length() > 500 ? value.substring(0, 500) + "..." : value;
    }

    private final class RegistryCompleter implements Completer {
        @Override
        public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
            List<String> words = line.words();
            int wordIndex = line.wordIndex();
            if (wordIndex <= 0) {
                commandNames().forEach(name -> candidates.add(new Candidate(name)));
                return;
            }

            String command = words.isEmpty() ? "" : words.get(0);
            if (command.equals("help") || command.equals("?") || command.equals("commands")) {
                commandNames().forEach(name -> candidates.add(new Candidate(name)));
                return;
            }

            commandSubcommands(command).forEach(name -> candidates.add(new Candidate(name)));
        }

        private TreeSet<String> commandNames() {
            TreeSet<String> names = new TreeSet<>();
            for (CommandDescriptor descriptor : commandRegistry.descriptors()) {
                names.add(descriptor.name());
                names.addAll(descriptor.aliases());
            }
            return names;
        }

        private TreeSet<String> commandSubcommands(String command) {
            TreeSet<String> subcommands = new TreeSet<>();
            switch (command) {
                case "modules", "module", "mods" -> subcommands.addAll(List.of("summary", "list", "info"));
                case "toolbelt", "tools", "tb" -> subcommands.addAll(List.of("summary", "list", "info", "refresh", "dry-run", "run"));
                case "tunnel", "cf", "cloudflared" -> subcommands.addAll(List.of("status", "start", "stop", "restart", "logs"));
                case "publishers", "publisher", "pubs" -> subcommands.addAll(List.of("list", "fingerprint", "trust-cert", "trust-publisher", "block-cert", "revoke", "deploy", "build", "sign"));
                case "logs", "log" -> subcommands.addAll(List.of("20", "50", "100"));
                case "openai", "oai" -> subcommands.addAll(List.of("status", "setup", "refresh", "ask"));
                case "ai", "llm" -> subcommands.addAll(List.of("providers", "default", "status", "setup", "ask"));
                default -> {
                    // no command-specific completions
                }
            }
            return subcommands;
        }
    }
}
