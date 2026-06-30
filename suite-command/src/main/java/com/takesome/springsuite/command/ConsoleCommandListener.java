package com.takesome.springsuite.command;

import com.takesome.springsuite.logging.OperatorLogLevel;
import com.takesome.springsuite.logging.OperatorLogService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
    private volatile boolean running;
    private Thread listenerThread;

    public ConsoleCommandListener(
            CommandRegistry commandRegistry,
            ConsoleCommandProperties properties,
            OperatorLogService logService
    ) {
        this.commandRegistry = commandRegistry;
        this.properties = properties;
        this.logService = logService;
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
                "prompt", properties.getPrompt(),
                "completion", "tab"
        ));
    }

    private void runLoop() {
        if (properties.isPrintWelcome()) {
            System.out.println();
            System.out.println("SpringSuite console ready. Type 'help' or press TAB for commands.");
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
                line = reader.readLine(properties.getPrompt());
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
                System.out.print(properties.getPrompt());
                System.out.flush();
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
        try {
            CommandExecutionResult result = CommandExecutionContext.runAs(
                    CommandExecutionContext.Source.CONSOLE,
                    () -> commandRegistry.executeRaw(line)
            );
            printResult(result);
        } catch (Exception ex) {
            printResult(CommandExecutionResult.failed("command_exception", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
        }
    }

    private void printResult(CommandExecutionResult result) {
        if (Boolean.TRUE.equals(result.data().get("_consoleSilent"))) {
            return;
        }
        String marker = result.ok() ? "OK" : "ERR";
        System.out.println(marker + " " + result.code() + " :: " + result.message());
        if (!result.data().isEmpty()) {
            result.data().forEach((key, value) -> printValue("  ", key, value));
        }
    }

    private void printValue(String indent, String key, Object value) {
        if (value instanceof List<?> list) {
            System.out.println(indent + key + ":");
            if (list.isEmpty()) {
                System.out.println(indent + "  <empty>");
                return;
            }
            for (Object item : list) {
                printListItem(indent + "  ", item);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            System.out.println(indent + key + ":");
            if (map.isEmpty()) {
                System.out.println(indent + "  <empty>");
                return;
            }
            map.forEach((childKey, childValue) -> printValue(indent + "  ", String.valueOf(childKey), childValue));
            return;
        }
        System.out.println(indent + key + " = " + value);
    }

    private void printListItem(String indent, Object item) {
        if (item instanceof Map<?, ?> map) {
            System.out.println(indent + "-");
            map.forEach((key, value) -> printValue(indent + "  ", String.valueOf(key), value));
            return;
        }
        System.out.println(indent + "- " + Objects.toString(item, ""));
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
                default -> {
                    // no command-specific completions
                }
            }
            return subcommands;
        }
    }
}
